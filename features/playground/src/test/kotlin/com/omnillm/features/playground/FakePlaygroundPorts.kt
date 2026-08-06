package com.omnillm.features.playground

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.AssetHandleView
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.ports.CancelPortResult
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.PlaygroundCapabilityPort
import com.omnillm.features.playground.ports.PlaygroundInferencePort
import com.omnillm.features.playground.ports.PlaygroundMetricsPort
import com.omnillm.features.playground.ports.PlaygroundModelCatalogPort
import com.omnillm.features.playground.ports.PlaygroundModelRow
import com.omnillm.features.playground.ports.PlaygroundRuntimeStatusPort
import com.omnillm.features.playground.ports.PortMetricSample
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.playground.projection.CancelPhaseProjection
import com.omnillm.runtime.observability.MetricId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** In-memory capability matrix for tests. Default SUPPORTED. */
class FakeCapabilityPort(
    private val matrix: MutableMap<Pair<String, CapabilityId>, CapabilityState> = mutableMapOf(),
    private val conditions: MutableMap<Pair<String, CapabilityId>, List<String>> = mutableMapOf(),
) : PlaygroundCapabilityPort {

    fun set(modelRevisionId: String, capability: CapabilityId, state: CapabilityState) {
        matrix[modelRevisionId to capability] = state
    }

    fun setConditions(modelRevisionId: String, capability: CapabilityId, cond: List<String>) {
        conditions[modelRevisionId to capability] = cond
    }

    override fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState =
        matrix[modelRevisionId to capability] ?: CapabilityState.SUPPORTED

    override fun conditions(capability: CapabilityId, modelRevisionId: String): List<String> =
        conditions[modelRevisionId to capability].orEmpty()
}

class FakeModelCatalog(
    private val models: List<PlaygroundModelRow>,
) : PlaygroundModelCatalogPort {
    override fun listModels(): List<PlaygroundModelRow> = models
}

class FakeRuntimeStatus(
    private var state: String = "READY",
    private var reasons: List<String> = emptyList(),
) : PlaygroundRuntimeStatusPort {
    fun set(state: String, reasons: List<String> = emptyList()) {
        this.state = state
        this.reasons = reasons
    }

    override fun runtimeState(): String = state
    override fun degradedReasons(): List<String> = reasons
}

class FakeMetricsPort(
    private val samples: List<PortMetricSample> = emptyList(),
) : PlaygroundMetricsPort {
    override fun sampleWorkspaceMetrics(nowEpochMs: Long): List<PortMetricSample> = samples
}

/**
 * In-memory inference port that records claims, cancel phases, and terminals.
 * Simulates control-plane claim-or-return + cancel progression without engines.
 */
class FakeInferencePort(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : PlaygroundInferencePort {

    private val handles = ConcurrentHashMap<String, InferenceHandle>()
    private val cancelPhases = ConcurrentHashMap<String, CancelPhase>()
    private val assets = ConcurrentHashMap<String, AssetHandleView>()
    private val chatStarts = AtomicInteger(0)
    private val embedStarts = AtomicInteger(0)
    private val cancelCalls = AtomicInteger(0)

    /** When true, chat starts stay non-terminal (STREAMING) for cancel tests. */
    var holdStreaming: Boolean = false

    /** Force startChat to return this error (capability path tested separately). */
    var forceChatError: OmniError? = null

    val chatStartCount: Int get() = chatStarts.get()
    val embedStartCount: Int get() = embedStarts.get()
    val cancelCallCount: Int get() = cancelCalls.get()

    fun putAsset(asset: AssetHandleView) {
        assets[asset.assetId] = asset
    }

    fun getHandle(requestId: String): InferenceHandle? = handles[requestId]

    override suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<InferenceHandle> {
        chatStarts.incrementAndGet()
        forceChatError?.let { return OmniResult.err(it) }

        // Idempotent claim-or-return on requestId.
        handles[spec.identity.requestId]?.let { return OmniResult.ok(it) }

        val now = clockMs()
        val state = if (holdStreaming) "STREAMING" else "COMPLETED"
        val handle = InferenceHandle(
            requestId = spec.identity.requestId,
            operationKind = "chat",
            state = state,
            actualModelRevisionId = spec.modelRevisionId,
            engineBuildId = "engine-test-1",
            backend = "cpu",
            sessionId = if (spec.sourceSession is com.omnillm.features.playground.api.SourceSessionRef.None) {
                "sess-${spec.identity.requestId.take(8)}"
            } else {
                (spec.sourceSession as com.omnillm.features.playground.api.SourceSessionRef.Existing).sessionId
            },
            assistantText = if (holdStreaming) null else "hello from fake",
            metrics = listOf(
                PortMetricSample(
                    metricId = MetricId.REQUEST_TTFT_MS.wireName,
                    value = 42.0,
                    unit = "ms",
                    evidenceLabel = EvidenceLabel.MEASURED,
                    sampledAtEpochMs = now,
                ),
                PortMetricSample(
                    metricId = MetricId.REQUEST_TOKENS_PER_SECOND.wireName,
                    value = null,
                    unit = "token/s",
                    evidenceLabel = EvidenceLabel.UNKNOWN,
                    sampledAtEpochMs = now,
                ),
                PortMetricSample(
                    metricId = MetricId.REQUEST_QUEUE_MS.wireName,
                    value = 5.0,
                    unit = "ms",
                    evidenceLabel = EvidenceLabel.REPORTED,
                    sampledAtEpochMs = now,
                    source = "scheduler",
                ),
            ),
        )
        handles[spec.identity.requestId] = handle
        return OmniResult.ok(handle)
    }

    override suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<InferenceHandle> {
        embedStarts.incrementAndGet()
        handles[spec.identity.requestId]?.let { return OmniResult.ok(it) }

        val dims = spec.dimensions ?: 8
        val vectors = spec.inputs.map { input ->
            List(dims) { i -> (input.length + i).toDouble() }
        }
        val handle = InferenceHandle(
            requestId = spec.identity.requestId,
            operationKind = "embedding",
            state = "COMPLETED",
            actualModelRevisionId = spec.modelRevisionId,
            engineBuildId = "engine-test-1",
            backend = "cpu",
            sessionId = null, // embeddings must not create generation Session
            embeddingVectors = vectors,
            embeddingDimensions = dims,
            metrics = listOf(
                PortMetricSample(
                    metricId = MetricId.REQUEST_QUEUE_MS.wireName,
                    value = 1.0,
                    unit = "ms",
                    evidenceLabel = EvidenceLabel.MEASURED,
                    sampledAtEpochMs = clockMs(),
                ),
            ),
        )
        handles[spec.identity.requestId] = handle
        return OmniResult.ok(handle)
    }

    /**
     * When true, each [cancel] advances one cancel phase
     * (requested → acknowledged → execution stopped → terminal).
     */
    var progressiveCancel: Boolean = false

    override suspend fun cancel(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<CancelPortResult> {
        cancelCalls.incrementAndGet()
        val existing = handles[spec.requestId]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to spec.requestId),
                ),
            )

        if (RequestUiProjectionTerminal.isTerminal(existing.state)) {
            cancelPhases[spec.requestId] = CancelPhase.TERMINAL
            return OmniResult.ok(
                CancelPortResult(
                    requestId = spec.requestId,
                    phase = CancelPhase.TERMINAL,
                    requestState = existing.state,
                ),
            )
        }

        if (progressiveCancel) {
            return OmniResult.ok(cancelStep(spec.requestId))
        }

        // Single-shot cancel → durable CANCELLED (matches early Orchestrator cancel).
        val phase = CancelPhase.TERMINAL
        cancelPhases[spec.requestId] = phase
        val newState = "CANCELLED"
        handles[spec.requestId] = existing.copy(
            state = newState,
            cancelPhase = phase,
            assistantText = existing.assistantText,
            error = OmniError.CANCELLED(message = "cancelled by client"),
        )
        return OmniResult.ok(
            CancelPortResult(
                requestId = spec.requestId,
                phase = phase,
                requestState = newState,
            ),
        )
    }

    /**
     * Multi-step cancel that exposes each phase for progressive UI tests.
     */
    fun cancelStep(requestId: String): CancelPortResult {
        val existing = handles[requestId]
            ?: error("request not found: $requestId")
        val prev = cancelPhases[requestId]
        val phase = when (prev) {
            null -> CancelPhase.REQUESTED
            else -> CancelPhaseProjection.advance(prev)
        }
        cancelPhases[requestId] = phase
        val newState = when (phase) {
            CancelPhase.REQUESTED -> "STREAMING"
            CancelPhase.ACKNOWLEDGED -> "STREAMING"
            CancelPhase.EXECUTION_STOPPED -> "TERMINATING"
            CancelPhase.TERMINAL -> "CANCELLED"
        }
        handles[requestId] = existing.copy(
            state = newState,
            cancelPhase = phase,
            error = if (phase == CancelPhase.TERMINAL) {
                OmniError.CANCELLED(message = "cancelled by client")
            } else {
                null
            },
        )
        return CancelPortResult(
            requestId = requestId,
            phase = phase,
            requestState = newState,
        )
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<InferenceHandle> {
        val h = handles[requestId]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to requestId),
                ),
            )
        return OmniResult.ok(h.copy(cancelPhase = cancelPhases[requestId] ?: h.cancelPhase))
    }

    override fun getAsset(assetId: String): AssetHandleView? = assets[assetId]

    companion object {
        fun progressive(clockMs: () -> Long = { System.currentTimeMillis() }): FakeInferencePort =
            FakeInferencePort(clockMs).also { it.progressiveCancel = true }
    }
}

/** Local terminal check without depending on projection package cycles in fakes. */
private object RequestUiProjectionTerminal {
    private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "ABORTED_UNCERTAIN")
    fun isTerminal(state: String): Boolean = state in TERMINAL
}
