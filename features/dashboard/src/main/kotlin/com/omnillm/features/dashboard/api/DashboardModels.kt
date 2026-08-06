package com.omnillm.features.dashboard.api

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.observability.HealthLevel

/**
 * UI-domain models for FEAT-DASHBOARD.
 *
 * Pure projections — no domain mutation (FEATURE-SYSTEM, ADR-002).
 * Evidence labels and runtime/request states come from catalogs only.
 */

/**
 * Screen-level presentation phase (UX empty / loading / error / degraded).
 * Not a catalog FSM — local UI chrome over canonical payloads.
 */
enum class DashboardUiPhase {
    /** No snapshot yet (first open). */
    EMPTY,
    /** Refresh in flight. */
    LOADING,
    /** Snapshot present; runtime/health not degraded. */
    READY,
    /** Snapshot present with degraded health or runtime DEGRADED. */
    DEGRADED,
    /** Last load failed; previous snapshot may still be shown. */
    ERROR,
}

/**
 * Metric/resource scalar for UI binding.
 * [displayValue] is null when [evidenceLabel] is UNKNOWN — never coerce to 0
 * (FEAT-DASHBOARD §3, EvidenceSemantics).
 */
data class EvidencedMetricUi(
    val name: String,
    val displayValue: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val ageMs: Long,
    val source: String? = null,
    val confidence: Double? = null,
    val dimensions: Map<String, String> = emptyMap(),
    /** True when the number may be rendered; false for UNKNOWN. */
    val allowsNumericDisplay: Boolean,
) {
    init {
        require(name.isNotBlank()) { "metric name must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        require(ageMs >= 0L) { "ageMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.UNKNOWN) {
            // UNKNOWN must never present a fabricated 0 (FEAT-DASHBOARD §3).
            // displayValue may still be non-null if producer withheld trust, but
            // allowsNumericDisplay is false so UI renders "unknown".
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED metrics must disclose source"
            }
        }
    }
}

/**
 * One resource dimension row: budget, reserved, allocated, safety, free,
 * evidence, confidence, blocked (FEAT-DASHBOARD §2 Resources).
 */
data class ResourceDimensionUi(
    val dimension: String,
    val capacity: EvidencedMetricUi,
    val reserved: EvidencedMetricUi,
    val allocated: EvidencedMetricUi,
    val free: EvidencedMetricUi,
    val safetyMargin: EvidencedMetricUi,
    val conservationOk: Boolean,
    val blockedReasonCode: String? = null,
)

/**
 * Full multi-dimensional resource strip for the dashboard.
 */
data class ResourceAccountingUi(
    val dimensions: List<ResourceDimensionUi>,
    val conservationOk: Boolean,
    val sampledAtEpochMs: Long,
    val evidenceLabel: EvidenceLabel,
)

/**
 * Service / subject health projection (CORE-OBSERVABILITY §3).
 */
data class HealthSubjectUi(
    val kind: String,
    val subjectId: String,
    val level: HealthLevel,
    val reasonCodes: List<String>,
    val sinceEpochMs: Long,
    val sampledAtEpochMs: Long,
    val evidenceLabel: EvidenceLabel,
    val ageMs: Long,
    val affectedCapabilities: List<String>,
    val automaticActions: List<String>,
    val recommendedActions: List<String>,
    val diagnosticSummary: String? = null,
    val source: String? = null,
)

data class RuntimeHealthUi(
    val runtimeState: String,
    val resourceVersion: Long,
    val overallLevel: HealthLevel,
    val degradedReasons: List<String>,
    val subjects: List<HealthSubjectUi>,
    val sampledAtEpochMs: Long,
)

/**
 * Safe recommended action for the operator (FEAT-DASHBOARD §4).
 * Actions never bypass trust or Governor; they are suggestions only.
 */
data class ActionableItemUi(
    val reasonCode: String,
    val labelKey: String,
    val severity: DashboardSeverity,
    val recommendedActionKeys: List<String>,
    val affectedCapabilities: List<String> = emptyList(),
    val requiresLocalAdmin: Boolean = false,
)

/**
 * UX severity keys aligned with ux-projection-catalog (info/warning/error).
 * Not a domain state machine.
 */
enum class DashboardSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Active request / job row for Requests & Jobs section.
 * Client-generated [requestId] is authoritative for cancel / query (ADR-004/005).
 */
data class RequestRowUi(
    val requestId: String,
    val correlationId: String?,
    val principalId: String?,
    val phase: String,
    val labelKey: String,
    val severity: DashboardSeverity,
    val engineBuildId: String?,
    val modelRevisionId: String?,
    val cancelAllowed: Boolean,
    val queuePosition: Int? = null,
    val deadlineMonotonic: Long? = null,
    val replyLossRecoverable: Boolean = false,
)

/**
 * Redacted trace summary for REQUEST_TRACE capability.
 */
data class TraceSummaryUi(
    val correlationId: String,
    val requestId: String?,
    val jobId: String?,
    val principalId: String?,
    val eventCount: Int,
    val lastEventName: String?,
    val lastPhase: String?,
    val engineBuildId: String?,
    val modelRevisionId: String?,
)

/**
 * Performance strip — operational only (not benchmark comparison).
 * Percentiles only when methodVersion present (observability-catalog rules).
 */
data class PerformanceStripUi(
    val ttftMs: EvidencedMetricUi?,
    val tokensPerSecond: EvidencedMetricUi?,
    val queueMs: EvidencedMetricUi?,
    val errorCount: EvidencedMetricUi?,
    val thermalStatus: EvidencedMetricUi?,
    /** When true, profile-incompatible comparisons must not auto-rank. */
    val operationalOnly: Boolean = true,
)

/**
 * Last measurement run projection for MEASUREMENTS tab (FEAT-BENCHMARK / FEAT-DASHBOARD).
 * Fixture metrics carry REPORTED evidence — never auto-rank as device PASS.
 */
data class LastMeasurementRunUi(
    val runId: String,
    val profileId: String,
    val runSeq: Long,
    val outcome: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val ttftMs: EvidencedMetricUi?,
    val throughputTokensPerSec: EvidencedMetricUi?,
    val endToEndLatencyMs: EvidencedMetricUi?,
    val evidenceLabel: EvidenceLabel,
    val methodVersion: String?,
    val fixtureSource: String?,
    val deviationReasons: List<String> = emptyList(),
) {
    init {
        require(runId.isNotBlank()) { "runId must be non-blank" }
        require(profileId.isNotBlank()) { "profileId must be non-blank" }
        require(outcome.isNotBlank()) { "outcome must be non-blank" }
    }
}

/**
 * Full dashboard home snapshot answering FEAT-DASHBOARD §1 questions.
 */
data class DashboardSnapshot(
    val snapshotVersion: Long,
    val sampledAtEpochMs: Long,
    val health: RuntimeHealthUi,
    val resources: ResourceAccountingUi?,
    val performance: PerformanceStripUi,
    val requests: List<RequestRowUi>,
    val traces: List<TraceSummaryUi>,
    val actions: List<ActionableItemUi>,
    val metrics: List<EvidencedMetricUi>,
    /** True when no subjects / metrics / requests yet (empty operational surface). */
    val isOperationallyEmpty: Boolean,
    /** Last sealed measurement run (fixture or live); null when none. */
    val lastMeasurementRun: LastMeasurementRunUi? = null,
)

/**
 * Result of a client-generated cancel for an in-flight request
 * (CANCELLATION capability; ADR-004/005 query-safe).
 */
data class CancelRequestResult(
    val requestId: String,
    val priorPhase: String?,
    val phase: String,
    val terminal: Boolean,
)

/**
 * Capability negotiation outcome for a required capability set
 * (CAPABILITY_NEGOTIATION; fail closed on UNSUPPORTED / UNKNOWN).
 */
data class CapabilityNegotiationResult(
    val allSupported: Boolean,
    val cells: List<CapabilityCellUi>,
) {
    val unsupportedIds: List<String>
        get() = cells.filter { it.state.name == "UNSUPPORTED" }.map { it.capabilityId }

    val blockingIds: List<String>
        get() = cells.filter {
            it.state.name != "SUPPORTED" && it.state.name != "CONDITIONAL"
        }.map { it.capabilityId }
}

data class CapabilityCellUi(
    val capabilityId: String,
    val state: com.omnillm.core.canonical.generated.CapabilityState,
    val conditions: List<String> = emptyList(),
)

sealed class DashboardFeatureError {
    data class Port(val error: OmniError) : DashboardFeatureError()
    data class Validation(val message: String) : DashboardFeatureError()
}
