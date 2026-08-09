package com.omnillm.engines.ortgenai.session

import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.GenAIException
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Sequences
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.TokenizerStream

/**
 * Direct binding to the official ONNX Runtime GenAI Java API
 * (Android AAR `onnxruntime-genai-android` 0.14.0, package
 * `ai.onnxruntime.genai`). API surface verified against the pinned AAR with
 * `javap` (see UPSTREAM.lock): `Model(path)`, `Generator(Model, GeneratorParams)`,
 * `generateNextToken()` (void — decode the delta via [TokenizerStream]),
 * `appendTokens(int[])`, `isDone()`, `tokenCount()`. There is **no** `OrtGenAI`
 * class and no `Model.load(path, sessionOptions)` in this version — the older
 * surface from earlier docs no longer exists (doc-vs-reality gap, Stage 4b).
 *
 * - Native loading happens lazily when the [Model] class is first touched
 *   (`GenAI.init()` → `System.loadLibrary`). On host JVM without the Android
 *   .so this throws `LinkageError`; [isAvailable] catches it and reports false —
 *   missing natives are never reported as success.
 * - `GenAIException` (checked) is mapped per phase to [GenAiRuntimeException].
 * - KV-cache lifetime: `Generator`/`GeneratorParams`/`Model` are
 *   `AutoCloseable`; release happens in `close()` (no separate KV-release API
 *   in the Java bindings).
 */
class OrtGenAiRuntime : GenAiRuntime {

    override fun isAvailable(): Boolean = NativeBoundary.isAvailable()

    override fun libraryLabel(): String = NativeBoundary.LIBRARY_LABEL

    override fun openModel(modelDir: String): GenAiNativeModel =
        NativeBoundary.openModel(modelDir)
}

/**
 * The only place in the pack that touches `ai.onnxruntime.genai.*` classes.
 * Everything here is guarded: native absence must fail closed, never throw
 * out of the backend, and never fake availability.
 */
internal object NativeBoundary {

    const val LIBRARY_LABEL: String = "onnxruntime-genai-android-0.14.0"

    private val availability: Boolean by lazy { probe() }

    fun isAvailable(): Boolean = availability

    private fun probe(): Boolean = try {
        // IMPORTANT: `Model::class.java` alone does NOT initialize the class.
        // Class.forName(..., initialize=true) triggers Model.<clinit> →
        // GenAI.init() → System.loadLibrary(libonnxruntime.so + genai .so).
        // On Android with both AARs packaged this succeeds; on host JVM it
        // throws (LinkageError / ExceptionInInitializerError) — fail closed.
        Class.forName(
            "ai.onnxruntime.genai.Model",
            true,
            NativeBoundary::class.java.classLoader,
        )
        true
    } catch (e: LinkageError) {
        false
    } catch (e: RuntimeException) {
        false
    } catch (e: Exception) {
        false
    }

    fun openModel(modelDir: String): GenAiNativeModel {
        if (!isAvailable()) {
            throw GenAiRuntimeException(
                GenAiErrorCode.NOT_AVAILABLE,
                "ONNX Runtime GenAI native libraries not loadable on this runtime",
            )
        }
        return try {
            val model = Model(modelDir)
            val tokenizer = Tokenizer(model)
            DirectNativeModel(model, tokenizer)
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.MODEL_OPEN_FAILED, e.message)
        } catch (e: LinkageError) {
            throw GenAiRuntimeException(GenAiErrorCode.NOT_AVAILABLE, e.message)
        } catch (e: IllegalArgumentException) {
            throw GenAiRuntimeException(GenAiErrorCode.INVALID_ARGUMENT, e.message)
        }
    }

    private class DirectNativeModel(
        private val model: Model,
        private val tokenizer: Tokenizer,
    ) : GenAiNativeModel {

        override fun label(): String = LIBRARY_LABEL

        override fun encode(prompt: String): IntArray = try {
            val seq: Sequences = tokenizer.encode(prompt)
            seq.getSequence(0)
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.TOKENIZE_FAILED, e.message)
        }

        override fun createParams(): GenAiNativeParams = try {
            DirectNativeParams(model, tokenizer, GeneratorParams(model))
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.SESSION_CREATE_FAILED, e.message)
        }

        override fun close() {
            try {
                tokenizer.close()
                model.close()
            } catch (_: Exception) {
                // Best-effort release; generator close already released KV.
            }
        }
    }

    private class DirectNativeParams(
        private val model: Model,
        private val tokenizer: Tokenizer,
        private val params: GeneratorParams,
    ) : GenAiNativeParams {

        override fun setSearchOption(name: String, value: Double) = try {
            params.setSearchOption(name, value)
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.UNSUPPORTED_PARAMETER, e.message)
        }

        override fun setSearchOption(name: String, value: Boolean) = try {
            params.setSearchOption(name, value)
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.UNSUPPORTED_PARAMETER, e.message)
        }

        override fun createGenerator(): GenAiNativeGenerator = try {
            DirectNativeGenerator(Generator(model, params), tokenizer.createStream())
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.GENERATE_FAILED, e.message)
        }

        override fun close() {
            try {
                params.close()
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    private class DirectNativeGenerator(
        private val generator: Generator,
        private val stream: TokenizerStream,
    ) : GenAiNativeGenerator {

        override fun isDone(): Boolean = generator.isDone()

        override fun generateNextToken() {
            try {
                generator.generateNextToken()
            } catch (e: GenAIException) {
                throw GenAiRuntimeException(GenAiErrorCode.GENERATE_FAILED, e.message)
            }
        }

        override fun decodeLastToken(): String = try {
            stream.decode(generator.getLastTokenInSequence(0))
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.GENERATE_FAILED, e.message)
        }

        override fun tokenCount(): Long = try {
            generator.tokenCount()
        } catch (e: GenAIException) {
            throw GenAiRuntimeException(GenAiErrorCode.GENERATE_FAILED, e.message)
        }

        override fun appendTokens(tokens: IntArray) {
            try {
                generator.appendTokens(tokens)
            } catch (e: GenAIException) {
                throw GenAiRuntimeException(GenAiErrorCode.TOKENIZE_FAILED, e.message)
            }
        }

        override fun close() {
            try {
                stream.close()
                generator.close()
            } catch (_: Exception) {
                // best-effort
            }
        }
    }
}
