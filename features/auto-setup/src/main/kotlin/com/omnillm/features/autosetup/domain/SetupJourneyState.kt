package com.omnillm.features.autosetup.domain

import com.omnillm.core.errors.generated.OmniError

/**
 * UI journey phase labels for FEAT-AUTOSETUP (FEATURE-SYSTEM: feature-scoped
 * projection, not a new domain aggregate FSM).
 *
 * Underlying durable state remains JOB / MODEL_INSTALLATION / REQUEST /
 * LOADED_MODEL from `specs/state-machines.yaml`.
 */
object SetupJourneyPhases {
    const val IDLE: String = "IDLE"
    const val DISCOVERING_DEVICE: String = "DISCOVERING_DEVICE"
    const val DEVICE_READY: String = "DEVICE_READY"
    const val RECOMMENDING: String = "RECOMMENDING"
    const val RECOMMENDATION_READY: String = "RECOMMENDATION_READY"
    const val AWAITING_SOURCE_SELECTION: String = "AWAITING_SOURCE_SELECTION"
    const val ACQUIRING: String = "ACQUIRING"
    const val INSTALLING: String = "INSTALLING"
    const val CONFIGURING: String = "CONFIGURING"
    const val PLANNING_LOAD: String = "PLANNING_LOAD"
    const val FIRST_INFERENCE: String = "FIRST_INFERENCE"
    const val COMPLETED: String = "COMPLETED"
    const val FAILED: String = "FAILED"
    const val CANCELLED: String = "CANCELLED"

    val ALL: Set<String> = setOf(
        IDLE,
        DISCOVERING_DEVICE,
        DEVICE_READY,
        RECOMMENDING,
        RECOMMENDATION_READY,
        AWAITING_SOURCE_SELECTION,
        ACQUIRING,
        INSTALLING,
        CONFIGURING,
        PLANNING_LOAD,
        FIRST_INFERENCE,
        COMPLETED,
        FAILED,
        CANCELLED,
    )

    fun isKnown(phase: String): Boolean = phase in ALL

    fun isTerminal(phase: String): Boolean =
        phase == COMPLETED || phase == FAILED || phase == CANCELLED
}

/**
 * Full journey projection for UI / Admin (FEAT-AUTOSETUP + UX-JOURNEYS §1).
 * Client-generated ids live on the request/job claim path (ADR-004/005).
 */
data class SetupJourneySnapshot(
    val phase: String,
    val device: DeviceDiscoverySnapshot? = null,
    val recommendation: RecommendationResult? = null,
    val selectedCandidateId: String? = null,
    val configuration: AutomatedConfiguration? = null,
    /** JOB aggregate state when acquisition is in flight (state-machines.yaml#JOB). */
    val jobId: String? = null,
    val jobState: String? = null,
    /** MODEL_INSTALLATION aggregate state when known. */
    val installationId: String? = null,
    val installationState: String? = null,
    /** REQUEST aggregate state for first inference when known. */
    val requestId: String? = null,
    val requestState: String? = null,
    val progressRatio: Double? = null,
    val progressPhase: String? = null,
    val error: OmniError? = null,
    val reasonCodes: List<RecommendationReason> = emptyList(),
    val updatedAtEpochMs: Long,
) {
    init {
        require(SetupJourneyPhases.isKnown(phase)) {
            "unknown setup journey phase (fail closed): $phase"
        }
        require(updatedAtEpochMs >= 0L) { "updatedAtEpochMs must be non-negative" }
        progressRatio?.let { require(it in 0.0..1.0) { "progressRatio must be 0..1" } }
    }

    val isTerminal: Boolean get() = SetupJourneyPhases.isTerminal(phase)
}
