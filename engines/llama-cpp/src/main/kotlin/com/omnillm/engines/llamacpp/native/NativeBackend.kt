package com.omnillm.engines.llamacpp.native

/**
 * JNI / C API surface for llama.cpp (ENGINE-LLAMACPP §2).
 *
 * Production: [JniNativeBackend] loads verified packaged `libomnillm_llama.so`
 * (ANDROID-NATIVE) via [JniNativeBridge]. Tests / NDK-less hosts: [StubNativeBackend].
 *
 * Opaque [NativeModelToken] / [NativeSessionToken] strings only — never raw pointers
 * on wire. Adapters using this port must never write OmniLLM DB/model store.
 *
 * Model load uses read-only FD / path broker keys (never catalog writable paths).
 * Cooperative cancel is polled during generate. Missing native lib ⇒ fail closed
 * on the control plane (do not silently substitute Stub in production).
 */
interface NativeBackend {
    /** Backend library identity for diagnostics (not a trust elevation). */
    fun libraryLabel(): String

    fun isAvailable(): Boolean

    /**
     * Bounded probe: backend presence / thread sanity. Must not load untrusted models
     * in a privileged process.
     */
    fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome>

    /**
     * Load model weights into an opaque native model token after reservation +
     * privileged re-verify ticket (ticket is opaque to native).
     */
    fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken>

    fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken>

    /**
     * Prefill / decode step. May emit multiple [NativeStreamEvent]s via [onEvent].
     * Cooperative cancel is polled via [cancelFlag].
     */
    fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome>

    /**
     * Embedding forward pass. Returns [NativeResult.Err] with UNSUPPORTED until
     * pooling config is qualified for this build.
     */
    fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome>

    fun closeSession(session: NativeSessionToken): NativeResult<Unit>

    fun unloadModel(model: NativeModelToken): NativeResult<Unit>

    /** Request cooperative cancel on an in-flight generate (best-effort). */
    fun requestCancel(operationToken: String): NativeResult<Unit>
}

/** Opaque model handle — never a raw pointer on wire. */
@JvmInline
value class NativeModelToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "NativeModelToken must be non-empty" }
    }
}

/** Opaque session/context handle — never a raw pointer on wire. */
@JvmInline
value class NativeSessionToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "NativeSessionToken must be non-empty" }
    }
}

sealed class NativeResult<out T> {
    data class Ok<T>(val value: T) : NativeResult<T>()
    data class Err(val error: NativeError) : NativeResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): NativeError? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): NativeResult<T> = Ok(value)
        fun err(error: NativeError): NativeResult<Nothing> = Err(error)
    }
}

/**
 * Structured native error — mapped to catalog [com.omnillm.core.errors.generated.OmniError]
 * by [com.omnillm.engines.llamacpp.mapping.ErrorMapper]. No raw paths/pointers.
 */
data class NativeError(
    val code: NativeErrorCode,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class NativeErrorCode {
    NOT_AVAILABLE,
    INVALID_ARGUMENT,
    UNSUPPORTED_PARAMETER,
    UNSUPPORTED_OPERATION,
    MODEL_OPEN_FAILED,
    CONTEXT_CREATE_FAILED,
    TOKENIZE_FAILED,
    GENERATE_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INTERNAL,
    WORKER_CRASH,
}

data class NativeProbeRequest(
    val backend: String,
    val operationToken: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class NativeProbeOutcome(
    val backend: String,
    val available: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)

data class NativeLoadRequest(
    /** Opaque storage key / FD broker id — never a client-supplied absolute path. */
    val storageRootKey: String,
    val installationKey: String,
    val backend: String,
    val nCtx: Int,
    val nThreads: Int,
    val privilegedLoadTicketId: String,
    /**
     * Runtime-resolved read-only model path after privileged re-verify
     * (INV-010). Use [JniNativeMapping.EXPERIMENTAL_FIXTURE] / `fixture:` prefix
     * for the deterministic C++ fixture path.
     * Null with [modelFd] &lt; 0 and non-fixture [installationKey]/[storageRootKey]
     * ⇒ fail closed (native NOT_AVAILABLE) — no silent fixture.
     */
    val resolvedModelPath: String? = null,
    /**
     * Read-only FD for GGUF bytes (-1 unused). Preferred over path when the
     * control plane brokers an already-verified open FD.
     */
    val modelFd: Int = -1,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty())
        require(installationKey.isNotEmpty())
        require(backend.isNotEmpty())
        require(nCtx > 0) { "nCtx must be positive" }
        require(nThreads > 0) { "nThreads must be positive" }
        require(privilegedLoadTicketId.isNotEmpty())
    }
}

data class NativeSessionRequest(
    val nCtx: Int,
    val seed: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(nCtx > 0)
    }
}

data class NativeGenerateRequest(
    val operationToken: String,
    val promptDigestHex: String,
    val maxTokens: Int,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val stopSequenceCount: Int = 0,
    /**
     * Optional UTF-8 prompt body for native tokenize. When null/empty, native
     * uses [promptDigestHex] as synthetic tokenize input (fixture / exploratory).
     */
    val promptUtf8: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(operationToken.isNotEmpty())
        require(promptDigestHex.isNotEmpty())
        require(maxTokens > 0)
    }
}

data class NativeGenerateOutcome(
    val promptTokens: Int,
    val completionTokens: Int,
    val stopReason: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class NativeEmbedRequest(
    val operationToken: String,
    val inputDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class NativeEmbedOutcome(
    val dimensions: Int,
    val normalized: Boolean,
    val vectorDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Low-level stream events from native decode. Normalized to catalog
 * [com.omnillm.engines.api.EngineEvent] by EventNormalizer.
 */
data class NativeStreamEvent(
    val kind: NativeStreamKind,
    /** Optional opaque payload digest (token batch / log blob) — no plaintext required. */
    val payloadDigestHex: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class NativeStreamKind {
    METADATA,
    TOKEN_DELTA,
    USAGE,
    DIAGNOSTIC,
    WARNING,
    /** Native may emit STOP; normalizer maps to unique terminal. */
    STOP,
}
