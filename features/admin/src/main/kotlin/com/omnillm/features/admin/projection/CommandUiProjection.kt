package com.omnillm.features.admin.projection

import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.admin.model.CommandStatusUi
import com.omnillm.interfaces.admin.AdminCommandResult

/**
 * Projects durable Command ledger / COMMAND FSM outcomes to UI (FEAT-ADMIN §2).
 *
 * Reply-loss path: client shows RECONCILING-equivalent and offers [CommandUiAction.QUERY_STATUS]
 * — never blind replay (ADR-004/005).
 */
object CommandUiProjection {

    val MACHINE = StateMachines.COMMAND

    fun project(result: AdminCommandResult): CommandStatusUi {
        val state = result.state
        val known = MACHINE.isKnownState(state) || state in LEDGER_ALIASES
        val labelKey = labelKeyFor(state)
        val severity = severityFor(state, result.error != null)
        return CommandStatusUi(
            commandId = result.commandId,
            state = state,
            labelKey = labelKey,
            severity = severity,
            isTerminal = result.isTerminal || state in MACHINE.terminal,
            isSuccess = result.isSuccess,
            resourceVersion = result.resourceVersion,
            affectedResourceId = result.affectedResourceId,
            allowedActions = allowedActions(state, known),
            error = result.error,
            resultCanonicalJson = result.resultCanonicalJson,
        )
    }

    fun allowedActions(state: String, known: Boolean = true): List<CommandUiAction> {
        if (!known && state !in LEDGER_ALIASES) return listOf(CommandUiAction.VIEW_DETAILS)
        return when (state) {
            "RECEIVED", "CLAIMED", "RUNNING" -> listOf(
                CommandUiAction.WAIT,
                CommandUiAction.QUERY_STATUS,
            )
            "RECONCILING" -> listOf(
                CommandUiAction.QUERY_STATUS,
                CommandUiAction.WAIT,
            )
            "SUCCEEDED" -> listOf(CommandUiAction.VIEW_DETAILS)
            "FAILED", "CANCELLED", "UNCERTAIN" -> listOf(
                CommandUiAction.VIEW_DETAILS,
                CommandUiAction.QUERY_STATUS,
            )
            else -> listOf(CommandUiAction.VIEW_DETAILS)
        }
    }

    private fun labelKeyFor(state: String): String = when (state) {
        "RECEIVED" -> "command.received"
        "CLAIMED" -> "command.claimed"
        "RUNNING" -> "command.running"
        "RECONCILING" -> "command.reconciling"
        "SUCCEEDED" -> "command.succeeded"
        "FAILED" -> "command.failed"
        "CANCELLED" -> "command.cancelled"
        "UNCERTAIN" -> "command.uncertain"
        else -> "command.unknown-state"
    }

    private fun severityFor(state: String, hasError: Boolean): UiSeverity = when (state) {
        "SUCCEEDED" -> UiSeverity.INFO
        "FAILED" -> UiSeverity.ERROR
        "CANCELLED", "UNCERTAIN", "RECONCILING" -> UiSeverity.WARNING
        "RECEIVED", "CLAIMED", "RUNNING" -> if (hasError) UiSeverity.WARNING else UiSeverity.INFO
        else -> UiSeverity.ERROR
    }

    /**
     * Command ledger durable states used by [com.omnillm.interfaces.admin.AdminApiService]
     * align with COMMAND terminal set; intermediate CLAIMED/RUNNING may appear mid-recovery.
     */
    private val LEDGER_ALIASES: Set<String> = setOf(
        "RECEIVED",
        "CLAIMED",
        "RUNNING",
        "RECONCILING",
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
        "UNCERTAIN",
    )
}

enum class CommandUiAction {
    WAIT,
    QUERY_STATUS,
    VIEW_DETAILS,
}
