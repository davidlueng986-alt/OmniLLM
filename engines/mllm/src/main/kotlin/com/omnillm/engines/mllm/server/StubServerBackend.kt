package com.omnillm.engines.mllm.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fail-closed exploratory stub for unit wiring without real `mllm_server.aar`.
 *
 * **Not** a production backend. Does not start a Go server, open sockets, or
 * bind ports. Dry "load" never elevates model trust (INV-008).
 *
 * Default behavior: every execute path returns [ServerErrorCode.NOT_LOCKED] or
 * [ServerErrorCode.CAPABILITY_UNKNOWN] so the adapter maps to
 * [com.omnillm.core.errors.generated.OmniError.CAPABILITY_UNKNOWN].
 *
 * Set [exploratoryDryRun] only in controlled tests that need Plan→Commit plumbing.
 *
 * TODO(AAR): replace with real private-channel binding under worker process
 * with 16 KB page-size verification and orphan cleanup.
 */
class StubServerBackend(
    /**
     * When true (template lock incomplete), unproven ops report NOT_LOCKED.
     * When false and [lockComplete] true, report CAPABILITY_UNKNOWN.
     */
    private val lockComplete: Boolean = false,
    /**
     * When true, allows synthetic probe/load/generate for architecture tests only.
     * Default false — unproven capabilities stay UNKNOWN / NOT_LOCKED.
     */
    var exploratoryDryRun: Boolean = false,
    var deltaCount: Int = 2,
) : ServerBackend {

    private val models = ConcurrentHashMap<String, ServerLoadRequest>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val modelSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)
    private val cancelTokens = ConcurrentHashMap.newKeySet<String>()
    private val lifecycle = AtomicReference(ServerLifecycleState.STOPPED)
    private val channelConfig = AtomicReference<PrivateChannelConfig?>(null)

    override fun libraryLabel(): String = "mllm-server-stub"

    override fun isAvailable(): Boolean = exploratoryDryRun

    override fun lifecycleState(): ServerLifecycleState = lifecycle.get()

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
        if (!exploratoryDryRun) {
            return unproven("ENSURE_READY")
        }
        channelConfig.set(config)
        lifecycle.set(ServerLifecycleState.READY)
        return ServerResult.ok(Unit)
    }

    override fun authenticate(request: ChannelAuthRequest): ServerResult<ChannelAuthOutcome> {
        if (!exploratoryDryRun) {
            return unproven("AUTH")
        }
        val cfg = channelConfig.get()
            ?: return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.SERVER_UNREACHABLE,
                    message = "channel not ready",
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
                attributes = mapOf("method" to PrivateChannelProtocol.Methods.HEALTH),
            ),
        )
    }

    override fun probe(request: ServerProbeRequest): ServerResult<ServerProbeOutcome> {
        if (!exploratoryDryRun) {
            return unproven("PROBE")
        }
        return ServerResult.ok(
            ServerProbeOutcome(
                available = true,
                backend = request.backend,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "false",
                    "exploratory" to "true",
                    "protocol" to PrivateChannelProtocol.PROTOCOL_ID,
                    "method" to PrivateChannelProtocol.Methods.PROBE,
                ),
            ),
        )
    }

    override fun loadModel(request: ServerLoadRequest): ServerResult<ServerModelToken> {
        if (!exploratoryDryRun) {
            return unproven("LOAD")
        }
        val id = "stub-mllm-model-${modelSeq.incrementAndGet()}"
        models[id] = request
        return ServerResult.ok(ServerModelToken(id))
    }

    override fun createSession(
        model: ServerModelToken,
        request: ServerSessionRequest,
    ): ServerResult<ServerSessionToken> {
        if (!exploratoryDryRun) {
            return unproven("CREATE_SESSION")
        }
        if (!models.containsKey(model.value)) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        val id = "stub-mllm-session-${sessionSeq.incrementAndGet()}"
        sessions[id] = model.value
        return ServerResult.ok(ServerSessionToken(id))
    }

    override fun generate(
        session: ServerSessionToken,
        request: ServerGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (ServerStreamEvent) -> Unit,
    ): ServerResult<ServerGenerateOutcome> {
        if (!exploratoryDryRun) {
            return unproven("GENERATE")
        }
        if (!sessions.containsKey(session.value)) {
            return ServerResult.err(
                ServerError(
                    code = ServerErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        }

        onEvent(
            ServerStreamEvent.Metadata(
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "method" to PrivateChannelProtocol.Methods.GENERATE,
                ),
            ),
        )

        var completed = 0
        for (i in 0 until deltaCount) {
            if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
                onEvent(
                    ServerStreamEvent.Stop(
                        stopReason = "CANCELLED",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
                return ServerResult.err(
                    ServerError(
                        code = ServerErrorCode.CANCELLED,
                        message = "stub generate cancelled",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
            }
            onEvent(
                ServerStreamEvent.Delta(
                    textFragment = "",
                    payloadDigestHex = STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to i.toString()),
                ),
            )
            completed++
        }

        onEvent(
            ServerStreamEvent.Usage(
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            ServerStreamEvent.Stop(
                stopReason = "COMPLETED",
                attributes = mapOf("completionTokens" to completed.toString()),
            ),
        )
        return ServerResult.ok(
            ServerGenerateOutcome(
                finished = true,
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: ServerModelToken,
        request: ServerEmbedRequest,
    ): ServerResult<ServerEmbedOutcome> {
        // Embedding always unproven in scaffold until API + cell evidence (ENGINE-MLLM §9)
        return unproven("EMBED")
    }

    override fun closeSession(session: ServerSessionToken): ServerResult<Unit> {
        sessions.remove(session.value)
        return ServerResult.ok(Unit)
    }

    override fun unloadModel(model: ServerModelToken): ServerResult<Unit> {
        val sessionsToClose = sessions.filterValues { it == model.value }.keys
        sessionsToClose.forEach { sessions.remove(it) }
        models.remove(model.value)
        return ServerResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): ServerResult<Unit> {
        cancelTokens.add(operationToken)
        return ServerResult.ok(Unit)
    }

    override fun queryCommit(request: ServerCommitQueryRequest): ServerResult<ServerCommitQueryOutcome> {
        // Scaffold: no server-side ledger; adapter local journal is authoritative.
        return unproven("COMMIT_QUERY")
    }

    override fun shutdown(): ServerResult<Unit> {
        sessions.clear()
        models.clear()
        cancelTokens.clear()
        channelConfig.set(null)
        lifecycle.set(ServerLifecycleState.SHUTDOWN)
        return ServerResult.ok(Unit)
    }

    private fun <T> unproven(operation: String): ServerResult<T> {
        val code = if (!lockComplete) {
            ServerErrorCode.NOT_LOCKED
        } else {
            ServerErrorCode.CAPABILITY_UNKNOWN
        }
        return ServerResult.err(
            ServerError(
                code = code,
                message = "mllm $operation not executable until lock + qualification PASS",
                attributes = mapOf(
                    "capability" to "mllm.$operation",
                    "operation" to operation,
                    "engineId" to "mllm",
                    "protocol" to PrivateChannelProtocol.PROTOCOL_ID,
                ),
            ),
        )
    }

    companion object {
        const val STUB_DELTA_DIGEST: String =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
