package com.omnillm.engines.litertlm.sdk

/**
 * Official LiteRT-LM SDK / AAR surface (ENGINE-LITERT §2).
 *
 * Prefer documented Engine / Conversation APIs. Do **not** assume traditional
 * JNI exposes every handle. Tokens returned here are opaque strings — never
 * pass live SDK objects across AIDL / process boundaries.
 *
 * Implementations:
 * - [StubSdkBackend] — host unit tests only (never silent production fallback)
 * - [RealSdkBackend] — optional AAR via [LitertLmSdkBridge] / reflection; fail closed
 *   when missing; exploratory execute policy-gated; never marks SUPPORTED
 *
 * Pin Maven coordinate + AAR digest in `UPSTREAM.lock`. Stream cancel that only
 * stops future output ≠ native execution stopped (ENGINE-LITERT §6).
 *
 * Adapters using this port must never write OmniLLM DB / model store.
 */
interface SdkBackend {
    /** SDK identity for diagnostics (not a trust elevation). */
    fun libraryLabel(): String

    fun isAvailable(): Boolean

    /**
     * Bounded probe: SDK / backend presence. Must not load untrusted models
     * in a privileged process.
     */
    fun probe(request: SdkProbeRequest): SdkResult<SdkProbeOutcome>

    /**
     * Load / open model into an opaque engine token after reservation +
     * privileged re-verify ticket (ticket is opaque to the SDK layer).
     */
    fun loadEngine(request: SdkLoadRequest): SdkResult<SdkEngineToken>

    /** Create Conversation / session object → opaque conversation token. */
    fun createConversation(
        engine: SdkEngineToken,
        request: SdkConversationRequest,
    ): SdkResult<SdkConversationToken>

    /**
     * Prefill / generate. May emit multiple [SdkStreamEvent]s via [onEvent].
     * Cooperative cancel is polled via [cancelFlag]; stopping future output
     * alone is not proof that native execution stopped (ENGINE-LITERT §6).
     */
    fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome>

    /**
     * Embedding path. Returns [SdkResult.Err] with UNSUPPORTED until the
     * SDK version + model cell is qualified (ENGINE-LITERT §10).
     */
    fun embed(
        engine: SdkEngineToken,
        request: SdkEmbedRequest,
    ): SdkResult<SdkEmbedOutcome>

    fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit>

    fun unloadEngine(engine: SdkEngineToken): SdkResult<Unit>

    /** Request cooperative cancel on an in-flight generate (best-effort). */
    fun requestCancel(operationToken: String): SdkResult<Unit>
}

/** Opaque Engine handle — never a raw SDK object on wire. */
@JvmInline
value class SdkEngineToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "SdkEngineToken must be non-empty" }
    }
}

/** Opaque Conversation / session handle — never a raw SDK object on wire. */
@JvmInline
value class SdkConversationToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "SdkConversationToken must be non-empty" }
    }
}

sealed class SdkResult<out T> {
    data class Ok<T>(val value: T) : SdkResult<T>()
    data class Err(val error: SdkError) : SdkResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): SdkError? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): SdkResult<T> = Ok(value)
        fun err(error: SdkError): SdkResult<Nothing> = Err(error)
    }
}

/**
 * Structured SDK error — mapped to catalog
 * [com.omnillm.core.errors.generated.OmniError] by ErrorMapper.
 * No raw paths / pointers / SDK object dumps.
 */
data class SdkError(
    val code: SdkErrorCode,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class SdkErrorCode {
    NOT_AVAILABLE,
    INVALID_ARGUMENT,
    UNSUPPORTED_PARAMETER,
    UNSUPPORTED_OPERATION,
    MODEL_OPEN_FAILED,
    COMPILE_FAILED,
    CONVERSATION_CREATE_FAILED,
    TOKENIZE_FAILED,
    GENERATE_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INTERNAL,
    WORKER_CRASH,
    /** Operation or backend not proven for this EngineBuildId / cell. */
    CAPABILITY_UNKNOWN,
}

data class SdkProbeRequest(
    val backend: String,
    val operationToken: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class SdkProbeOutcome(
    val backend: String,
    val available: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)

data class SdkLoadRequest(
    /** Opaque storage key / FD broker id — never a client-supplied absolute path. */
    val storageRootKey: String,
    val installationKey: String,
    val backend: String,
    val privilegedLoadTicketId: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty())
        require(installationKey.isNotEmpty())
        require(backend.isNotEmpty())
        require(privilegedLoadTicketId.isNotEmpty())
    }
}

data class SdkConversationRequest(
    val attributes: Map<String, String> = emptyMap(),
)

data class SdkGenerateRequest(
    val operationToken: String,
    val promptDigestHex: String,
    val maxTokens: Int,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(operationToken.isNotEmpty())
        require(promptDigestHex.isNotEmpty())
        require(maxTokens > 0)
    }
}

data class SdkGenerateOutcome(
    val promptTokens: Int,
    val completionTokens: Int,
    val stopReason: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class SdkEmbedRequest(
    val operationToken: String,
    val inputDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class SdkEmbedOutcome(
    val dimensions: Int,
    val normalized: Boolean,
    val vectorDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Low-level stream events from SDK callbacks. Normalized to catalog
 * [com.omnillm.engines.api.EngineEvent] by EventNormalizer.
 */
data class SdkStreamEvent(
    val kind: SdkStreamKind,
    val payloadDigestHex: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class SdkStreamKind {
    METADATA,
    TOKEN_DELTA,
    USAGE,
    DIAGNOSTIC,
    WARNING,
    /** SDK may emit STOP; normalizer maps to unique terminal. */
    STOP,
}
