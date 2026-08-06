package com.omnillm.features.benchmark.policy

import com.omnillm.features.benchmark.domain.EnvironmentSnapshot
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes

/**
 * Thermal / condition deviation policy (FEAT-BENCHMARK §3 acceptance §3,
 * DATA-MEASUREMENT §6).
 *
 * Runs that exceed the profile thermal ceiling are marked INVALID or DEGRADED
 * and must **not** merge into normal rollup / comparison.
 */
object ThermalDeviationPolicy {

    /** Soft margin above ceiling still DEGRADED; hard breach INVALID. */
    const val HARD_BREACH_MARGIN_C: Int = 5

    data class Decision(
        val outcome: String,
        val reasons: List<String>,
    )

    /**
     * Combine thermal check with routing deviations.
     * [routingDeviations] already disclosed from [BenchmarkRoutingPolicy].
     */
    fun evaluate(
        profile: MeasurementProfile,
        environment: EnvironmentSnapshot,
        routingDeviations: List<String> = emptyList(),
        forcedInvalid: Boolean = false,
    ): Decision {
        val reasons = mutableListOf<String>()
        reasons += routingDeviations

        if (forcedInvalid) {
            reasons += "forced_invalid"
            return Decision(MeasurementRunOutcomes.INVALID, reasons.distinct())
        }

        val ceiling = profile.thermalCeilingCelsius
        val actual = environment.thermalCelsius
        if (ceiling != null && actual != null) {
            when {
                actual > ceiling + HARD_BREACH_MARGIN_C -> {
                    reasons += "thermal_hard_breach:actual=$actual,ceiling=$ceiling"
                    return Decision(MeasurementRunOutcomes.INVALID, reasons.distinct())
                }
                actual > ceiling -> {
                    reasons += "thermal_soft_breach:actual=$actual,ceiling=$ceiling"
                }
            }
        } else if (ceiling != null && actual == null) {
            // Unknown thermal under a declared ceiling ⇒ degrade (do not invent 0).
            reasons += "thermal_unknown_under_ceiling"
        }

        when (environment.thermalState.uppercase()) {
            "CRITICAL", "EMERGENCY", "SHUTDOWN" -> {
                reasons += "thermal_state:${environment.thermalState}"
                return Decision(MeasurementRunOutcomes.INVALID, reasons.distinct())
            }
            "SEVERE", "WARNING" -> {
                reasons += "thermal_state:${environment.thermalState}"
            }
        }

        return if (reasons.isEmpty()) {
            Decision(MeasurementRunOutcomes.VALID, emptyList())
        } else {
            Decision(MeasurementRunOutcomes.DEGRADED, reasons.distinct())
        }
    }

    /** Rollup key exclusion: only VALID outcomes participate. */
    fun mayRollup(outcome: String): Boolean =
        MeasurementRunOutcomes.isRollupEligible(outcome)
}
