package com.omnillm.features.playground.ports

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
import com.omnillm.features.playground.projection.CancelPhase

/**
 * Feature-level ports into the runtime control plane for FEAT-PLAYGROUND.
 *
 * Feature packs compose these ports only — never open domain DB writers,
 * native engines, or engine-private knobs (INV-001, ADR-010, ARCH-EXTENSION).
 *
 * Implementations live behind AIDL [ai.omnillm.api.IOmniRuntime] / Orchestrator
 * on the runtime process.
 */

/** Installed / selectable model catalog (read-only projection). */
interface PlaygroundModelCatalogPort {
    fun listModels(): List<PlaygroundModelRow>
}

data class PlaygroundModelRow(
    val modelRevisionId: String,
    val displayName: String,
    val installationState: String?,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
    }
}

object EmptyPlaygroundModelCatalogPort : PlaygroundModelCatalogPort {
    override fun listModels(): List<PlaygroundModelRow> = emptyList()
}

/**
 * Capability negotiation lookup per model revision (CORE-ORCHESTRATOR filter 1).
 * Unknown capability IDs must not be invented; unknown cells fail closed (INV-018).
 */
interface PlaygroundCapabilityPort {
    fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState

    /** Optional conditions when state is CONDITIONAL. */
    fun conditions(capability: CapabilityId, modelRevisionId: String): List<String> = emptyList()
}

/**
 * Runtime inference surface (chat / embed / cancel / query).
 * Maps to IOmniRuntime / Orchestrator — not engine C APIs.
 */
interface PlaygroundInferencePort {
    suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<InferenceHandle>

    suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<InferenceHandle>

    suspend fun cancel(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<CancelPortResult>

    suspend fun query(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<InferenceHandle>

    /** Asset metadata for multimodal claim checks. */
    fun getAsset(assetId: String): AssetHandleView? = null
}

data class InferenceHandle(
    val requestId: String,
    val operationKind: String,
    val state: String,
    val cancelPhase: CancelPhase? = null,
    val actualModelRevisionId: String? = null,
    val engineBuildId: String? = null,
    val backend: String? = null,
    val sessionId: String? = null,
    val assistantText: String? = null,
    val embeddingVectors: List<List<Double>>? = null,
    val embeddingDimensions: Int? = null,
    val error: OmniError? = null,
    val degraded: Boolean = false,
    val degradedReasons: List<String> = emptyList(),
    /** Per-request metrics with evidence labels. */
    val metrics: List<PortMetricSample> = emptyList(),
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(operationKind.isNotBlank()) { "operationKind must be non-blank" }
        require(state.isNotBlank()) { "state must be non-blank" }
    }
}

data class CancelPortResult(
    val requestId: String,
    val phase: CancelPhase,
    val requestState: String?,
    val error: OmniError? = null,
)

data class PortMetricSample(
    val metricId: String,
    val value: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val source: String? = null,
    val confidence: Double? = null,
)

/** Read-only runtime strip (READY / DEGRADED / …). */
interface PlaygroundRuntimeStatusPort {
    fun runtimeState(): String
    fun degradedReasons(): List<String> = emptyList()
}

object StaticRuntimeReadyPort : PlaygroundRuntimeStatusPort {
    override fun runtimeState(): String = "READY"
}

/**
 * Optional live metrics feed for the workspace strip.
 * Values always carry [EvidenceLabel] + sampledAt (CORE-OBSERVABILITY).
 */
interface PlaygroundMetricsPort {
    fun sampleWorkspaceMetrics(nowEpochMs: Long): List<PortMetricSample>
}

object NoOpPlaygroundMetricsPort : PlaygroundMetricsPort {
    override fun sampleWorkspaceMetrics(nowEpochMs: Long): List<PortMetricSample> = emptyList()
}

/**
 * Bundle of feature ports used by FEAT-PLAYGROUND use-cases and view-models.
 */
data class PlaygroundFeaturePorts(
    val inference: PlaygroundInferencePort,
    val capabilities: PlaygroundCapabilityPort,
    val models: PlaygroundModelCatalogPort = EmptyPlaygroundModelCatalogPort,
    val metrics: PlaygroundMetricsPort = NoOpPlaygroundMetricsPort,
    val runtimeStatus: PlaygroundRuntimeStatusPort = StaticRuntimeReadyPort,
    /** STRUCTURED_TOOLS tab — wired to ToolsApi on control plane when present. */
    val structured: PlaygroundStructuredPort = FailClosedPlaygroundStructuredPort,
)
