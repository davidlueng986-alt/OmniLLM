package com.omnillm.engines.ortgenai.session

/**
 * ONNX Runtime GenAI session adapter surface (ENGINE-ORTGENAI §2–§5).
 *
 * Real implementation binds GenAI C/C++ / JNI APIs:
 * - Model + Config (package: ONNX + external data + genai_config + tokenizer)
 * - Generator / GeneratorParams
 * - Sequences / multi-turn state
 * - Execution providers (CPU / NNAPI / QNN / …)
 *
 * Tokens returned here are **opaque strings** — never pass live ORT/GenAI
 * objects, native pointers, or FDs across AIDL / process boundaries.
 *
 * **TODO(native):** implement `JniGenAiBackend` with:
 * - pinned ORT + GenAI + provider library digests from UPSTREAM.lock
 * - 16 KB page-size packaging under `:android:native`
 * - provider init / graph opt / compile-cache as LOAD-phase work
 * - cooperative cancel measurement per EP
 * - stream token deltas → [GenAiStreamEvent]
 *
 * Adapters using this port must never write OmniLLM DB / model store (ADR-010).
 */
interface GenAiBackend {
    /** Runtime / library identity for diagnostics (not a trust elevation). */
    fun libraryLabel(): String

    fun isAvailable(): Boolean

    /**
     * Bounded probe: package / provider presence.
     * Must not load untrusted models in a privileged process.
     */
    fun probe(request: GenAiProbeRequest): GenAiResult<GenAiProbeOutcome>

    /**
     * Load model + generator parameters after reservation + privileged
     * re-verify ticket. Includes provider init / graph opt / compile-cache
     * when the EP requires them (LOAD phase).
     */
    fun loadModel(request: GenAiLoadRequest): GenAiResult<GenAiModelToken>

    /**
     * Create generator / sequence session state for plan/commit.
     * Opaque [GenAiSessionToken] only — never a raw generator pointer on wire.
     */
    fun createSession(
        model: GenAiModelToken,
        request: GenAiSessionRequest,
    ): GenAiResult<GenAiSessionToken>

    /**
     * Token generation drive. May emit multiple [GenAiStreamEvent]s via [onEvent].
     * Cooperative cancel is polled via [cancelFlag]; EP without bounded cancel
     * must run on a killable worker (ENGINE-ORTGENAI §6).
     */
    fun generate(
        session: GenAiSessionToken,
        request: GenAiGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (GenAiStreamEvent) -> Unit,
    ): GenAiResult<GenAiGenerateOutcome>

    /**
     * Embedding path. Default CAPABILITY_UNKNOWN until API + model cell
     * evidence exists (ENGINE-ORTGENAI §4 / §10).
     */
    fun embed(
        model: GenAiModelToken,
        request: GenAiEmbedRequest,
    ): GenAiResult<GenAiEmbedOutcome>

    fun closeSession(session: GenAiSessionToken): GenAiResult<Unit>

    fun unloadModel(model: GenAiModelToken): GenAiResult<Unit>

    /** Request cooperative cancel on an in-flight generate (best-effort). */
    fun requestCancel(operationToken: String): GenAiResult<Unit>
}

/** Opaque model / generator-params handle — never a raw pointer on wire. */
@JvmInline
value class GenAiModelToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "GenAiModelToken must be non-empty" }
    }
}

/** Opaque session / sequence / generator handle — never a raw pointer on wire. */
@JvmInline
value class GenAiSessionToken(val value: String) {
    init {
        require(value.isNotEmpty()) { "GenAiSessionToken must be non-empty" }
    }
}

sealed class GenAiResult<out T> {
    data class Ok<T>(val value: T) : GenAiResult<T>()
    data class Err(val error: GenAiError) : GenAiResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): GenAiError? = (this as? Err)?.error

    companion object {
        fun <T> ok(value: T): GenAiResult<T> = Ok(value)
        fun err(error: GenAiError): GenAiResult<Nothing> = Err(error)
    }
}

/**
 * Structured GenAI / ORT error — mapped to catalog
 * [com.omnillm.core.errors.generated.OmniError] by ErrorMapper.
 * No raw paths / pointers / EP object dumps.
 */
data class GenAiError(
    val code: GenAiErrorCode,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class GenAiErrorCode {
    NOT_AVAILABLE,
    INVALID_ARGUMENT,
    UNSUPPORTED_PARAMETER,
    UNSUPPORTED_OPERATION,
    /** Unproven / unpinned capability (maps to CAPABILITY_UNKNOWN). */
    UNKNOWN_CAPABILITY,
    MODEL_OPEN_FAILED,
    PROVIDER_INIT_FAILED,
    GRAPH_OPT_FAILED,
    COMPILE_CACHE_FAILED,
    CONFIG_PARSE_FAILED,
    SESSION_CREATE_FAILED,
    TOKENIZE_FAILED,
    GENERATE_FAILED,
    CANCELLED,
    DEADLINE_EXCEEDED,
    RESOURCE_EXHAUSTED,
    INTERNAL,
    WORKER_CRASH,
    PROVIDER_CRASH,
}

data class GenAiProbeRequest(
    val backend: String,
    val operationToken: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class GenAiProbeOutcome(
    val backend: String,
    val available: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)

data class GenAiLoadRequest(
    /** Opaque storage key / FD broker id — never a client-supplied absolute path. */
    val storageRootKey: String,
    val installationKey: String,
    val backend: String,
    val privilegedLoadTicketId: String,
    /** Optional digests from UPSTREAM.lock (empty until locked). */
    val genAiArtifactDigest: String = "",
    val ortRuntimeDigest: String = "",
    val providerLibraryDigest: String = "",
    val configSchemaDigest: String = "",
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty())
        require(installationKey.isNotEmpty())
        require(backend.isNotEmpty())
        require(privilegedLoadTicketId.isNotEmpty())
    }
}

data class GenAiSessionRequest(
    val attributes: Map<String, String> = emptyMap(),
)

data class GenAiGenerateRequest(
    val operationToken: String,
    val promptDigestHex: String,
    val maxTokens: Int,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    /**
     * Optional UTF-8 prompt body for real tokenization (ENGINE-ORTGENAI §4).
     * When null/empty, [RealGenAiBackend] falls back to the staged-prompt
     * registry keyed by [promptDigestHex]; missing content fails closed —
     * no synthetic tokenize input (a digest is not a prompt).
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

data class GenAiGenerateOutcome(
    val promptTokens: Int,
    val completionTokens: Int,
    val stopReason: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class GenAiEmbedRequest(
    val operationToken: String,
    val inputDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class GenAiEmbedOutcome(
    val dimensions: Int,
    val normalized: Boolean,
    val vectorDigestHex: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Low-level stream events from GenAI callbacks. Normalized to catalog
 * [com.omnillm.engines.api.EngineEvent] by EventNormalizer.
 */
data class GenAiStreamEvent(
    val kind: GenAiStreamKind,
    val payloadDigestHex: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

enum class GenAiStreamKind {
    METADATA,
    TOKEN_DELTA,
    USAGE,
    DIAGNOSTIC,
    WARNING,
    /** Upstream stop; normalizer maps to unique terminal. */
    STOP,
}
