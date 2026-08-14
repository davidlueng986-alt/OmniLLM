package com.omnillm.engines.ortgenai.session

import com.omnillm.core.canonical.IdentityHashing
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real ONNX Runtime GenAI backend (ENGINE-ORTGENAI §2–§6) over
 * [GenAiRuntime] (default [OrtGenAiRuntime] = official `ai.onnxruntime.genai`
 * Java API from the pinned Android AAR).
 *
 * - Model load from a control-plane-resolved ONNX GenAI folder
 *   (`genai_config.json` + `.onnx` + tokenizer files). The path is never a
 *   client-supplied raw path: [modelDirProvider] resolves the broker key
 *   (`storageRootKey` / `installationKey`) to app-private storage; default
 *   reads `attributes["modelDir"]`.
 * - Streaming generation: per-token `generateNextToken()` + stateful
 *   `TokenizerStream` decode, `max_length` derived from request, stop on
 *   EOS / max-tokens / cooperative cancel. Prompt text comes from
 *   `promptUtf8` or the staged-prompt registry keyed by prompt digest —
 *   never synthetic content (a digest is not a prompt).
 * - Cancellation is cooperative polling between tokens (the Java bindings
 *   expose no native cancel API); an in-flight generate is not preemptible —
 *   ENGINE-ORTGENAI §6 places the full lifecycle on a killable worker.
 * - Fail closed: natives absent → NOT_AVAILABLE everywhere; unproven execute
 *   (lock incomplete / exploratory off) → CAPABILITY_UNKNOWN. Missing natives
 *   are never reported as load success.
 * - Never writes OmniLLM DB / model store (ADR-010); opaque tokens only.
 */
class RealGenAiBackend(
    private val runtime: GenAiRuntime = OrtGenAiRuntime(),
    /**
     * Resolves the model folder for a load request. Default: `modelDir`
     * attribute (control plane supplies the app-private absolute path).
     * Production may inject a broker→path resolver here (Stage 5).
     */
    private val modelDirProvider: (GenAiLoadRequest) -> String? = { req ->
        req.attributes["modelDir"]
    },
    /**
     * Lock-completeness gate. When false (default), load/generate return
     * CAPABILITY_UNKNOWN even when natives are present, so AAR presence cannot
     * fake qualification. Mirrors the LiteRT-LM backend policy.
     */
    private val allowExploratoryExecute: Boolean = false,
) : GenAiBackend {

    private val models = ConcurrentHashMap<String, LoadedModel>()
    private val sessions = ConcurrentHashMap<String, ActiveSession>()

    /**
     * Bounded cooperative-cancel registry (D19): cap + FIFO eviction +
     * consume-on-completion — never unbounded (mirrors the native 1024 cap).
     */
    internal val cancelRegistry: BoundedCancelRegistry = BoundedCancelRegistry()
    private val stagedPrompts = ConcurrentHashMap<String, String>()
    private val modelSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)

    override fun libraryLabel(): String = runtime.libraryLabel()

    override fun isAvailable(): Boolean = runtime.isAvailable()

    override fun probe(request: GenAiProbeRequest): GenAiResult<GenAiProbeOutcome> {
        if (!runtime.isAvailable()) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.NOT_AVAILABLE,
                    message = "ONNX Runtime GenAI native libraries not loadable; " +
                        "genai AAR + base onnxruntime AAR must be packaged on device",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        return GenAiResult.ok(
            GenAiProbeOutcome(
                backend = request.backend,
                available = true,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "true",
                    "qualification" to "UNKNOWN",
                    "allowExploratoryExecute" to allowExploratoryExecute.toString(),
                ),
            ),
        )
    }

    override fun loadModel(request: GenAiLoadRequest): GenAiResult<GenAiModelToken> {
        if (!runtime.isAvailable()) {
            return notAvailable("LOAD", request.backend)
        }
        if (!allowExploratoryExecute) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.UNKNOWN_CAPABILITY,
                    message = "ONNX Runtime GenAI LOAD not allowed until UPSTREAM.lock " +
                        "completeness + exploratory policy enable execute; " +
                        "AAR presence ≠ SUPPORTED",
                    attributes = mapOf(
                        "operation" to "LOAD",
                        "backend" to request.backend,
                        "installationKey" to request.installationKey,
                    ),
                ),
            )
        }

        val modelDir = modelDirProvider(request)
            ?: return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.MODEL_OPEN_FAILED,
                    message = "modelDir resolution failed: broker/attributes must supply " +
                        "the ONNX GenAI model folder (app-private path); " +
                        "refusing client-supplied raw paths",
                    attributes = mapOf("backend" to request.backend),
                ),
            )

        // ONNX GenAI model package sanity (ENGINE-ORTGENAI §2): genai config is
        // mandatory; the runtime rejects folders without a complete package.
        if (!File(modelDir, GENAI_CONFIG_FILE).isFile) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.MODEL_OPEN_FAILED,
                    message = "ONNX GenAI model folder must contain $GENAI_CONFIG_FILE " +
                        "plus model .onnx and tokenizer files",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }

        val native = try {
            runtime.openModel(modelDir)
        } catch (e: GenAiRuntimeException) {
            return GenAiResult.err(GenAiError(code = e.code, message = e.message))
        } catch (e: Exception) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INTERNAL,
                    message = "unexpected model open failure: ${e::class.simpleName}",
                ),
            )
        }

        val id = "ort-genai-model-${modelSeq.incrementAndGet()}"
        models[id] = LoadedModel(
            backend = request.backend,
            installationKey = request.installationKey,
            native = native,
        )
        return GenAiResult.ok(GenAiModelToken(id))
    }

    override fun createSession(
        model: GenAiModelToken,
        request: GenAiSessionRequest,
    ): GenAiResult<GenAiSessionToken> {
        if (!runtime.isAvailable()) {
            return notAvailable("CREATE_SESSION", "n/a")
        }
        val loaded = models[model.value]
            ?: return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        val params = try {
            loaded.native.createParams()
        } catch (e: GenAiRuntimeException) {
            return GenAiResult.err(GenAiError(code = e.code, message = e.message))
        }
        val id = "ort-genai-session-${sessionSeq.incrementAndGet()}"
        sessions[id] = ActiveSession(model.value, params)
        return GenAiResult.ok(GenAiSessionToken(id))
    }

    override fun generate(
        session: GenAiSessionToken,
        request: GenAiGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (GenAiStreamEvent) -> Unit,
    ): GenAiResult<GenAiGenerateOutcome> {
        if (!runtime.isAvailable()) {
            return notAvailable("GENERATE", "n/a")
        }
        if (!allowExploratoryExecute) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.UNKNOWN_CAPABILITY,
                    message = "ONNX Runtime GenAI GENERATE not allowed without exploratory " +
                        "policy + complete lock; capability remains UNKNOWN",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )
        }
        val active = sessions[session.value]
            ?: return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )

        val promptText = request.promptUtf8?.takeIf { it.isNotBlank() }
            ?: stagedPrompts[request.promptDigestHex]
            ?: return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "prompt content required for real generation: provide " +
                        "promptUtf8 or stage the prompt under its digest (digest alone " +
                        "is not a prompt — ENGINE-ORTGENAI §4)",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )

        val loaded = models[active.modelToken]
            ?: return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "model token no longer loaded",
                ),
            )

        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.METADATA,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "backend" to loaded.backend,
                ),
            ),
        )

        val promptTokens = try {
            loaded.native.encode(promptText).size
        } catch (e: GenAiRuntimeException) {
            return GenAiResult.err(GenAiError(code = e.code, message = e.message))
        }

        var generator: GenAiNativeGenerator? = null
        var emitted = 0
        var stopReason = MAX_TOKENS
        try {
            active.params.setSearchOption(
                "max_length",
                (promptTokens + request.maxTokens).toDouble(),
            )
            request.temperature?.let { active.params.setSearchOption("temperature", it.toDouble()) }
            request.topP?.let { active.params.setSearchOption("top_p", it.toDouble()) }
            request.topK?.let { active.params.setSearchOption("top_k", it.toDouble()) }

            val tokens = loaded.native.encode(promptText)
            generator = active.params.createGenerator()
            generator.appendTokens(tokens)

            while (!generator.isDone() && emitted < request.maxTokens) {
                if (cancelFlag() || cancelRegistry.contains(request.operationToken)) {
                    stopReason = CANCELLED
                    break
                }
                generator.generateNextToken()
                val piece = generator.decodeLastToken()
                onEvent(
                    GenAiStreamEvent(
                        kind = GenAiStreamKind.TOKEN_DELTA,
                        payloadDigestHex = IdentityHashing.sha256Hex(piece),
                        attributes = mapOf("index" to emitted.toString()),
                    ),
                )
                emitted++
            }
            if (stopReason != CANCELLED && emitted >= request.maxTokens) {
                stopReason = MAX_TOKENS
            } else if (stopReason != CANCELLED) {
                stopReason = EOS
            }
        } catch (e: GenAiRuntimeException) {
            emitUsage(onEvent, promptTokens, emitted, stopReason)
            onEvent(
                GenAiStreamEvent(
                    kind = GenAiStreamKind.STOP,
                    attributes = mapOf("stopReason" to "ERROR:${e.code.name}"),
                ),
            )
            return GenAiResult.err(GenAiError(code = e.code, message = e.message))
        } finally {
            runCatching { generator?.close() }
            // D19: a completed operation consumes its cancel token — the
            // registry never grows without bound.
            cancelRegistry.consume(request.operationToken)
        }

        val completionTokens = runCatching {
            generator?.tokenCount()?.let { (it - promptTokens).toInt() } ?: emitted
        }.getOrDefault(emitted).coerceAtLeast(emitted)

        emitUsage(onEvent, promptTokens, completionTokens, stopReason)
        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.STOP,
                attributes = mapOf("stopReason" to stopReason),
            ),
        )

        if (stopReason == CANCELLED) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.CANCELLED,
                    message = "generation cancelled",
                    attributes = mapOf(
                        "operationToken" to request.operationToken,
                        "completionTokens" to completionTokens.toString(),
                    ),
                ),
            )
        }
        return GenAiResult.ok(
            GenAiGenerateOutcome(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                stopReason = stopReason,
                attributes = mapOf("library" to libraryLabel()),
            ),
        )
    }

    override fun embed(
        model: GenAiModelToken,
        request: GenAiEmbedRequest,
    ): GenAiResult<GenAiEmbedOutcome> {
        // Embedding remains unproven (ENGINE-ORTGENAI §10): no qualified
        // embedding cell, and the Java API has no embedding surface to call.
        return GenAiResult.err(
            GenAiError(
                code = GenAiErrorCode.UNKNOWN_CAPABILITY,
                message = "ONNX Runtime GenAI embedding not qualified for this build " +
                    "(ENGINE-ORTGENAI §10)",
                attributes = mapOf(
                    "operation" to "EMBED",
                    "operationToken" to request.operationToken,
                ),
            ),
        )
    }

    override fun closeSession(session: GenAiSessionToken): GenAiResult<Unit> {
        val active = sessions.remove(session.value) ?: return GenAiResult.ok(Unit)
        runCatching { active.params.close() }
        return GenAiResult.ok(Unit)
    }

    override fun unloadModel(model: GenAiModelToken): GenAiResult<Unit> {
        sessions.entries
            .filter { it.value.modelToken == model.value }
            .toList()
            .forEach { (sid, active) ->
                sessions.remove(sid)?.let { runCatching { active.params.close() } }
            }
        val loaded = models.remove(model.value) ?: return GenAiResult.ok(Unit)
        runCatching { loaded.native.close() }
        return GenAiResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): GenAiResult<Unit> {
        // Cooperative poll: the generate loop checks the registry between tokens.
        // The Java bindings expose no native cancel; ENGINE-ORTGENAI §6 requires
        // a killable worker for bounded preemption.
        cancelRegistry.add(operationToken)
        return GenAiResult.ok(Unit)
    }

    /**
     * Stage prompt content under its content digest so `generate` can be
     * driven digest-only by the control plane (privacy: no raw prompt on the
     * engine SPI). Control-plane wiring point (Stage 5); also used by tests.
     */
    fun stagePrompt(promptDigestHex: String, promptUtf8: String) {
        require(promptDigestHex.isNotEmpty())
        require(promptUtf8.isNotEmpty())
        stagedPrompts[promptDigestHex] = promptUtf8
    }

    /** Remove a staged prompt (e.g. after request completion / revocation). */
    fun clearStagedPrompt(promptDigestHex: String) {
        stagedPrompts.remove(promptDigestHex)
    }

    private fun emitUsage(
        onEvent: (GenAiStreamEvent) -> Unit,
        promptTokens: Int,
        completionTokens: Int,
        stopReason: String,
    ) {
        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to promptTokens.toString(),
                    "completionTokens" to completionTokens.toString(),
                    "stopReason" to stopReason,
                ),
            ),
        )
    }

    private fun notAvailable(operation: String, backend: String): GenAiResult<Nothing> =
        GenAiResult.err(
            GenAiError(
                code = GenAiErrorCode.NOT_AVAILABLE,
                message = "ONNX Runtime GenAI $operation unavailable: native libraries " +
                    "not loadable (genai AAR + base onnxruntime AAR must be packaged)",
                attributes = mapOf(
                    "operation" to operation,
                    "backend" to backend,
                ),
            ),
        )

    private data class LoadedModel(
        val backend: String,
        val installationKey: String,
        val native: GenAiNativeModel,
    )

    private data class ActiveSession(
        val modelToken: String,
        val params: GenAiNativeParams,
    )

    companion object {
        const val GENAI_CONFIG_FILE: String = "genai_config.json"
        const val EOS: String = "EOS"
        const val MAX_TOKENS: String = "MAX_TOKENS"
        const val CANCELLED: String = "CANCELLED"
    }
}
