package com.omnillm.features.autosetup.projection

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.autosetup.domain.AutomatedConfiguration
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.RecommendationReason
import com.omnillm.features.autosetup.domain.RecommendationResult
import com.omnillm.features.autosetup.domain.SetupJourneyPhases
import com.omnillm.features.autosetup.domain.SetupJourneySnapshot
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord

/**
 * Projects canonical FSMs (JOB / MODEL_INSTALLATION / REQUEST) into
 * [SetupJourneySnapshot] for UI / Admin (FEAT-AUTOSETUP, UX-PROJECTION).
 *
 * Does not invent domain states — only maps known catalog states.
 */
object AutoSetupStateProjection {

    private val JOB_STATES: Set<String> = StateMachines.JOB.states
    private val INSTALLATION_STATES: Set<String> = StateMachines.MODEL_INSTALLATION.states
    private val REQUEST_STATES: Set<String> = StateMachines.REQUEST.states

    /**
     * Map durable control-plane facts into a journey phase.
     * Terminal job cancel/fail and request terminal override intermediate phases.
     */
    fun project(
        previousPhase: String,
        device: DeviceDiscoverySnapshot?,
        recommendation: RecommendationResult?,
        selectedCandidateId: String?,
        configuration: AutomatedConfiguration?,
        job: JobRecord?,
        installationState: String?,
        installationId: String?,
        requestId: String?,
        requestState: String?,
        cancelRequested: Boolean,
        error: OmniError?,
        reasonCodes: List<RecommendationReason> = emptyList(),
        nowEpochMs: Long,
    ): SetupJourneySnapshot {
        require(SetupJourneyPhases.isKnown(previousPhase)) {
            "unknown previous phase: $previousPhase"
        }
        installationState?.let {
            require(it in INSTALLATION_STATES) {
                "unknown MODEL_INSTALLATION state (fail closed): $it"
            }
        }
        job?.let {
            require(it.state in JOB_STATES) {
                "unknown JOB state (fail closed): ${it.state}"
            }
        }
        requestState?.let {
            require(it in REQUEST_STATES) {
                "unknown REQUEST state (fail closed): $it"
            }
        }

        val phase = resolvePhase(
            previousPhase = previousPhase,
            device = device,
            recommendation = recommendation,
            selectedCandidateId = selectedCandidateId,
            configuration = configuration,
            job = job,
            installationState = installationState,
            requestState = requestState,
            cancelRequested = cancelRequested,
            error = error,
        )

        val progress = job?.progress
        return SetupJourneySnapshot(
            phase = phase,
            device = device,
            recommendation = recommendation,
            selectedCandidateId = selectedCandidateId,
            configuration = configuration,
            jobId = job?.jobId?.value,
            jobState = job?.state,
            installationId = installationId,
            installationState = installationState,
            requestId = requestId,
            requestState = requestState,
            progressRatio = progress?.ratioOrNull(),
            progressPhase = progress?.currentPhase,
            error = error ?: job?.error,
            reasonCodes = reasonCodes,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    /**
     * UX labels for JOB states (ux-projection-catalog aligned where present).
     */
    fun jobStateLabelKey(jobState: String): String = when (jobState) {
        "QUEUED" -> "job.queued"
        "RUNNING" -> "job.running"
        "PAUSED_WAITING_INPUT" -> "job.waiting-input"
        "PAUSED_WAITING_NETWORK" -> "job.waiting-network"
        "PAUSED_WAITING_FOREGROUND" -> "job.waiting-foreground"
        "RECOVERING" -> "job.recovering"
        "SUCCEEDED" -> "job.succeeded"
        "FAILED" -> "job.failed"
        "CANCELLED" -> "job.cancelled"
        else -> "job.unknown"
    }

    fun installationStateLabelKey(state: String): String = when (state) {
        "DISCOVERED" -> "installation.discovered"
        "ACQUIRING" -> "installation.acquiring"
        "QUARANTINED" -> "installation.quarantined"
        "VERIFYING" -> "installation.verifying"
        "COMPATIBILITY_CHECK" -> "installation.compatibility"
        "READY" -> "installation.ready"
        "DRAINING" -> "installation.draining"
        "REVOKED" -> "installation.revoked"
        "CORRUPT" -> "installation.corrupt"
        "REJECTED" -> "installation.rejected"
        "DELETING" -> "installation.deleting"
        "DELETED" -> "installation.deleted"
        else -> "installation.unknown"
    }

    fun requestStateLabelKey(state: String): String = when (state) {
        "QUEUED" -> "request.queued"
        "RESERVED" -> "request.preparing"
        "STREAMING" -> "request.generating"
        "RECONCILING" -> "request.reconciling"
        "COMPLETED" -> "request.completed"
        "FAILED" -> "request.failed"
        "CANCELLED" -> "request.cancelled"
        "ABORTED_UNCERTAIN" -> "request.uncertain"
        else -> "request.$state".lowercase()
    }

    // ------------------------------------------------------------------

    private fun resolvePhase(
        previousPhase: String,
        device: DeviceDiscoverySnapshot?,
        recommendation: RecommendationResult?,
        selectedCandidateId: String?,
        configuration: AutomatedConfiguration?,
        job: JobRecord?,
        installationState: String?,
        requestState: String?,
        cancelRequested: Boolean,
        error: OmniError?,
    ): String {
        // Sticky terminal journey phases until reset (unless durable work reopens).
        if (previousPhase == SetupJourneyPhases.COMPLETED &&
            requestState in terminalSuccessRequest()
        ) {
            return SetupJourneyPhases.COMPLETED
        }
        if (previousPhase == SetupJourneyPhases.CANCELLED &&
            (job?.state == "CANCELLED" || requestState == "CANCELLED" || cancelRequested)
        ) {
            return SetupJourneyPhases.CANCELLED
        }
        if (previousPhase == SetupJourneyPhases.FAILED && error != null &&
            job?.state != "RUNNING" && requestState !in activeRequestStates()
        ) {
            return SetupJourneyPhases.FAILED
        }

        if (cancelRequested && job?.state == "CANCELLED") {
            return SetupJourneyPhases.CANCELLED
        }
        if (job?.state == "CANCELLED") {
            return SetupJourneyPhases.CANCELLED
        }
        if (requestState == "CANCELLED") {
            return SetupJourneyPhases.CANCELLED
        }
        if (error != null && error.code.code == "CANCELLED") {
            return SetupJourneyPhases.CANCELLED
        }

        // Request terminal success ends the journey.
        if (requestState in terminalSuccessRequest()) {
            return SetupJourneyPhases.COMPLETED
        }
        if (requestState in terminalFailureRequest()) {
            return SetupJourneyPhases.FAILED
        }

        // Job failure
        if (job?.state == "FAILED") {
            return SetupJourneyPhases.FAILED
        }

        // Installation hard failures
        if (installationState in setOf("REJECTED", "CORRUPT", "REVOKED")) {
            return SetupJourneyPhases.FAILED
        }

        if (error != null) {
            // Any non-cancel error with no successful recovery keeps journey FAILED.
            if (previousPhase != SetupJourneyPhases.COMPLETED) {
                return SetupJourneyPhases.FAILED
            }
        }

        // Active request streaming / reserved
        if (requestState != null && requestState !in terminalRequest()) {
            return when (requestState) {
                "STREAMING", "STARTING", "PREPARED", "COMMITTING", "RESERVED",
                "PLANNING", "QUEUED", "CLAIMED", "RECEIVED",
                -> SetupJourneyPhases.FIRST_INFERENCE
                else -> SetupJourneyPhases.FIRST_INFERENCE
            }
        }

        // Installation progressing after job success / during install
        if (installationState != null && installationState != "READY" &&
            installationState != "DELETED"
        ) {
            return when (installationState) {
                "DISCOVERED", "ACQUIRING", "QUARANTINED" -> SetupJourneyPhases.ACQUIRING
                "VERIFYING", "COMPATIBILITY_CHECK" -> SetupJourneyPhases.INSTALLING
                "DRAINING", "DELETING" -> SetupJourneyPhases.INSTALLING
                else -> SetupJourneyPhases.INSTALLING
            }
        }

        // Job in flight
        if (job != null && !job.isTerminal) {
            return SetupJourneyPhases.ACQUIRING
        }

        // Job succeeded, installation READY → configure / plan load
        if (installationState == "READY" && configuration != null && requestState == null) {
            return if (previousPhase == SetupJourneyPhases.PLANNING_LOAD) {
                SetupJourneyPhases.PLANNING_LOAD
            } else {
                SetupJourneyPhases.CONFIGURING
            }
        }

        if (configuration != null && selectedCandidateId != null && job == null &&
            installationState == null && requestState == null
        ) {
            return SetupJourneyPhases.CONFIGURING
        }

        if (selectedCandidateId != null && recommendation != null && configuration == null) {
            return SetupJourneyPhases.AWAITING_SOURCE_SELECTION
        }

        if (recommendation != null) {
            return SetupJourneyPhases.RECOMMENDATION_READY
        }

        if (device != null) {
            return SetupJourneyPhases.DEVICE_READY
        }

        return when (previousPhase) {
            SetupJourneyPhases.DISCOVERING_DEVICE -> SetupJourneyPhases.DISCOVERING_DEVICE
            SetupJourneyPhases.RECOMMENDING -> SetupJourneyPhases.RECOMMENDING
            else -> SetupJourneyPhases.IDLE
        }
    }

    private fun terminalSuccessRequest(): Set<String> = setOf("COMPLETED")

    private fun terminalFailureRequest(): Set<String> =
        setOf("FAILED", "ABORTED_UNCERTAIN")

    private fun terminalRequest(): Set<String> =
        terminalSuccessRequest() + terminalFailureRequest() + setOf("CANCELLED")

    private fun activeRequestStates(): Set<String> =
        StateMachines.REQUEST.states - terminalRequest()
}

/** Helper for progress phase labels without depending on JobRecord. */
fun JobProgress.displayPhaseOr(default: String): String = currentPhase ?: default
