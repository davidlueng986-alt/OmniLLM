package com.omnillm.engines.mllm.server

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
 * - The upstream mobile StartServer launches its HTTP listener in a goroutine
 *   and drops the bind error (returns a nominal "Success" even when another
 *   process pre-bound 127.0.0.1:8080) ⇒ [loadModel] runs a post-start
 *   IDENTITY PROBE (D3): a per-session nonce is reflected through the
 *   upstream model-not-found 404 template; any mismatch or refusal fails
 *   closed (SERVER_IMPERSONATED / SERVER_CRASH). The port is fixed upstream
 *   (no ephemeral-port option — verified against the AAR binding).
 *
 * Fail closed: no credential, no resolved model path, unknown session token,
 * missing prompt body, unreachable server, unverifiable server identity — all
 * return errors; never success.
 */
class MllmServerBackend(
    private val bridge: MllmServerBridge,
    private val transport: MllmHttpTransport,
    private val serverHost: String = MllmOpenAiProtocol.HOST,
    private val serverPort: Int = MllmOpenAiProtocol.PORT,
    /**
     * Post-start identity probe requirement (D3). Default REQUIRED. When the
     * probe cannot run, loadModel fails closed with SERVER_IMPERSONATED —
     * there is no load path without identity verification (escape hatch that
     * only makes the failure explicit; never disables the check silently).
     */
    private val identityProbeEnabled: Boolean = true,
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

    /**
     * Bounded cooperative-cancel registry (D19): cap + FIFO eviction +
     * consume-on-completion — never unbounded (mirrors the native 1024 cap).
     */
    internal val cancelRegistry: BoundedCancelRegistry = BoundedCancelRegistry()

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
        val outcome = identityProbe()
        val available = outcome == IdentityProbeOutcome.VERIFIED
        return ServerResult.ok(
            ServerProbeOutcome(
                available = available,
                backend = request.backend,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "true",
                    "started" to "true",
                    // Identity-verified reachability — a bare TCP connect is
                    // never proof of OUR server (D3).
                    "reachable" to available.toString(),
                    "identityVerified" to available.toString(),
                    "probe" to "nonce-reflection",
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
        if (isStartFailureStatus(status)) {
            lifecycle.set(ServerLifecycleState.FAILED)
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_CRASH,
                    message = "Gomllm.startServer reported failure: $status — " +
                        "load fails closed (status is never swallowed)",
                    attributes = mapOf("capability" to "mllm.LOAD"),
                ),
            )
        }
        if (!identityProbeEnabled) {
            // Escape hatch: environments where probing is impossible fail
            // closed — there is NO load path without identity verification.
            lifecycle.set(ServerLifecycleState.FAILED)
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_IMPERSONATED,
                    message = "identity probe disabled; refusing unverified load " +
                        "(fail-closed escape hatch, D3)",
                    attributes = mapOf("capability" to "mllm.LOAD"),
                ),
            )
        }
        when (val outcome = identityProbe()) {
            IdentityProbeOutcome.VERIFIED -> Unit
            IdentityProbeOutcome.REFUSED -> {
                // Nothing (or nothing reachable) answered — the upstream server
                // failed to come up (the mobile StartServer drops the goroutine
                // bind error, so the status string alone is not authoritative).
                lifecycle.set(ServerLifecycleState.FAILED)
                return ServerResult.err(
                    ServerError(
                        code = ServerErrorCode.SERVER_CRASH,
                        message = "in-app server refused the identity probe on " +
                            "$serverHost:$serverPort after start " +
                            "(upstream status: $status)",
                    ),
                )
            }
            IdentityProbeOutcome.IMPERSONATED -> {
                // The port answered but did not prove it is the upstream mllm
                // server — consistent with a pre-bound port / port squat.
                lifecycle.set(ServerLifecycleState.FAILED)
                return ServerResult.err(
                    ServerError(
                        code = ServerErrorCode.SERVER_IMPERSONATED,
                        message = "loopback port $serverHost:$serverPort answered but " +
                            "failed the post-start identity probe — another process " +
                            "may have pre-bound the port; refusing load " +
                            "(upstream status: $status)",
                        attributes = mapOf("capability" to "mllm.LOAD"),
                    ),
                )
            }
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
        if (cancelFlag() || cancelRegistry.contains(request.operationToken)) {
            cancelRegistry.consume(request.operationToken)
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
        val result = try {
            transport.postChatCompletions(
                url = MllmOpenAiProtocol.chatCompletionsUrl(serverHost, serverPort),
                bodyJson = body,
                credentialHeader = cfg.runtimeCredential,
                cancelFlag = {
                    cancelFlag() || cancelRegistry.contains(request.operationToken)
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
        } finally {
            // D19: a completed operation consumes its cancel token — the
            // registry never grows without bound.
            cancelRegistry.consume(request.operationToken)
        }

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
        cancelRegistry.add(operationToken)
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

    /**
     * D3 identity probe outcome. A bare TCP connect proves nothing — an
     * attacker app can pre-bind 127.0.0.1:8080 before our in-process Go
     * server starts (the upstream mobile StartServer launches the listener in
     * a goroutine and DROPS the bind error, returning "Success").
     */
    private enum class IdentityProbeOutcome { VERIFIED, REFUSED, IMPERSONATED }

    /**
     * Post-start identity probe: send a per-session unique nonce to the
     * loopback server as an unknown model name and require the exact upstream
     * 404 error template reflecting our nonce (mllm-cli/pkg/server/
     * handlers.go, verified 2026-08-15). Only the real upstream server
     * produces that shape; a passive squat (port bound, no service), a
     * generic OpenAI-compatible impostor, or our own server that failed to
     * bind all fail closed.
     */
    private fun identityProbe(): IdentityProbeOutcome {
        val cfg = channelConfig.get() ?: return IdentityProbeOutcome.REFUSED
        val nonce = "omnillm-${UUID.randomUUID()}"
        val probeModel = MllmOpenAiProtocol.identityProbeModel(nonce)
        val body = MllmOpenAiProtocol.buildIdentityProbeBody(probeModel, nonce)
        return try {
            val result = transport.postJson(
                url = MllmOpenAiProtocol.chatCompletionsUrl(serverHost, serverPort),
                bodyJson = body,
                credentialHeader = cfg.runtimeCredential,
            )
            when (result) {
                is MllmHttpTransport.Result.Failed ->
                    if (MllmOpenAiProtocol.verifyIdentityProbeResponse(
                            status = result.status,
                            body = result.message.orEmpty(),
                            probeModelName = probeModel,
                        )
                    ) {
                        IdentityProbeOutcome.VERIFIED
                    } else if (result.status == 0) {
                        IdentityProbeOutcome.REFUSED
                    } else {
                        IdentityProbeOutcome.IMPERSONATED
                    }
                else -> IdentityProbeOutcome.IMPERSONATED
            }
        } catch (_: Throwable) {
            IdentityProbeOutcome.REFUSED
        }
    }

    /**
     * Advisory check of the upstream status string. The upstream mobile
     * StartServer drops its goroutine bind error and returns "Success", so a
     * nominal status is NOT proof of a live server — the identity probe is
     * authoritative. This catches only clearly-failing statuses.
     */
    private fun isStartFailureStatus(status: String): Boolean {
        val lower = status.lowercase()
        return listOf("error", "fail", "panic", "already in use", "address in use")
            .any { lower.contains(it) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS: Int = 3000
    }
}
