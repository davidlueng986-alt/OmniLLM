package com.omnillm.features.admin.projection

import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.admin.model.JobDetailUi
import com.omnillm.features.admin.model.JobListItemUi
import com.omnillm.runtime.job.JobPauseReason
import com.omnillm.runtime.job.JobRecord

/**
 * Projects canonical JOB FSM state onto UX actions/labels (UX-STATE, FEAT-ADMIN §4–5).
 *
 * Does not invent states — unknown JOB states fail closed to a generic error row
 * with no unsafe actions (INV-018).
 */
object JobUiProjection {

    val MACHINE = StateMachines.JOB

    fun projectListItem(record: JobRecord): JobListItemUi {
        val state = record.state
        val known = MACHINE.isKnownState(state)
        val labelKey = labelKeyFor(state, known)
        val severity = severityFor(state, record.cancelRequested, known)
        val actions = if (known) allowedActions(state, record.cancelRequested) else emptyList()
        return JobListItemUi(
            jobId = record.jobId.value,
            kind = record.kind.name,
            state = state,
            labelKey = labelKey,
            severity = severity,
            progressRatio = record.progress.ratioOrNull(),
            currentPhase = record.progress.currentPhase,
            attemptNo = record.currentAttemptNo,
            cancelRequested = record.cancelRequested,
            pauseReason = record.pauseReason?.name,
            allowedActions = actions,
            errorCode = record.error?.code?.code,
            updatedAtEpochMs = record.updatedAtEpochMs,
        )
    }

    fun projectDetail(record: JobRecord): JobDetailUi {
        val list = projectListItem(record)
        return JobDetailUi(
            list = list,
            resourceVersion = record.resourceVersion,
            networkBytes = record.progress.networkBytes,
            materializedBytes = record.progress.materializedBytes,
            verifiedBytes = record.progress.verifiedBytes,
            totalBytesKnown = record.progress.totalBytesKnown,
            estimatedRemainingMs = record.progress.estimatedRemainingMs,
            checkpointResumeCursor = record.checkpoint?.resumeCursor,
            eventCount = record.events.size,
            blockedReasonLabelKey = blockedReasonLabel(record),
        )
    }

    fun isTerminal(state: String): Boolean = state in MACHINE.terminal

    fun isCancellable(state: String, cancelRequested: Boolean): Boolean {
        if (!MACHINE.isKnownState(state)) return false
        if (isTerminal(state)) return false
        if (cancelRequested && state == "RUNNING") return false // already requested
        return state in CANCELLABLE_STATES
    }

    fun isPaused(state: String): Boolean = JobPauseReason.fromState(state) != null

    fun isRecovering(state: String): Boolean = state == "RECOVERING"

    /**
     * Actions legal for the current JOB state (UX-STATE §5: only when machine allows).
     * Cancel remains available on paused / recovering (FEAT-ADMIN acceptance §3).
     */
    fun allowedActions(state: String, cancelRequested: Boolean): List<JobUiAction> {
        if (!MACHINE.isKnownState(state) || isTerminal(state)) {
            return if (isTerminal(state)) listOf(JobUiAction.VIEW_DETAILS) else emptyList()
        }
        val actions = mutableListOf<JobUiAction>()
        actions += JobUiAction.VIEW_DETAILS
        when (state) {
            "QUEUED" -> {
                actions += JobUiAction.CANCEL
            }
            "RUNNING" -> {
                if (cancelRequested) {
                    actions += JobUiAction.WAIT_SAFE_STOP
                } else {
                    actions += JobUiAction.CANCEL
                }
            }
            "PAUSED_WAITING_INPUT" -> {
                actions += JobUiAction.PROVIDE_INPUT
                actions += JobUiAction.CANCEL
            }
            "PAUSED_WAITING_NETWORK" -> {
                actions += JobUiAction.WAIT_NETWORK
                actions += JobUiAction.CANCEL
            }
            "PAUSED_WAITING_FOREGROUND" -> {
                actions += JobUiAction.BRING_TO_FOREGROUND
                actions += JobUiAction.CANCEL
            }
            "RECOVERING" -> {
                actions += JobUiAction.WAIT_RECOVERY
                actions += JobUiAction.CANCEL
            }
        }
        return actions
    }

    private fun labelKeyFor(state: String, known: Boolean): String =
        if (!known) {
            "job.unknown-state"
        } else {
            when (state) {
                "QUEUED" -> "job.queued"
                "RUNNING" -> "job.running"
                "PAUSED_WAITING_INPUT" -> "job.paused-waiting-input"
                "PAUSED_WAITING_NETWORK" -> "job.paused-waiting-network"
                "PAUSED_WAITING_FOREGROUND" -> "job.paused-waiting-foreground"
                "RECOVERING" -> "job.recovering"
                "SUCCEEDED" -> "job.succeeded"
                "FAILED" -> "job.failed"
                "CANCELLED" -> "job.cancelled"
                else -> "job.unknown-state"
            }
        }

    private fun severityFor(
        state: String,
        cancelRequested: Boolean,
        known: Boolean,
    ): UiSeverity {
        if (!known) return UiSeverity.ERROR
        return when (state) {
            "QUEUED", "RUNNING" -> if (cancelRequested) UiSeverity.WARNING else UiSeverity.INFO
            "PAUSED_WAITING_INPUT",
            "PAUSED_WAITING_NETWORK",
            "PAUSED_WAITING_FOREGROUND",
            "RECOVERING",
            -> UiSeverity.WARNING
            "SUCCEEDED" -> UiSeverity.INFO
            "FAILED" -> UiSeverity.ERROR
            "CANCELLED" -> UiSeverity.WARNING
            else -> UiSeverity.ERROR
        }
    }

    private fun blockedReasonLabel(record: JobRecord): String? =
        when (record.pauseReason) {
            JobPauseReason.WAITING_INPUT -> "job.blocked.waiting-input"
            JobPauseReason.WAITING_NETWORK -> "job.blocked.waiting-network"
            JobPauseReason.WAITING_FOREGROUND -> "job.blocked.waiting-foreground"
            null -> if (record.state == "RECOVERING") "job.blocked.recovering" else null
        }

    private val CANCELLABLE_STATES: Set<String> = setOf(
        "QUEUED",
        "RUNNING",
        "PAUSED_WAITING_INPUT",
        "PAUSED_WAITING_NETWORK",
        "PAUSED_WAITING_FOREGROUND",
        "RECOVERING",
    )
}

/** UX action keys for JOB (not domain events). */
enum class JobUiAction {
    VIEW_DETAILS,
    CANCEL,
    WAIT_SAFE_STOP,
    PROVIDE_INPUT,
    WAIT_NETWORK,
    BRING_TO_FOREGROUND,
    WAIT_RECOVERY,
}

enum class UiSeverity {
    INFO,
    WARNING,
    ERROR,
}
