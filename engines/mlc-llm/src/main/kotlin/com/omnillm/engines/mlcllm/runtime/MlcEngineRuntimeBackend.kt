package com.omnillm.engines.mlcllm.runtime

import com.omnillm.core.canonical.IdentityHashing
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production [RuntimeBackend] for MLC-LLM bound to the official generated
 * Android runtime (`ai.mlc.mlcllm.MLCEngine` from `mlc_llm package`, via
 * [MlcRuntimeBridge]).
 *
 * Honest constraints (verified 2026-08-09 against mlc-ai/mlc-llm pin):
 * - The Android runtime is **not** published as Maven/GitHub-release `.aar`;
 *   it is generated per-app (`dist/lib/mlc4j`: `libtvm4j_runtime_packed.so` +
 *   `tvm4j_core.jar` + Kotlin API). Without that generated module on the
 *   classpath, [createOrNull] returns null and the control plane must fail
 *   closed (no stub substitution).
 * - Real inference additionally requires a **compiled model bundle**
 *   (`mlc-chat-config.json` + weights + packaged model lib). Load is gated on
 *   non-empty [NativeLoadRequest.generatedLibraryDigest] /
 *   [NativeLoadRequest.runtimeArtifactDigest] (complete UPSTREAM.lock);
 *   unpinned artifacts are refused (ENGINE-MLC §1: `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK`).
 * - The MLC chat API is stateless per request: a "session" is a message
 *   journal kept adapter-side and replayed to `chat.completions.create`.
 * - Streaming is cooperative: cancellation is checked between deltas
 *   (capability matrix GENERATE = COOPERATIVE). The upstream Kotlin API does
 *   not expose abort; the background stream is left to close naturally.
 * - Stock mlc4j hardcodes `Device.opencl()`; CPU/Vulkan targets are not
 *   shipped by the generated runtime — no silent claims (ENGINE-MLC §3).
 * - Embedding stays CAPABILITY_UNKNOWN until per-cell qualification (§10).
 *
 * This backend is Android-free and host-testable; the bridge binds the pinned
 * API surface (test doubles live under `src/test/kotlin/ai/mlc/mlcllm/`).
 */
class MlcEngineRuntimeBackend private constructor(
    private val engineRef: MlcEngineRef,
) : RuntimeBackend {

    override fun libraryLabel(): String = engineRef.libraryLabel

    override fun isAvailable(): Boolean = true

    override fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome> {
        val attrs = mutableMapOf(
            "library" to libraryLabel(),
            "native" to "true",
            "abi" to ABI_ARM64,
            "operationToken" to request.operationToken,
            "deviceSelection" to MlcRuntimeBridge.STOCK_DEVICE,
            "page16kb" to "PENDING_MEASUREMENT",
        )
        val available = when (request.backend) {
            // Stock generated runtime initializes the OpenCL device (JSONFFIEngine).
            "opencl" -> true
            // CPU/Vulkan targets are not shipped by the stock mlc4j build — no
            // silent claims; qualification must come from a matching built runtime.
            "cpu", "vulkan" -> false
            else -> false
        }
        if (!available) {
            attrs["reason"] = "backend not shipped by stock mlc4j runtime (device=opencl)"
        }
        return NativeResult.ok(
            NativeProbeOutcome(
                backend = request.backend,
                available = available,
                attributes = attrs,
            ),
        )
    }

    override fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken> {
        val modelPath = request.attributes[ATTRIBUTE_RESOLVED_MODEL_PATH]
        val modelLib = request.attributes[ATTRIBUTE_MODEL_LIB]
        if (modelPath.isNullOrBlank() || modelLib.isNullOrBlank()) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "resolvedModelPath and modelLib attributes required " +
                        "(broker-resolved installation dir + packaged compiled library)",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        if (request.generatedLibraryDigest.isBlank() || request.runtimeArtifactDigest.isBlank()) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.MODULE_LOAD_FAILED,
                    message = "lock incomplete (NOT_LOCKED): generated library / runtime " +
                        "artifact digest not pinned — NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK",
                    attributes = mapOf(
                        "backend" to request.backend,
                        "modelLib" to modelLib,
                    ),
                ),
            )
        }

        val modelDir = File(modelPath)
        if (!modelDir.isDirectory || !File(modelDir, MLC_CHAT_CONFIG).isFile) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.MODEL_OPEN_FAILED,
                    message = "model bundle not found at broker-resolved installation " +
                        "(expected $MLC_CHAT_CONFIG + weights + compiled lib)",
                    attributes = mapOf(
                        "backend" to request.backend,
                        "modelLib" to modelLib,
                        "installKey" to request.installationKey,
                    ),
                ),
            )
        }

        try {
            engineRef.reload(modelDir.absolutePath, modelLib)
        } catch (e: MlcRuntimeException) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.MODULE_LOAD_FAILED,
                    message = sanitizeMessage(e.message),
                    attributes = mapOf(
                        "backend" to request.backend,
                        "modelLib" to modelLib,
                    ),
                ),
            )
        }

        val token = NativeModelToken("mlc-model-${modelSeq.incrementAndGet()}")
        models[token.value] = ModelState(
            request = request,
            modelLib = modelLib,
            modelPath = modelDir.absolutePath,
        )
        return NativeResult.ok(token)
    }

    override fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken> {
        val modelState = models[model.value]
            ?: return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        val token = NativeSessionToken("mlc-session-${sessionSeq.incrementAndGet()}")
        sessions[token.value] = SessionState(modelToken = model.value, modelLib = modelState.modelLib)
        return NativeResult.ok(token)
    }

    override fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome> {
        val sessionState = sessions[session.value]
            ?: return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        if (cancelledTokens.contains(request.operationToken) || cancelFlag()) {
            cancelledTokens.add(request.operationToken)
            onEvent(
                NativeStreamEvent(
                    kind = NativeStreamKind.STOP,
                    attributes = mapOf("stopReason" to "CANCELLED"),
                ),
            )
            return cancelled()
        }

        val prompt = request.promptUtf8?.takeIf { it.isNotBlank() }
            ?: return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "promptUtf8 required: MLC chat.completions needs the real " +
                        "prompt text, not only a digest",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )

        // Doc-vs-reality gap (ENGINE-MLC §4): the mobile mlc4j chat API
        // (`Completions.create`) does NOT expose top_k — do not silently drop it.
        request.topK?.takeIf { it > 0 }?.let {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.UNSUPPORTED_PARAMETER,
                    message = "topK is not exposed by the MLC mobile chat API " +
                        "(chat.completions.create has no top_k parameter)",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )
        }

        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.METADATA,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "modelLib" to sessionState.modelLib,
                    "api" to "chat.completions.create (stream)",
                ),
            ),
        )

        sessionState.journal.add(MlcMessageEntry("user", prompt))
        val messages = sessionState.journal.toList()
        val params = MlcSamplingParams(
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            topK = request.topK,
            stopSequences = request.stopSequences,
        )

        val deltas = StringBuilder()
        var deltaIndex = 0
        var cancelledByFlag = false

        val result = try {
            engineRef.streamChat(messages, params) { delta ->
                if (cancelFlag() || cancelledTokens.contains(request.operationToken)) {
                    cancelledTokens.add(request.operationToken)
                    cancelledByFlag = true
                    return@streamChat false
                }
                deltas.append(delta)
                onEvent(
                    NativeStreamEvent(
                        kind = NativeStreamKind.TOKEN_DELTA,
                        payloadDigestHex = IdentityHashing.sha256Hex(delta),
                        attributes = mapOf("index" to deltaIndex.toString()),
                    ),
                )
                deltaIndex++
                true
            }
        } catch (e: MlcRuntimeException) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.GENERATE_FAILED,
                    message = sanitizeMessage(e.message),
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )
        }

        if (cancelledByFlag) {
            onEvent(
                NativeStreamEvent(
                    kind = NativeStreamKind.STOP,
                    attributes = mapOf("stopReason" to "CANCELLED"),
                ),
            )
            return cancelled()
        }

        result.promptTokens?.let { promptTokens ->
            result.completionTokens?.let { completionTokens ->
                onEvent(
                    NativeStreamEvent(
                        kind = NativeStreamKind.USAGE,
                        attributes = mapOf(
                            "promptTokens" to promptTokens.toString(),
                            "completionTokens" to completionTokens.toString(),
                        ),
                    ),
                )
            }
        }

        val stopReason = result.finishReason?.takeIf { it.isNotBlank() } ?: "COMPLETED"
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.STOP,
                attributes = mapOf("stopReason" to stopReason),
            ),
        )
        if (deltas.isNotEmpty()) {
            sessionState.journal.add(MlcMessageEntry("assistant", deltas.toString()))
        }
        return NativeResult.ok(
            NativeGenerateOutcome(
                promptTokens = result.promptTokens ?: 0,
                completionTokens = result.completionTokens ?: 0,
                stopReason = stopReason,
                attributes = mapOf("operationToken" to request.operationToken),
            ),
        )
    }

    override fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome> {
        // Embedding always unproven in this build (ENGINE-MLC §10) — per-cell only.
        return NativeResult.err(
            NativeError(
                code = NativeErrorCode.UNKNOWN_CAPABILITY,
                message = "MLC-LLM embed not proven for this build " +
                    "(UNQUALIFIED; per-model/API cell evidence required)",
                attributes = mapOf(
                    "operation" to "EMBED",
                    "engineId" to "MLC-LLM",
                ),
            ),
        )
    }

    override fun closeSession(session: NativeSessionToken): NativeResult<Unit> {
        sessions.remove(session.value)
        return NativeResult.ok(Unit)
    }

    override fun unloadModel(model: NativeModelToken): NativeResult<Unit> {
        sessions.entries
            .filter { it.value.modelToken == model.value }
            .forEach { sessions.remove(it.key) }
        models.remove(model.value)?.let { engineRef.unload() }
        return NativeResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): NativeResult<Unit> {
        if (operationToken.isNotEmpty()) {
            cancelledTokens.add(operationToken)
        }
        return NativeResult.ok(Unit)
    }

    private fun cancelled(): NativeResult<Nothing> = NativeResult.err(
        NativeError(
            code = NativeErrorCode.CANCELLED,
            message = "MLC-LLM generate cancelled (cooperative)",
        ),
    )

    /** Strip path-like tokens from upstream messages (never leak filesystem paths). */
    private fun sanitizeMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.split(Regex("\\s+"))
            .filterNot { it.startsWith("/") || it.contains(":\\") || it.startsWith("\\") }
            .joinToString(" ")
        return cleaned.take(512)
    }

    private data class ModelState(
        val request: NativeLoadRequest,
        val modelLib: String,
        val modelPath: String,
    )

    private class SessionState(
        val modelToken: String,
        val modelLib: String,
    ) {
        val journal: MutableList<MlcMessageEntry> = mutableListOf()
    }

    private val models = ConcurrentHashMap<String, ModelState>()
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val cancelledTokens = ConcurrentHashMap.newKeySet<String>()
    private val modelSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)

    companion object {
        const val ABI_ARM64: String = "arm64-v8a"
        const val MLC_CHAT_CONFIG: String = "mlc-chat-config.json"

        /** Broker-resolved installation dir containing the compiled model bundle. */
        const val ATTRIBUTE_RESOLVED_MODEL_PATH: String = "resolvedModelPath"

        /** Packaged compiled library name (upstream `"system://$modelLib"`). */
        const val ATTRIBUTE_MODEL_LIB: String = "modelLib"

        /**
         * Create backend only when the official runtime API is on the classpath.
         * Returns null when missing / API mismatch — production fails closed.
         */
        fun createOrNull(): MlcEngineRuntimeBackend? {
            val ref = MlcRuntimeBridge.createEngineOrNull() ?: return null
            return MlcEngineRuntimeBackend(ref)
        }

        /** True when the official runtime API resolves in this process. */
        fun isRuntimePresent(): Boolean = MlcRuntimeBridge.tryLoadRuntime()
    }
}
