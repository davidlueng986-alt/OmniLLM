package com.omnillm.features.benchmark.api

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.benchmark.domain.BenchmarkScenarioTemplate
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.runtime.observability.MetricClass

/**
 * Pre-start plan (ADR-002 — pure projection, no domain mutation).
 */
data class BenchmarkPlan(
    val profile: MeasurementProfile,
    val profileId: String,
    val canonicalProfileJson: String,
    val templateId: String?,
    val iterations: Int?,
    val fallbackPolicy: String,
    val estimatedSampleCount: Int,
    val estimatedWarmupCount: Int,
    val fairnessNoticeKey: String = "benchmark.fairness.interactive_share",
    val dimensions: List<ProfileDimensionView>,
) {
    init {
        require(profileId.matches(HEX64)) { "profileId must be 64-char hex" }
        require(canonicalProfileJson.isNotBlank()) { "canonicalProfileJson must be non-blank" }
        require(fallbackPolicy.isNotBlank()) { "fallbackPolicy must be non-blank" }
        require(estimatedSampleCount > 0) { "estimatedSampleCount must be positive" }
        require(estimatedWarmupCount >= 0) { "estimatedWarmupCount must be non-negative" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * One expandable profile dimension for UI (FEAT-BENCHMARK §2).
 */
data class ProfileDimensionView(
    val key: String,
    val value: String,
    val sourceKey: String,
) {
    init {
        require(key.isNotBlank()) { "key must be non-blank" }
    }
}

/**
 * Job handle after claim-or-return create / cancel / query.
 */
data class BenchmarkJobHandle(
    val jobId: String,
    val runId: String?,
    val kind: String,
    val state: String,
    val resourceVersion: Long,
    val createdNew: Boolean,
    val cancelRequested: Boolean = false,
    val progressPhase: String? = null,
    val sampleCount: Long = 0L,
    val error: OmniError? = null,
)

/**
 * Comparison result — never a single scoreboard when profiles differ
 * (FEAT-BENCHMARK acceptance §6).
 */
data class ProfileComparisonView(
    val leftProfileId: String,
    val rightProfileId: String,
    val compatible: Boolean,
    val differingDimensions: List<String>,
    /** True when UI must forbid a single ranking. */
    val forbidSingleRanking: Boolean,
    val noticeKey: String,
) {
    init {
        require(leftProfileId.isNotBlank() && rightProfileId.isNotBlank())
        if (!compatible) {
            require(forbidSingleRanking) {
                "incompatible profiles must forbid single ranking"
            }
            require(differingDimensions.isNotEmpty()) {
                "incompatible comparison must list differing dimensions"
            }
        }
    }
}

/**
 * Sealed measurement report (export). Explicitly MetricClass.MEASUREMENT —
 * must not be ingested as operational telemetry (CORE-OBSERVABILITY §5).
 */
data class MeasurementReport(
    val reportId: String,
    val profileId: String,
    val canonicalProfileJson: String,
    val runs: List<MeasurementRun>,
    val digests: Map<String, String>,
    val includePromptOutput: Boolean,
    val metricClass: MetricClass = MetricClass.MEASUREMENT,
    val schemaVersion: String = "1",
    val createdAtEpochMs: Long,
    /** Safe QR fields only — no long-lived secrets. */
    val qrShareFields: Map<String, String> = emptyMap(),
) {
    init {
        require(reportId.isNotBlank()) { "reportId must be non-blank" }
        require(profileId.matches(HEX64)) { "profileId must be 64-char hex" }
        require(metricClass == MetricClass.MEASUREMENT) {
            "measurement report must be MetricClass.MEASUREMENT (report != telemetry)"
        }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs must be non-negative" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Metric strip item with mandatory evidence label.
 */
data class BenchmarkMetricView(
    val name: String,
    val value: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val methodVersion: String?,
    val sampleCount: Long?,
    val errorBound: Double?,
    val metricClass: MetricClass = MetricClass.MEASUREMENT,
) {
    init {
        require(name.isNotBlank()) { "name must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(metricClass == MetricClass.MEASUREMENT) {
            "benchmark metrics must be MEASUREMENT class"
        }
    }
}

/**
 * Full feature snapshot for the benchmark screen.
 */
data class BenchmarkSnapshot(
    val snapshotVersion: Long,
    val uiPhase: String,
    val plan: BenchmarkPlan?,
    val activeJob: BenchmarkJobHandle?,
    val profiles: List<MeasurementProfile>,
    val runs: List<MeasurementRun>,
    val currentRunByProfile: Map<String, MeasurementRun>,
    val templates: List<BenchmarkScenarioTemplate>,
    val lastReport: MeasurementReport?,
    val lastError: OmniError?,
    val degradedReasons: List<String>,
) {
    init {
        require(BenchmarkUiPhases.isKnown(uiPhase)) {
            "unknown benchmark ui phase (fail closed): $uiPhase"
        }
        require(snapshotVersion >= 0L) { "snapshotVersion must be non-negative" }
    }

    val isEmpty: Boolean
        get() = uiPhase == BenchmarkUiPhases.EMPTY &&
            runs.isEmpty() &&
            activeJob == null

    /** Current run for a profile: max runSeq only (FEAT-BENCHMARK acceptance §2). */
    fun currentRun(profileId: String): MeasurementRun? =
        currentRunByProfile[profileId]
            ?: runs.filter {
                it.profileId == profileId &&
                    MeasurementRunOutcomes.isTerminal(it.outcome)
            }.maxByOrNull { it.runSeq }
}
