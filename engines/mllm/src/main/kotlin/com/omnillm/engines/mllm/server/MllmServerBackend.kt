package com.omnillm.engines.mllm.server

import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Real [ServerBackend] for mllm (ENGINE-MLLM) backed by the upstream
 * `mllm_server.aar` Go in-app server (UbiquitousLearning/mllm v2.0.0 /
 * mllm-chat v2.0.0).
 *
 * Integration shape (matches upstream + ENGINE-MLLM):
 * - `Gomllm.startServer(modelPath, ocrPath, tmpDir, enableProbing)` starts the
 *   OpenAI-compatible HTTP/SSE server on `127.0.0.1:8080` and registers a
 *   session for the chat model directory. **The server start IS the model
 *   load** — there is exactly one chat model slot per process.
 * - Chat requests: `POST /v1/chat/completions` with `model` = the model
 *   directory name, `messages` = real prompt content, `stream: true`, optional
 *   `session_id` (server-side KV session), `id` = operation token.
 * - Cancellation: connection close (server breaks its `r.Context()` poll);
 *   no explicit cancel RPC upstream (ENGINE-MLLM §6).
 *
 * Honest upstream limitations surfaced here (not silent):
 * - No unload API ⇒ [unloadModel] returns UNSUPPORTED_OPERATION.
 * - No stop API ⇒ [shutdown] returns CAPABILITY_UNKNOWN.
 * - No embedding API ⇒ [embed] returns UNSUPPORTED_OPERATION.
 * - Single model slot ⇒ second [loadModel] with a different path returns
 *   RESOURCE_EXHAUSTED.
 * - Server does not authenticate ⇒ credential is adapter-enforced
 *   ([authenticate] accepts only the runtime credential the adapter injected;
 *   [generate]/[loadModel] refuse without it).
 *
 * Fail closed: no credential, no resolved model path, unknown session token,
 * missing prompt body, unreachable server — all return errors; never success.
 */
class MllmServerBackend(
    private val bridge: MllmServerBridge,
    private val transport: MllmHttpTransport,
    private val serverHost: String = MllmOpenAiProtocol.HOST,
    private val serverPort: Int = MllmOpenAiProtocol.PORT,
) : ServerBackend {

    override fun libraryLabel(): String = "mllm-server-gomllm"

    override fun isAvailable(): Boolean = lifecycle.get() != ServerLifecycleState.STOPPED

    override fun lifecycleState(): ServerLifecycleState = lifecycle.get()

    private val lifecycle = AtomicReference(ServerLifecycleState.STOPPED)
    private val channelConfig = AtomicReference<PrivateChannelConfig?>(null)

    /** Single chat-model slot: model dir → opaque token. */
    private val modelSlot = AtomicReference<ServerModelToken?>(null)
    private val modelPath = AtomicReference<String?>(null)
    private val modelName = AtomicReference<String?>(null)

    private val sessions = ConcurrentHashMap<String, ServerSessionToken>()
    private val cancelTokens = ConcurrentHashMap.newKeySet<String>()

    private val modelSeq = AtomicInteger(0)

    // --- lifecycle / channel ---

    override fun ensureReady(config: PrivateChannelConfig): ServerResult<Unit> {
        config.policyRefuseReason()?.let { reason ->
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.CHANNEL_POLICY,
                    message = reason,
                    attributes = mapOf(
                        "channelKind" to config.kind.name,
                        "capability" to "mllm.CHANNEL",
                    ),
                ),
            )
        }
        if (!config.hasCredential()) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.CHANNEL_POLICY,
                    message = "runtime credential required (ENGINE-MLLM §7)",
                ),
            )
        }
        channelConfig.set(config)
        // The Go server process itself starts on first loadModel (the upstream
        // entry point binds a model); READY here means "channel accepted".
        lifecycle.set(ServerLifecycleState.READY)
        return ServerResult.ok(Unit)
    }

    override fun authenticate(request: ChannelAuthRequest): ServerResult<ChannelAuthOutcome> {
        val cfg = channelConfig.get()
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_UNREACHABLE,
                    message = "channel not configured",
                ),
            )
        if (request.runtimeCredential != cfg.runtimeCredential) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.AUTH_FAILED,
                    message = "runtime credential mismatch",
                ),
            )
        }
        if (request.protocolVersion != PrivateChannelProtocol.PROTOCOL_VERSION) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.AUTH_FAILED,
                    message = "protocol version mismatch",
                    attributes = mapOf(
                        "requested" to request.protocolVersion.toString(),
                        "server" to PrivateChannelProtocol.PROTOCOL_VERSION.toString(),
                    ),
                ),
            )
        }
        return ServerResult.ok(
            ChannelAuthOutcome(
                accepted = true,
                serverProtocolVersion = PrivateChannelProtocol.PROTOCOL_VERSION,
                attributes = mapOf(
                    "method" to PrivateChannelProtocol.Methods.HEALTH,
                    // Upstream server has no auth; enforcement is adapter-side.
                    "authModel" to "adapter-enforced",
                    "serverAuthenticates" to "false",
                ),
            ),
        )
    }

    override fun probe(request: ServerProbeRequest): ServerResult<ServerProbeOutcome> {
        val started = modelSlot.get() != null
        if (!started) {
            return ServerResult.ok(
                ServerProbeOutcome(
                    available = false,
                    backend = request.backend,
                    attributes = mapOf(
                        "library" to libraryLabel(),
                        "operationToken" to request.operationToken,
                        "native" to "true",
                        "started" to "false",
                        "protocol" to PrivateChannelProtocol.PROTOCOL_ID,
                        "method" to PrivateChannelProtocol.Methods.PROBE,
                    ),
                ),
            )
        }
        val reachable = tcpReachable()
        return ServerResult.ok(
            ServerProbeOutcome(
                available = reachable,
                backend = request.backend,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "true",
                    "started" to "true",
                    "reachable" to reachable.toString(),
                    "protocol" to PrivateChannelProtocol.PROTOCOL_ID,
                    "method" to PrivateChannelProtocol.Methods.PROBE,
                ),
            ),
        )
    }

    override fun loadModel(request: ServerLoadRequest): ServerResult<ServerModelToken> {
        val existing = modelSlot.get()
        if (existing != null) {
            if (request.resolvedModelPath == modelPath.get()) {
                // Idempotent reload of the same model directory.
                return ServerResult.ok(existing)
            }
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.RESOURCE_EXHAUSTED,
                    message = "mllm in-app server is a single-model slot; " +
                        "unload/restart process before loading a different model",
                    attributes = mapOf(
                        "capability" to "mllm.LOAD",
                        "method" to PrivateChannelProtocol.Methods.LOAD_MODEL,
                    ),
                ),
            )
        }
        val cfg = channelConfig.get()
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.CHANNEL_POLICY,
                    message = "ensureReady(credential) required before load",
                ),
            )
        val path = request.resolvedModelPath?.takeIf { it.isNotEmpty() }
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.MODEL_OPEN_FAILED,
                    message = "resolvedModelPath required (privileged path broker, INV-010) — " +
                        "no silent synthetic load",
                    attributes = mapOf("capability" to "mllm.LOAD"),
                ),
            )
        val name = deriveModelName(path, request.attributes["modelName"])

        lifecycle.set(ServerLifecycleState.STARTING)
        val status = try {
            bridge.startServer(
                modelPath = path,
                ocrPath = "",
                tmpDir = request.attributes["tmpDir"].orEmpty().ifEmpty {
                    defaultTmpDir()
                },
                enableProbing = false,
            )
        } catch (t: Throwable) {
            lifecycle.set(ServerLifecycleState.FAILED)
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_CRASH,
                    message = "Gomllm.startServer failed: ${t.message ?: t::class.simpleName}",
                ),
            )
        }
        if (!tcpReachable()) {
            lifecycle.set(ServerLifecycleState.FAILED)
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_CRASH,
                    message = "in-app server not reachable on $serverHost:$serverPort " +
                        "after start (upstream status: $status)",
                ),
            )
        }
        val token = ServerModelToken("mllm-model-${modelSeq.incrementAndGet()}")
        modelSlot.set(token)
        modelPath.set(path)
        modelName.set(name)
        lifecycle.set(ServerLifecycleState.READY)
        return ServerResult.ok(token)
    }

    override fun createSession(
        model: ServerModelToken,
        request: ServerSessionRequest,
    ): ServerResult<ServerSessionToken> {
        if (model.value != modelSlot.get()?.value) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        // Session is created lazily server-side on first request with this
        // session_id (upstream GetSession/SendRequest model).
        val token = ServerSessionToken("mllm-session-${UUID.randomUUID()}")
        sessions[token.value] = token
        return ServerResult.ok(token)
    }

    override fun generate(
        session: ServerSessionToken,
        request: ServerGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (ServerStreamEvent) -> Unit,
    ): ServerResult<ServerGenerateOutcome> {
        if (!sessions.containsKey(session.value)) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        }
        val cfg = channelConfig.get()
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.CHANNEL_POLICY,
                    message = "channel not configured (ensureReady first)",
                ),
            )
        val model = modelSlot.get()
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.MODEL_OPEN_FAILED,
                    message = "no model loaded",
                ),
            )
        val name = modelName.get()
            ?: return ServerResult.err(
                ServerError(code = ServerErrorCode.INTERNAL, message = "model name unresolved"),
            )
        val prompt = request.promptUtf8?.takeIf { it.isNotEmpty() }
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.INVALID_ARGUMENT,
                    message = "promptUtf8 required: control plane must resolve " +
                        "canonicalInputDigest to content before start",
                ),
            )
        if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
            onEvent(
                ServerStreamEvent.Stop(
                    stopReason = MllmOpenAiProtocol.STOP_REASON_CANCELLED,
                    attributes = mapOf("completionTokens" to "0"),
                ),
            )
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.CANCELLED,
                    message = "generate cancelled before start",
                    attributes = mapOf("completionTokens" to "0"),
                ),
            )
        }

        onEvent(
            ServerStreamEvent.Metadata(
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "method" to PrivateChannelProtocol.Methods.GENERATE,
                    "sessionId" to session.value,
                ),
            ),
        )

        val body = MllmOpenAiProtocol.buildChatBody(
            modelName = name,
            promptUtf8 = prompt,
            requestId = request.operationToken,
            sessionId = session.value,
        )

        var deltaCount = 0
        var sawStop = false
        val result = transport.postChatCompletions(
            url = MllmOpenAiProtocol.chatCompletionsUrl(serverHost, serverPort),
            bodyJson = body,
            credentialHeader = cfg.runtimeCredential,
            cancelFlag = {
                cancelFlag() || cancelTokens.contains(request.operationToken)
            },
            onData = { data ->
                when (val event = MllmOpenAiProtocol.parseSseData(data)) {
                    is MllmOpenAiProtocol.SseEvent.Delta -> {
                        deltaCount++
                        onEvent(
                            ServerStreamEvent.Delta(
                                textFragment = event.text,
                                attributes = mapOf(
                                    "index" to deltaCount.toString(),
                                    "usageAvailable" to "false",
                                ),
                            ),
                        )
                        true
                    }
                    is MllmOpenAiProtocol.SseEvent.Stop -> {
                        sawStop = true
                        onEvent(
                            ServerStreamEvent.Stop(
                                stopReason = event.reason,
                                attributes = mapOf("deltaCount" to deltaCount.toString()),
                            ),
                        )
                        false
                    }
                    is MllmOpenAiProtocol.SseEvent.Warning -> {
                        onEvent(ServerStreamEvent.Warning(attributes = mapOf("message" to event.message)))
                        true
                    }
                    MllmOpenAiProtocol.SseEvent.Ignore -> true
                }
            },
        )

        return when (result) {
            is MllmHttpTransport.Result.Completed -> {
                if (!sawStop) {
                    onEvent(
                        ServerStreamEvent.Stop(
                            stopReason = MllmOpenAiProtocol.STOP_REASON_COMPLETED,
                            attributes = mapOf("deltaCount" to deltaCount.toString()),
                        ),
                    )
                }
                onEvent(
                    ServerStreamEvent.Usage(
                        attributes = mapOf(
                            "deltaCount" to deltaCount.toString(),
                            "promptTokens" to "0",
                            "completionTokens" to "0",
                            "usageAvailable" to "false",
                        ),
                    ),
                )
                ServerResult.ok(
                    ServerGenerateOutcome(
                        finished = true,
                        promptTokens = 0,
                        completionTokens = 0,
                        stopReason = MllmOpenAiProtocol.STOP_REASON_COMPLETED,
                        attributes = mapOf("deltaCount" to deltaCount.toString()),
                    ),
                )
            }
            is MllmHttpTransport.Result.Cancelled -> {
                onEvent(
                    ServerStreamEvent.Stop(
                        stopReason = MllmOpenAiProtocol.STOP_REASON_CANCELLED,
                        attributes = mapOf("deltaCount" to deltaCount.toString()),
                    ),
                )
                ServerResult.err(
                    ServerError(
                        code = ServerErrorCode.CANCELLED,
                        message = "generate cancelled (connection closed cooperatively)",
                        attributes = mapOf("deltaCount" to deltaCount.toString()),
                    ),
                )
            }
            is MllmHttpTransport.Result.Failed -> {
                onEvent(
                    ServerStreamEvent.Stop(
                        stopReason = MllmOpenAiProtocol.STOP_REASON_ERROR,
                        attributes = mapOf("deltaCount" to deltaCount.toString()),
                    ),
                )
                ServerResult.err(
                    ServerError(
                        code = if (result.status == 404) {
                            ServerErrorCode.MODEL_OPEN_FAILED
                        } else {
                            ServerErrorCode.GENERATE_FAILED
                        },
                        message = result.message,
                        attributes = mapOf(
                            "httpStatus" to result.status.toString(),
                            "deltaCount" to deltaCount.toString(),
                        ),
                    ),
                )
            }
        }
    }

    override fun embed(
        model: ServerModelToken,
        request: ServerEmbedRequest,
    ): ServerResult<ServerEmbedOutcome> = ServerResult.err(
        ServerError(
            code = ServerErrorCode.UNSUPPORTED_OPERATION,
            message = "upstream mllm in-app server exposes no embedding API " +
                "(ENGINE-MLLM §9 embedding per-cell)",
            attributes = mapOf("capability" to "mllm.EMBED"),
        ),
    )

    override fun closeSession(session: ServerSessionToken): ServerResult<Unit> {
        sessions.remove(session.value)
        // Upstream has no close RPC; the server-side KV session persists until
        // process death. Surface honestly rather than claiming a server close.
        return ServerResult.ok(Unit)
    }

    override fun unloadModel(model: ServerModelToken): ServerResult<Unit> = ServerResult.err(
        ServerError(
            code = ServerErrorCode.UNSUPPORTED_OPERATION,
            message = "upstream mllm in-app server has no unload API; the model " +
                "stays resident until process death (single model slot)",
            attributes = mapOf("capability" to "mllm.UNLOAD"),
        ),
    )

    override fun requestCancel(operationToken: String): ServerResult<Unit> {
        cancelTokens.add(operationToken)
        return ServerResult.ok(Unit)
    }

    override fun shutdown(): ServerResult<Unit> = ServerResult.err(
        ServerError(
            code = ServerErrorCode.CAPABILITY_UNKNOWN,
            message = "upstream gomllm exposes no stop API; the Go server stops " +
                "at process death (documented limitation)",
            attributes = mapOf("capability" to "mllm.SHUTDOWN"),
        ),
    )

    // --- internal helpers ---

    private fun deriveModelName(modelDirPath: String, override: String?): String {
        override?.takeIf { it.isNotEmpty() }?.let { return it }
        // Upstream registers the session under the model directory name
        // (demo default "qwen3" for /sdcard/Download/model/qwen3).
        val trimmed = modelDirPath.trimEnd('/').trimEnd('\\')
        return trimmed.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { trimmed }
    }

    private fun defaultTmpDir(): String = System.getProperty("java.io.tmpdir").orEmpty()

    private fun tcpReachable(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS: Int = 3000
    }
}
