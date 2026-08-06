package com.omnillm.core.resource

/**
 * Non-additive operating limits separated from [com.omnillm.core.canonical.generated.ResourceVector]
 * (CORE-RESOURCE §1).
 *
 * Thermal, power, foreground requirements, network conditions, and maximum continuous
 * compute time must **not** be mixed into ResourceVector dimensions or summed with bytes.
 * Engines publish both a ResourceVector envelope and an OperatingConstraint; admission
 * fails closed when either is violated.
 *
 * Fields are optional so platforms can omit unobservable dimensions without inventing
 * zero-default additive charges.
 */
data class OperatingConstraint(
    /** Preferred maximum continuous compute duration in milliseconds (monotonic). */
    val maxContinuousComputeMs: Long? = null,
    /** Thermal class label from platform policy (opaque versioned string). */
    val thermalClass: String? = null,
    /** Power class / battery policy label (opaque versioned string). */
    val powerClass: String? = null,
    /** When true, operation requires a foreground-capable runtime. */
    val requiresForeground: Boolean = false,
    /** Network readiness requirement (opaque versioned string; not a byte charge). */
    val networkCondition: String? = null,
    /** Engine/backend preference under constraint pressure (opaque). */
    val preferredBackend: String? = null,
    /** Policy version that produced this constraint snapshot. */
    val policyVersion: String? = null,
) {
    init {
        maxContinuousComputeMs?.let {
            require(it >= 0L) { "maxContinuousComputeMs must be non-negative" }
        }
        thermalClass?.let { require(it.isNotEmpty()) { "thermalClass must be non-empty when set" } }
        powerClass?.let { require(it.isNotEmpty()) { "powerClass must be non-empty when set" } }
        networkCondition?.let {
            require(it.isNotEmpty()) { "networkCondition must be non-empty when set" }
        }
        preferredBackend?.let {
            require(it.isNotEmpty()) { "preferredBackend must be non-empty when set" }
        }
    }

    companion object {
        val NONE: OperatingConstraint = OperatingConstraint()
    }
}
