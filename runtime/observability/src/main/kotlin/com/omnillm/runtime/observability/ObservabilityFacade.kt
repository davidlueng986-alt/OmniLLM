package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult

/**
 * Facade wiring metrics, health, and traces for the runtime control plane
 * (CORE-OBSERVABILITY). Feature packs (dashboard / diagnostics) consume
 * snapshots; they never write DB or load engines (INV-001).
 */
class ObservabilityFacade(
    val metrics: MetricRegistry,
    val health: HealthRegistry,
    val traces: TraceRecorder,
    private val clockWallMs: () -> Long = { System.currentTimeMillis() },
) {
    fun recordMetric(
        id: MetricId,
        value: Double,
        evidenceLabel: EvidenceLabel,
        dimensions: Map<String, String> = emptyMap(),
        source: String? = null,
        sampledAtEpochMs: Long? = null,
    ): OmniResult<Unit> =
        metrics.record(
            id = id,
            value = value,
            evidenceLabel = evidenceLabel,
            dimensions = dimensions,
            sampledAtEpochMs = sampledAtEpochMs,
            source = source,
        )

    fun metricSummary(): MetricSnapshot =
        metrics.snapshot(
            privacyFilter = setOf(MetricPrivacy.OPERATIONAL),
            includeDetailRestricted = false,
        )

    fun metricDetail(): MetricSnapshot =
        metrics.snapshot(
            privacyFilter = null,
            includeDetailRestricted = true,
        )

    fun serviceHealth(): ServiceHealthSnapshot = health.snapshot()

    fun nowEpochMs(): Long = clockWallMs()
}
