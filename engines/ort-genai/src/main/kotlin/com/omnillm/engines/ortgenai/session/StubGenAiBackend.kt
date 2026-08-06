package com.omnillm.engines.ortgenai.session

/**
 * Fail-closed exploratory stub for unit wiring without real ORT / GenAI natives.
 *
 * **Not** a production backend. Does not load `.onnx`, genai_config, or EP libs.
 * Dry "load" never elevates model trust (INV-008).
 *
 * Default behavior: every mutation path returns [GenAiErrorCode.UNKNOWN_CAPABILITY]
 * so the adapter maps to [com.omnillm.core.errors.generated.OmniError.CAPABILITY_UNKNOWN].
 * Set [exploratoryDryRun] only in controlled tests that need Plan→Commit plumbing.
 *
 * TODO(native): replace with `JniGenAiBackend` once UPSTREAM.lock is complete and
 * packaged under `:android:native` with 16 KB page-size verification.
 */
class StubGenAiBackend(
    /**
     * When true, allows synthetic probe/load/generate for architecture tests only.
     * Default false — unproven capabilities stay UNKNOWN.
     */
    var exploratoryDryRun: Boolean = false,
    var deltaCount: Int = 2,
) : GenAiBackend {

    private val models = java.util.concurrent.ConcurrentHashMap<String, GenAiLoadRequest>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val modelSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val cancelTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun libraryLabel(): String = "stub-ort-genai"

    override fun isAvailable(): Boolean = exploratoryDryRun

    override fun probe(request: GenAiProbeRequest): GenAiResult<GenAiProbeOutcome> {
        if (!exploratoryDryRun) {
            return unknown("PROBE", request.backend)
        }
        return GenAiResult.ok(
            GenAiProbeOutcome(
                backend = request.backend,
                available = true,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "false",
                    "exploratory" to "true",
                ),
            ),
        )
    }

    override fun loadModel(request: GenAiLoadRequest): GenAiResult<GenAiModelToken> {
        if (!exploratoryDryRun) {
            return unknown("LOAD", request.backend)
        }
        val id = "stub-ort-model-${modelSeq.incrementAndGet()}"
        models[id] = request
        return GenAiResult.ok(GenAiModelToken(id))
    }

    override fun createSession(
        model: GenAiModelToken,
        request: GenAiSessionRequest,
    ): GenAiResult<GenAiSessionToken> {
        if (!exploratoryDryRun) {
            return unknown("CREATE_SESSION", "n/a")
        }
        if (!models.containsKey(model.value)) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        val id = "stub-ort-session-${sessionSeq.incrementAndGet()}"
        sessions[id] = model.value
        return GenAiResult.ok(GenAiSessionToken(id))
    }

    override fun generate(
        session: GenAiSessionToken,
        request: GenAiGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (GenAiStreamEvent) -> Unit,
    ): GenAiResult<GenAiGenerateOutcome> {
        if (!exploratoryDryRun) {
            return unknown("GENERATE", "n/a")
        }
        if (!sessions.containsKey(session.value)) {
            return GenAiResult.err(
                GenAiError(
                    code = GenAiErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        }

        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.METADATA,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                ),
            ),
        )

        var completed = 0
        for (i in 0 until deltaCount) {
            if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
                onEvent(
                    GenAiStreamEvent(
                        kind = GenAiStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
                return GenAiResult.err(
                    GenAiError(
                        code = GenAiErrorCode.CANCELLED,
                        message = "stub generate cancelled",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
            }
            onEvent(
                GenAiStreamEvent(
                    kind = GenAiStreamKind.TOKEN_DELTA,
                    payloadDigestHex = STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to i.toString()),
                ),
            )
            completed++
        }

        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            GenAiStreamEvent(
                kind = GenAiStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )
        return GenAiResult.ok(
            GenAiGenerateOutcome(
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: GenAiModelToken,
        request: GenAiEmbedRequest,
    ): GenAiResult<GenAiEmbedOutcome> {
        // Embedding always unproven in scaffold (ENGINE-ORTGENAI §10)
        return unknown("EMBED", "n/a")
    }

    override fun closeSession(session: GenAiSessionToken): GenAiResult<Unit> {
        sessions.remove(session.value)
        return GenAiResult.ok(Unit)
    }

    override fun unloadModel(model: GenAiModelToken): GenAiResult<Unit> {
        val sessionsToClose = sessions.filterValues { it == model.value }.keys
        sessionsToClose.forEach { sessions.remove(it) }
        models.remove(model.value)
        return GenAiResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): GenAiResult<Unit> {
        cancelTokens.add(operationToken)
        return GenAiResult.ok(Unit)
    }

    private fun unknown(operation: String, backend: String): GenAiResult<Nothing> =
        GenAiResult.err(
            GenAiError(
                code = GenAiErrorCode.UNKNOWN_CAPABILITY,
                message = "ONNX Runtime GenAI $operation not proven for this build " +
                    "(UNQUALIFIED; incomplete upstream lock or missing cell evidence)",
                attributes = mapOf(
                    "operation" to operation,
                    "backend" to backend,
                    "engineId" to "ONNX-Runtime-GenAI",
                ),
            ),
        )

    companion object {
        const val STUB_DELTA_DIGEST: String =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
