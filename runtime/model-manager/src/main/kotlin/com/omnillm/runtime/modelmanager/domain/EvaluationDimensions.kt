package com.omnillm.runtime.modelmanager.domain

/**
 * ADR-009 / SEC-PLACEMENT — five independent evaluation dimensions.
 *
 * Dry-load / benchmark / compatibility success must **not** promote authenticity.
 * License acceptance ≠ executable placement. Dimensions are computed and stored
 * separately; UI projects them without collapsing into a single "verified" flag.
 */
data class EvaluationDimensions(
    /** Authenticity: source, signature, digest, revision pin. */
    val authenticityOk: Boolean,
    /** License: terms, accept/revoke events, source scope. */
    val licenseOk: Boolean,
    /** Compatibility: can run on the chosen combination (evidence only). */
    val compatibilityOk: Boolean,
    /** Performance: full-profile results (evidence only; never lowers placement). */
    val performanceRecorded: Boolean,
    /**
     * Placement class label from SEC-PLACEMENT
     * (e.g. PRIVILEGED_TRUSTED, ISOLATED_CPU_UNTRUSTED, TRUST_PLACEMENT_REQUIRED).
     */
    val placementClass: String,
    /** Monotonic trust epoch after recompute (schema installations.trust_epoch). */
    val trustEpoch: Long = 0L,
) {
    init {
        require(placementClass.isNotEmpty()) { "placementClass must be non-empty" }
        require(trustEpoch >= 0L) { "trustEpoch must be non-negative" }
    }

    /**
     * Effective readiness for load planning (not native loaded).
     * Requires authenticity + license; placement must not be fail-closed.
     * Compatibility/performance never gate authenticity.
     */
    fun allowsLoadPlanning(executablePlacement: (String) -> Boolean): Boolean =
        authenticityOk && licenseOk && executablePlacement(placementClass)
}
