package com.omnillm.features.benchmark.domain

import com.omnillm.core.errors.generated.OmniError

/**
 * Measurement run outcomes (FEAT-BENCHMARK §3 / DATA-MEASUREMENT §2).
 * Feature-local labels — not JOB FSM states. Thermal deviation yields
 * INVALID / DEGRADED and must not roll up with VALID runs.
 */
object MeasurementRunOutcomes {
    const val VALID: String = "VALID"
    const val DEGRADED: String = "DEGRADED"
    const val INVALID: String = "INVALID"
    const val CANCELLED: String = "CANCELLED"
    const val FAILED: String = "FAILED"
    const val RUNNING: String = "RUNNING"

    private val KNOWN: Set<String> = setOf(
        VALID, DEGRADED, INVALID, CANCELLED, FAILED, RUNNING,
    )

    fun isKnown(outcome: String): Boolean = outcome in KNOWN

    fun isTerminal(outcome: String): Boolean =
        outcome == VALID || outcome == DEGRADED || outcome == INVALID ||
            outcome == CANCELLED || outcome == FAILED

    /** Eligible for automatic rollup / comparison with other VALID runs. */
    fun isRollupEligible(outcome: String): Boolean = outcome == VALID
}

/**
 * Environment snapshot captured at run start (DATA-MEASUREMENT §2).
 * Free-form evidence fields; not mixed into operational telemetry series.
 */
data class EnvironmentSnapshot(
    val thermalCelsius: Int?,
    val thermalState: String,
    val batteryPercent: Int?,
    val powerSource: String,
    val backgroundRestriction: Boolean,
    val driverResetDetected: Boolean,
    val backendActual: String,
    val engineBuildIdActual: String,
    val modelRevisionIdActual: String,
    val notes: Map<String, String> = emptyMap(),
    val capturedAtEpochMs: Long,
) {
    init {
        require(thermalState.isNotBlank()) { "thermalState must be non-blank" }
        require(powerSource.isNotBlank()) { "powerSource must be non-blank" }
        require(backendActual.isNotBlank()) { "backendActual must be non-blank" }
        require(engineBuildIdActual.isNotBlank()) { "engineBuildIdActual must be non-blank" }
        require(modelRevisionIdActual.matches(HEX64)) {
            "modelRevisionIdActual must be 64-char hex"
        }
        require(capturedAtEpochMs >= 0L) { "capturedAtEpochMs must be non-negative" }
        batteryPercent?.let { require(it in 0..100) { "batteryPercent must be 0..100" } }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * MeasurementRun(runId, profileId, runSeq, startedAt, completedAt, outcome, environmentSnapshot)
 * (DATA-MEASUREMENT §2). Current view selects by runSeq DESC uniquely — never by
 * timestamp alone (FEAT-BENCHMARK acceptance §2).
 */
data class MeasurementRun(
    val runId: String,
    val profileId: String,
    val jobId: String?,
    val runSeq: Long,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val outcome: String,
    val environmentSnapshot: EnvironmentSnapshot?,
    val metrics: MeasurementMetrics?,
    val deviationReasons: List<String> = emptyList(),
    val error: OmniError? = null,
) {
    init {
        require(runId.isNotBlank()) { "runId must be non-blank" }
        require(profileId.matches(HEX64)) { "profileId must be 64-char hex" }
        require(runSeq >= 1L) { "runSeq must be >= 1" }
        require(startedAtEpochMs >= 0L) { "startedAtEpochMs must be non-negative" }
        completedAtEpochMs?.let {
            require(it >= startedAtEpochMs) { "completedAtEpochMs must be >= startedAtEpochMs" }
        }
        require(MeasurementRunOutcomes.isKnown(outcome)) {
            "unknown measurement run outcome (fail closed): $outcome"
        }
        // INVALID/DEGRADED must carry at least one deviation reason (FEAT-BENCHMARK §3).
        if (outcome == MeasurementRunOutcomes.INVALID ||
            outcome == MeasurementRunOutcomes.DEGRADED
        ) {
            require(deviationReasons.isNotEmpty()) {
                "INVALID/DEGRADED runs must list deviation reasons"
            }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Benchmark job phases projected into JobProgress.currentPhase.
 * Opaque catalog-style labels for workers — not JOB FSM states.
 */
object BenchmarkPhases {
    const val PLANNED: String = "PLANNED"
    const val WARMUP: String = "WARMUP"
    const val SAMPLING: String = "SAMPLING"
    const val AGGREGATING: String = "AGGREGATING"
    const val SEALING: String = "SEALING"

    private val KNOWN: Set<String> = setOf(PLANNED, WARMUP, SAMPLING, AGGREGATING, SEALING)

    fun isKnown(phase: String): Boolean = phase in KNOWN
}
