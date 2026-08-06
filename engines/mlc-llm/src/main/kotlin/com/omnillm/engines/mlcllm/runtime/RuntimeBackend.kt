package com.omnillm.engines.mlcllm.runtime

/**
 * MLC runtime / generated-module surface (ENGINE-MLC §2).
 *
 * Real implementation will bind the official Android runtime (MLCEngine) and
 * load verified generated model libraries. This interface keeps the Kotlin
 * adapter free of process-crossing pointers: only opaque [NativeModelToken] /
 * [NativeSessionToken] strings are returned to higher layers.
 *
 * **TODO(runtime):** implement `JniMlcRuntimeBackend` with:
 * - NDK r28+ 16 KB-aligned packaged libs
 * - generated module load from read-only FD / path broker
 * - stream callback → event bridge
 * - driver/worker crash mapping
 *
 * Adapters using this port must never write OmniLLM DB/model store.
 */
interface RuntimeBackend {
    /** Runtime library identity for diagnostics (not a trust elevation). */
    fun libraryLabel(): String

    fun isAvailable(): Boolean

    /**
     * Bounded probe: package/target/runtime compatibility.
     * Must not load untrusted generated modules in a privileged process.
     */
    fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome>

    /**
     * Load runtime + generated module after reservation + privileged re-verify ticket.
     */
    fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken>

    fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken>

    fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome>

    /**
     * Embedding / multimodal / structured — default UNSUPPORTED until cell qualified.
     */
    fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome>

    fun closeSession(session: NativeSessionToken): NativeResult<Unit>

    fun unloadModel(model: NativeModelToken): NativeResult<Unit>

    fun requestCancel(operationToken: String): NativeResult<Unit>
}

/** Opaque model handle — never a raw pointer on wire. */
@JvmInline
value class NativeModelToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "NativeModelToken must be non-empty" }
    }
}

/** Opaque session/chat handle — never a raw pointer on wire. */
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
    /** Unproven / unpinned capability (maps to CAPABILITY_UNKNOWN). */
    UNKNOWN_CAPABILITY,
    MODEL_OPEN_FAILED,
    MODULE_LOAD_FAILED,
    SESSION_CREATE_FAILED,
    TOKENIZE_FAILED,
    GENERATE_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INTERNAL,
    WORKER_CRASH,
    DRIVER_CRASH,
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
    val privilegedLoadTicketId: String,
    val generatedLibraryDigest: String = "",
    val runtimeArtifactDigest: String = "",
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty())
        require(installationKey.isNotEmpty())
        require(backend.isNotEmpty())
        require(privilegedLoadTicketId.isNotEmpty())
    }
}

data class NativeSessionRequest(
    val attributes: Map<String, String> = emptyMap(),
)

data class NativeGenerateRequest(
    val operationToken: String,
    val promptDigestHex: String,
    val maxTokens: Int,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val stopSequenceCount: Int = 0,
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

data class NativeStreamEvent(
    val kind: NativeStreamKind,
    val payloadDigestHex: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class NativeStreamKind {
    METADATA,
    TOKEN_DELTA,
    USAGE,
    DIAGNOSTIC,
    WARNING,
    STOP,
}
