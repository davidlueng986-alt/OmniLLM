package com.omnillm.engines.ortgenai.session

/**
 * Thin seam over the ONNX Runtime GenAI Java API (`ai.onnxruntime.genai.*`,
 * Android AAR v0.14.0) used by [RealGenAiBackend] (ENGINE-ORTGENAI §2–§6).
 *
 * ORT GenAI is JVM-accessible directly — no OmniLLM JNI bridge is needed; the
 * Java bindings in the AAR talk to `libonnxruntime-genai-jni.so` themselves.
 * The seam exists so host JVM unit tests can exercise the backend orchestration
 * (token loop, stop conditions, cancellation, event emission, resource cleanup)
 * with a fake, while production always uses [OrtGenAiRuntime].
 *
 * Native-boundary contract:
 * - Every method may throw [GenAiRuntimeException] (already mapped from
 *   `GenAIException` / `LinkageError` / `IllegalArgumentException`).
 * - Instances are NOT thread-safe; one generator per generate call.
 * - close() releases native state (Generator/Params/Model close → KV cache freed).
 */
interface GenAiRuntime {
    /** True only when the GenAI native libraries actually loaded (Android AAR present). */
    fun isAvailable(): Boolean

    /** Runtime / library identity for diagnostics (not a trust elevation). */
    fun libraryLabel(): String

    /**
     * Open an ONNX GenAI model folder (genai_config.json + .onnx + tokenizer).
     * [modelDir] is a control-plane-resolved path (app-private storage on Android).
     */
    fun openModel(modelDir: String): GenAiNativeModel
}

/** Wrapped `Model` + `Tokenizer` + `GeneratorParams` factory. */
interface GenAiNativeModel : AutoCloseable {
    /** Opaque label for diagnostics. */
    fun label(): String

    /** Encode prompt text to token ids (`Tokenizer.encode`). */
    fun encode(prompt: String): IntArray

    /** Create model-bound generator parameters (config defaults from genai_config.json). */
    fun createParams(): GenAiNativeParams

    override fun close()
}

/** Wrapped `GeneratorParams`. */
interface GenAiNativeParams : AutoCloseable {
    fun setSearchOption(name: String, value: Double)

    fun setSearchOption(name: String, value: Boolean)

    /** Create the generator (KV cache allocated from max_length at this point). */
    fun createGenerator(): GenAiNativeGenerator

    override fun close()
}

/**
 * Wrapped `Generator` + `TokenizerStream`.
 * Streaming: one token per [generateNextToken]; [decodeLastToken] decodes the
 * delta through the stateful stream.
 */
interface GenAiNativeGenerator : AutoCloseable {
    fun isDone(): Boolean

    fun generateNextToken()

    fun decodeLastToken(): String

    /** Total tokens in the sequence (prompt + generated), when available. */
    fun tokenCount(): Long

    fun appendTokens(tokens: IntArray)

    override fun close()
}

/**
 * Mapped native error. [code] reuses [GenAiErrorCode] so [RealGenAiBackend]
 * forwards directly; [message] is sanitized (no raw paths/pointers).
 */
class GenAiRuntimeException(
    val code: GenAiErrorCode,
    message: String?,
) : RuntimeException(message)
