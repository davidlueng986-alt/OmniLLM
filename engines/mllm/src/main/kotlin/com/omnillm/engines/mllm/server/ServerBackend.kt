package com.omnillm.engines.mllm.server

/**
 * Private embedded-server surface for mllm (ENGINE-MLLM §2–§3).
 *
 * Real implementation will:
 * - own `mllm_server.aar` lifecycle (start / ready / drain / shutdown)
 * - open a **private** channel (prefer UDS / private IPC; localhost only with isolation)
 * - inject runtime-generated credential + operation epoch
 * - translate canonical probe/load/commit/start to server RPC
 * - map SSE/HTTP/RPC streams to canonical events (never double-write as client delivery)
 *
 * Opaque tokens only — no server-internal pointers across process.
 *
 * **TODO(AAR):** wire real Go server packaging (ANDROID-NATIVE, 16 KB pages).
 * Until then [StubServerBackend] reports NOT_AVAILABLE / CAPABILITY_UNKNOWN.
 *
 * Adapters using this port must never write OmniLLM DB/model store, never bind LAN,
 * never use CORS wildcard or unauthenticated management.
 */
interface ServerBackend {
    /** Diagnostics label (not a trust elevation). */
    fun libraryLabel(): String

    fun isAvailable(): Boolean

    /** Current embedded-server lifecycle (best-effort diagnostics). */
    fun lifecycleState(): ServerLifecycleState = ServerLifecycleState.STOPPED

    /**
     * Ensure server process/AAR is started and private channel is ready.
     * Must not bind LAN; must install runtime credential before serving.
     * Plan paths never call this (ADR-002).
     */
    fun ensureReady(config: PrivateChannelConfig): ServerResult<Unit> =
        ServerResult.Err(
            ServerError(
                code = ServerErrorCode.NOT_AVAILABLE,
                message = "ensureReady not implemented for ${libraryLabel()}",
            ),
        )

    /** Authenticate the private channel (credential + protocol version). */
    fun authenticate(request: ChannelAuthRequest): ServerResult<ChannelAuthOutcome> =
        ServerResult.Err(
            ServerError(
                code = ServerErrorCode.AUTH_FAILED,
                message = "authenticate not implemented for ${libraryLabel()}",
            ),
        )

    /**
     * Bounded probe of server/backend presence. Must not load untrusted models
     * in a privileged process.
     */
    fun probe(request: ServerProbeRequest): ServerResult<ServerProbeOutcome>

    fun loadModel(request: ServerLoadRequest): ServerResult<ServerModelToken>

    fun createSession(
        model: ServerModelToken,
        request: ServerSessionRequest,
    ): ServerResult<ServerSessionToken>

    /**
     * Prefill/decode stream. [cancelFlag] is polled cooperatively; socket close
     * alone does **not** prove native stop (ENGINE-MLLM §6).
     */
    fun generate(
        session: ServerSessionToken,
        request: ServerGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (ServerStreamEvent) -> Unit,
    ): ServerResult<ServerGenerateOutcome>

    fun embed(
        model: ServerModelToken,
        request: ServerEmbedRequest,
    ): ServerResult<ServerEmbedOutcome>

    fun closeSession(session: ServerSessionToken): ServerResult<Unit>

    fun unloadModel(model: ServerModelToken): ServerResult<Unit>

    fun requestCancel(operationToken: String): ServerResult<Unit>

    /**
     * Optional server-side commit query. Default: CAPABILITY_UNKNOWN
     * (local adapter ledger remains authoritative until protocol evidence).
     */
    fun queryCommit(request: ServerCommitQueryRequest): ServerResult<ServerCommitQueryOutcome> =
        ServerResult.Err(
            ServerError(
                code = ServerErrorCode.CAPABILITY_UNKNOWN,
                message = "server commit query not proven for this protocol",
                attributes = mapOf(
                    "capability" to "mllm.COMMIT_QUERY",
                    "method" to PrivateChannelProtocol.Methods.COMMIT_QUERY,
                ),
            ),
        )

    /** Best-effort orphan / shutdown cleanup (port FD reclaim). */
    fun shutdown(): ServerResult<Unit>
}

/** Opaque model handle — never a raw pointer on wire. */
@JvmInline
value class ServerModelToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "ServerModelToken must be non-empty" }
    }
}

/** Opaque server conversation/session handle (ENGINE-MLLM §5). */
@JvmInline
value class ServerSessionToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "ServerSessionToken must be non-empty" }
    }
}

data class ServerProbeRequest(
    val backend: String,
    val operationToken: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(backend.isNotEmpty())
        require(operationToken.isNotEmpty())
    }
}

data class ServerProbeOutcome(
    val available: Boolean,
    val backend: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class ServerLoadRequest(
    /** Opaque storage key / FD broker id — never a client-supplied absolute path. */
    val storageRootKey: String,
    val installationKey: String,
    val backend: String,
    val privilegedLoadTicketId: String,
    /**
     * Runtime-resolved read-only model directory after privileged re-verify
     * (INV-010). The upstream Go server loads a model directory at startup
     * (`Gomllm.startServer(modelPath, ...)`), so this is the only real load input.
     * Null ⇒ fail closed (MODEL_OPEN_FAILED) — no silent synthetic load.
     */
    val resolvedModelPath: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty())
        require(installationKey.isNotEmpty())
        require(backend.isNotEmpty())
        require(privilegedLoadTicketId.isNotEmpty())
    }
}

data class ServerSessionRequest(
    val ownerKey: String = "",
    val runtimeEpoch: Long = 0L,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(runtimeEpoch >= 0L)
    }
}

data class ServerGenerateRequest(
    val operationToken: String,
    val canonicalInputDigest: String,
    val maxTokens: Int = 16,
    /**
     * Optional UTF-8 prompt body for the OpenAI-compatible chat request. The
     * upstream server requires actual message content; when null the backend
     * fails closed with INVALID_ARGUMENT (never fabricate from the digest).
     * The control plane resolves canonicalInputDigest → content (Stage-5 work).
     */
    val promptUtf8: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(operationToken.isNotEmpty())
        require(canonicalInputDigest.isNotEmpty())
        require(maxTokens > 0)
    }
}

data class ServerGenerateOutcome(
    val finished: Boolean,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val stopReason: String = "UNKNOWN",
    val attributes: Map<String, String> = emptyMap(),
)

data class ServerEmbedRequest(
    val operationToken: String,
    val canonicalInputDigest: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(operationToken.isNotEmpty())
        require(canonicalInputDigest.isNotEmpty())
    }
}

data class ServerEmbedOutcome(
    val dimensions: Int,
    val normalized: Boolean = false,
    val vectorDigestHex: String = "",
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Low-level stream events from server SSE/RPC. Normalized to catalog
 * [com.omnillm.engines.api.EngineEvent] by EventNormalizer.
 */
sealed class ServerStreamEvent {
    data class Metadata(val attributes: Map<String, String> = emptyMap()) : ServerStreamEvent()
    data class Delta(
        val textFragment: String = "",
        val payloadDigestHex: String? = null,
        val attributes: Map<String, String> = emptyMap(),
    ) : ServerStreamEvent()
    data class Usage(val attributes: Map<String, String> = emptyMap()) : ServerStreamEvent()
    data class Diagnostic(val attributes: Map<String, String> = emptyMap()) : ServerStreamEvent()
    data class Warning(val attributes: Map<String, String> = emptyMap()) : ServerStreamEvent()
    data class Stop(
        val stopReason: String = "COMPLETED",
        val attributes: Map<String, String> = emptyMap(),
    ) : ServerStreamEvent()
}

enum class ServerErrorCode {
    NOT_AVAILABLE,
    NOT_LOCKED,
    INVALID_ARGUMENT,
    UNSUPPORTED_PARAMETER,
    UNSUPPORTED_OPERATION,
    CAPABILITY_UNKNOWN,
    SERVER_UNREACHABLE,
    AUTH_FAILED,
    CHANNEL_POLICY,
    MODEL_OPEN_FAILED,
    SESSION_CREATE_FAILED,
    GENERATE_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    SERVER_CRASH,
    INTERNAL,
}

data class ServerError(
    val code: ServerErrorCode,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

sealed class ServerResult<out T> {
    data class Ok<T>(val value: T) : ServerResult<T>()
    data class Err(val error: ServerError) : ServerResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): ServerError? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): ServerResult<T> = Ok(value)
        fun err(error: ServerError): ServerResult<Nothing> = Err(error)
    }
}
