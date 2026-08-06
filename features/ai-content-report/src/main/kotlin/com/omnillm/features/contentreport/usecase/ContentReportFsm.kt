package com.omnillm.features.contentreport.usecase

import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines

/**
 * Thin wrapper over catalog CONTENT_REPORT FSM (specs/state-machines.yaml).
 * Fail closed on illegal edges / unsatisfied guards.
 */
object ContentReportFsm {
    const val MACHINE_ID: String = "CONTENT_REPORT"

    private val machine = StateMachines.CONTENT_REPORT

    fun transition(
        from: ContentReportState,
        event: String,
        guards: GuardEvaluator,
    ): TransitionOutcome =
        StateMachineDriver.transition(machine, from.name, event, guards)

    fun mustTransition(
        from: ContentReportState,
        event: String,
        guards: GuardEvaluator,
    ): ContentReportState {
        return when (val outcome = transition(from, event, guards)) {
            is TransitionOutcome.Accepted ->
                ContentReportState.requireFromCatalogName(outcome.to)
            is TransitionOutcome.Rejected ->
                error(
                    "illegal CONTENT_REPORT transition (fail closed): " +
                        "${from.name} + $event → ${outcome::class.simpleName}",
                )
        }
    }

    fun tryTransition(
        from: ContentReportState,
        event: String,
        guards: GuardEvaluator,
    ): ContentReportState? =
        when (val outcome = transition(from, event, guards)) {
            is TransitionOutcome.Accepted ->
                ContentReportState.fromCatalogName(outcome.to)
            is TransitionOutcome.Rejected -> null
        }

    fun isKnownState(name: String): Boolean = machine.isKnownState(name)

    fun isTerminal(state: ContentReportState): Boolean =
        machine.isTerminal(state.name)
}
