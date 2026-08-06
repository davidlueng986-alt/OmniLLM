package com.omnillm.features.benchmark.policy

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.benchmark.domain.EnvironmentSnapshot
import com.omnillm.features.benchmark.domain.MeasurementProfile

/**
 * Routing policy for benchmark jobs (FEAT-BENCHMARK + FEAT-ROUTING §1/§6).
 *
 * Hard rule: **never silent cross-revision fallback**.
 * Benchmark pins exact ModelRevisionId from the MeasurementProfile; any actual
 * revision divergence is a hard failure unless an explicit ALLOW_LIST was
 * declared (and even then the run is marked DEGRADED with disclosed reasons —
 * never silently compared as the requested revision).
 */
object BenchmarkRoutingPolicy {

    /**
     * Default for benchmark: NONE — exact revision only.
     * SAME_REVISION_ONLY may allow backend change with disclosure.
     * ALLOW_LIST never applies silently.
     */
    val DEFAULT_FALLBACK: FallbackPolicy = FallbackPolicy.NONE

    /**
     * Validate planned profile against actual environment after load.
     * Returns null when acceptable; OmniError when fail-closed.
     */
    fun validateActualAgainstProfile(
        profile: MeasurementProfile,
        actual: EnvironmentSnapshot,
        fallbackPolicy: FallbackPolicy = DEFAULT_FALLBACK,
        allowedRevisionIds: Set<String> = emptySet(),
    ): RoutingCheck {
        val deviations = mutableListOf<String>()

        // Cross-revision check — always explicit.
        if (actual.modelRevisionIdActual != profile.modelRevisionId) {
            when (fallbackPolicy) {
                FallbackPolicy.NONE, FallbackPolicy.SAME_REVISION_ONLY -> {
                    return RoutingCheck.Fail(
                        OmniError.INVALID_REQUEST(
                            message = "silent cross-revision fallback denied",
                            details = mapOf(
                                "requestedRevisionId" to profile.modelRevisionId,
                                "actualRevisionId" to actual.modelRevisionIdActual,
                                "fallbackPolicy" to fallbackPolicy.name,
                                "reason" to "no_silent_cross_revision_fallback",
                            ),
                        ),
                    )
                }
                FallbackPolicy.ALLOW_LIST -> {
                    if (actual.modelRevisionIdActual !in allowedRevisionIds) {
                        return RoutingCheck.Fail(
                            OmniError.INVALID_REQUEST(
                                message = "actual revision not on allowlist",
                                details = mapOf(
                                    "requestedRevisionId" to profile.modelRevisionId,
                                    "actualRevisionId" to actual.modelRevisionIdActual,
                                    "fallbackPolicy" to fallbackPolicy.name,
                                    "reason" to "revision_not_on_allowlist",
                                ),
                            ),
                        )
                    }
                    deviations += "cross_revision_allowlist:${actual.modelRevisionIdActual}"
                }
            }
        }

        // Backend change under NONE is rejected; under SAME_REVISION_ONLY disclosed.
        if (actual.backendActual != profile.backend) {
            when (fallbackPolicy) {
                FallbackPolicy.NONE -> {
                    return RoutingCheck.Fail(
                        OmniError.INVALID_REQUEST(
                            message = "backend fallback denied under FallbackPolicy.NONE",
                            details = mapOf(
                                "requestedBackend" to profile.backend,
                                "actualBackend" to actual.backendActual,
                                "fallbackPolicy" to fallbackPolicy.name,
                            ),
                        ),
                    )
                }
                FallbackPolicy.SAME_REVISION_ONLY, FallbackPolicy.ALLOW_LIST -> {
                    deviations += "backend_changed:${actual.backendActual}"
                }
            }
        }

        if (actual.engineBuildIdActual != profile.engineBuildId) {
            deviations += "engine_build_changed:${actual.engineBuildIdActual}"
        }

        if (actual.driverResetDetected) {
            deviations += "driver_reset"
        }

        if (actual.backgroundRestriction) {
            deviations += "background_restriction"
        }

        return if (deviations.isEmpty()) {
            RoutingCheck.Ok(emptyList())
        } else {
            RoutingCheck.Degraded(deviations)
        }
    }

    /**
     * Plan-time check: reject unknown FallbackPolicy strings (INV-018).
     */
    fun parseFallbackPolicy(name: String?): OmniError? {
        if (name.isNullOrBlank()) return null
        return if (FallbackPolicy.fromCatalogName(name) == null) {
            OmniError.INVALID_REQUEST(
                message = "unknown FallbackPolicy (fail closed)",
                details = mapOf("fallbackPolicy" to name),
            )
        } else {
            null
        }
    }

    sealed class RoutingCheck {
        data class Ok(val deviations: List<String>) : RoutingCheck()
        data class Degraded(val deviations: List<String>) : RoutingCheck()
        data class Fail(val error: OmniError) : RoutingCheck()
    }
}
