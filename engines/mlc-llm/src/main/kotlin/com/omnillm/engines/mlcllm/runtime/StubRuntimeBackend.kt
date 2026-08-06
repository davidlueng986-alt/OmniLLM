package com.omnillm.engines.mlcllm.runtime

/**
 * Fail-closed exploratory stub for unit wiring without real MLC compiler/runtime.
 *
 * **Not** a production backend. Does not load generated modules or run TVM.
 * Dry "load" never elevates model trust (INV-008).
 *
 * Default behavior ([exploratoryDryRun] = false): every mutating / execute path
 * returns [NativeErrorCode.UNKNOWN_CAPABILITY] so the adapter maps to
 * [com.omnillm.core.errors.generated.OmniError.CAPABILITY_UNKNOWN].
 *
 * When [exploratoryDryRun] is true (controlled tests only):
 * - CPU probe/load/generate may return synthetic success for Plan→Commit plumbing
 * - opencl / vulkan / other backends stay UNKNOWN (no silent cross-backend claim)
 * - embed / multimodal stay UNKNOWN (ENGINE-MLC §10)
 *
 * Capability-matrix / Registry cells remain UNQUALIFIED regardless of dry-run.
 *
 * TODO(runtime): replace with real JNI / official Android MLCEngine binding under
 * `:android:native` with 16 KB page-size verification (see README integration guide).
 */
class StubRuntimeBackend(
    /**
     * When true, allows synthetic CPU probe/load/generate for architecture tests only.
     * Default false — unproven capabilities stay UNKNOWN.
     */
    var exploratoryDryRun: Boolean = false,
    var deltaCount: Int = 2,
    var failLoad: Boolean = false,
    var failGenerate: Boolean = false,
) : RuntimeBackend {

    private val models = java.util.concurrent.ConcurrentHashMap<String, NativeLoadRequest>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val modelSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val cancelTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun libraryLabel(): String = "stub-mlc-llm"

    override fun isAvailable(): Boolean = exploratoryDryRun

    override fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome> {
        if (!exploratoryDryRun) {
            return unknown("PROBE", request.backend)
        }
        if (request.backend !in EXPLORATORY_BACKENDS) {
            return unknown("PROBE", request.backend)
        }
        return NativeResult.ok(
            NativeProbeOutcome(
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

    override fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken> {
        if (!exploratoryDryRun) {
            return unknown("LOAD", request.backend)
        }
        if (request.backend !in EXPLORATORY_BACKENDS) {
            return unknown("LOAD", request.backend)
        }
        if (failLoad) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.MODULE_LOAD_FAILED,
                    message = "stub generated-module load failed",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        val id = "stub-mlc-model-${modelSeq.incrementAndGet()}"
        models[id] = request
        return NativeResult.ok(NativeModelToken(id))
    }

    override fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken> {
        if (!exploratoryDryRun) {
            return unknown("CREATE_SESSION", "n/a")
        }
        if (!models.containsKey(model.value)) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        val id = "stub-mlc-session-${sessionSeq.incrementAndGet()}"
        sessions[id] = model.value
        return NativeResult.ok(NativeSessionToken(id))
    }

    override fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome> {
        if (!exploratoryDryRun) {
            return unknown("GENERATE", "n/a")
        }
        if (!sessions.containsKey(session.value)) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        }
        if (failGenerate) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.GENERATE_FAILED,
                    message = "stub generate failed",
                ),
            )
        }

        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.METADATA,
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
                    NativeStreamEvent(
                        kind = NativeStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
                return NativeResult.err(
                    NativeError(
                        code = NativeErrorCode.CANCELLED,
                        message = "stub generate cancelled",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
            }
            onEvent(
                NativeStreamEvent(
                    kind = NativeStreamKind.TOKEN_DELTA,
                    payloadDigestHex = STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to i.toString()),
                ),
            )
            completed++
        }

        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )
        return NativeResult.ok(
            NativeGenerateOutcome(
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome> {
        // Embedding always unproven in scaffold (ENGINE-MLC §10) — even exploratory.
        return unknown("EMBED", "n/a")
    }

    override fun closeSession(session: NativeSessionToken): NativeResult<Unit> {
        sessions.remove(session.value)
        return NativeResult.ok(Unit)
    }

    override fun unloadModel(model: NativeModelToken): NativeResult<Unit> {
        val sessionsToClose = sessions.filterValues { it == model.value }.keys
        sessionsToClose.forEach { sessions.remove(it) }
        models.remove(model.value)
        return NativeResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): NativeResult<Unit> {
        cancelTokens.add(operationToken)
        return NativeResult.ok(Unit)
    }

    private fun unknown(operation: String, backend: String): NativeResult<Nothing> =
        NativeResult.err(
            NativeError(
                code = NativeErrorCode.UNKNOWN_CAPABILITY,
                message = "MLC-LLM $operation not proven for this build " +
                    "(UNQUALIFIED; incomplete upstream lock or missing cell evidence)",
                attributes = mapOf(
                    "operation" to operation,
                    "backend" to backend,
                    "engineId" to "MLC-LLM",
                ),
            ),
        )

    companion object {
        /**
         * Exploratory dry-run only exercises CPU. Accelerator backends stay
         * CAPABILITY_UNKNOWN (no silent inheritance from CPU).
         */
        val EXPLORATORY_BACKENDS: Set<String> = setOf("cpu")

        const val STUB_DELTA_DIGEST: String =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
