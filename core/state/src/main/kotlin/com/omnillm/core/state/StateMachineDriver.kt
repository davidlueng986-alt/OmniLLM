package com.omnillm.core.state

import com.omnillm.core.state.generated.FsmMachineDefinition
import com.omnillm.core.state.generated.FsmStepResult
import com.omnillm.core.state.generated.FsmTransition
import com.omnillm.core.state.generated.StateMachines

/**
 * Pure state-machine driver over catalog definitions from [StateMachines].
 *
 * Responsibilities:
 * - structural edge lookup (fail closed on unknown / terminal / missing edge)
 * - catalog guard expression evaluation via [GuardEvaluator]
 * - never mutates domain storage (Plan has no domain mutation; runtime commits)
 *
 * Minimum required machines (DATA-STATES / task scope): REQUEST, COMMIT,
 * RESERVATION, SESSION, MODEL_INSTALLATION, LOADED_MODEL, JOB, ASSET,
 * OPERATION, RUNTIME.
 */
object StateMachineDriver {

    /** Catalog IDs that must always be present for portable domain drivers. */
    val REQUIRED_MACHINE_IDS: Set<String> = setOf(
        "REQUEST",
        "COMMIT",
        "RESERVATION",
        "SESSION",
        "MODEL_INSTALLATION",
        "LOADED_MODEL",
        "JOB",
        "ASSET",
        "OPERATION",
        "RUNTIME",
    )

    fun machine(id: String): FsmMachineDefinition = StateMachines.require(id)

    fun requiredMachines(): List<FsmMachineDefinition> =
        REQUIRED_MACHINE_IDS.map { StateMachines.require(it) }

    /**
     * Pure transition: returns [TransitionOutcome.Accepted] with new state,
     * or a typed [TransitionOutcome.Rejected] (no illegal edges).
     *
     * When multiple catalog transitions share `(from, event)`, exactly one
     * candidate must satisfy its guard; zero ⇒ [GuardFailed], many ⇒ [Ambiguous].
     */
    fun transition(
        machine: FsmMachineDefinition,
        from: String,
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): TransitionOutcome {
        when (val structural = machine.step(from, event)) {
            is FsmStepResult.Illegal -> {
                return mapIllegal(structural)
            }
            is FsmStepResult.Taken -> {
                val tr = structural.transition
                return if (GuardExpression.evaluate(tr.guard, guards)) {
                    TransitionOutcome.Accepted.from(machine.id, tr)
                } else {
                    TransitionOutcome.Rejected.GuardFailed(
                        machineId = machine.id,
                        from = from,
                        event = event,
                        candidates = listOf(tr),
                    )
                }
            }
            is FsmStepResult.Ambiguous -> {
                return resolveAmbiguous(machine.id, from, event, structural.candidates, guards)
            }
        }
    }

    fun transition(
        machineId: String,
        from: String,
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): TransitionOutcome = transition(StateMachines.require(machineId), from, event, guards)

    /**
     * Force a specific catalog transition id (e.g. property tests of every edge).
     * Still verifies [from]/[event] match and evaluates the edge guard.
     */
    fun transitionById(
        machine: FsmMachineDefinition,
        transitionId: String,
        from: String,
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): TransitionOutcome {
        if (!machine.isKnownState(from)) {
            return TransitionOutcome.Rejected.UnknownState(machine.id, from, event)
        }
        if (machine.isTerminal(from)) {
            return TransitionOutcome.Rejected.TerminalHasNoOutbound(machine.id, from, event)
        }
        val edge = machine.transitions.firstOrNull {
            it.id == transitionId && it.from == from && it.event == event
        } ?: return TransitionOutcome.Rejected.NoTransition(machine.id, from, event)
        return if (GuardExpression.evaluate(edge.guard, guards)) {
            TransitionOutcome.Accepted.from(machine.id, edge)
        } else {
            TransitionOutcome.Rejected.GuardFailed(
                machineId = machine.id,
                from = from,
                event = event,
                candidates = listOf(edge),
            )
        }
    }

    /** All distinct event names appearing on catalog edges of [machine]. */
    fun catalogEvents(machine: FsmMachineDefinition): Set<String> =
        machine.transitions.map { it.event }.toSet()

    /** All legal (from, event) pairs (structural; guards ignored). */
    fun legalEdges(machine: FsmMachineDefinition): Set<Pair<String, String>> =
        machine.transitions.map { it.from to it.event }.toSet()

    /** Whether [state] is terminal for [machine] (empty terminal set ⇒ never). */
    fun isTerminal(machine: FsmMachineDefinition, state: String): Boolean =
        machine.isTerminal(state)

    private fun mapIllegal(illegal: FsmStepResult.Illegal): TransitionOutcome.Rejected =
        when (illegal.reason) {
            "unknown state" ->
                TransitionOutcome.Rejected.UnknownState(
                    illegal.machineId,
                    illegal.from,
                    illegal.event,
                )
            "terminal state has no outbound transitions" ->
                TransitionOutcome.Rejected.TerminalHasNoOutbound(
                    illegal.machineId,
                    illegal.from,
                    illegal.event,
                )
            else ->
                TransitionOutcome.Rejected.NoTransition(
                    illegal.machineId,
                    illegal.from,
                    illegal.event,
                )
        }

    private fun resolveAmbiguous(
        machineId: String,
        from: String,
        event: String,
        candidates: List<FsmTransition>,
        guards: GuardEvaluator,
    ): TransitionOutcome {
        val satisfied = candidates.filter { GuardExpression.evaluate(it.guard, guards) }
        return when (satisfied.size) {
            0 -> TransitionOutcome.Rejected.GuardFailed(
                machineId = machineId,
                from = from,
                event = event,
                candidates = candidates,
            )
            1 -> TransitionOutcome.Accepted.from(machineId, satisfied.single())
            else -> TransitionOutcome.Rejected.Ambiguous(
                machineId = machineId,
                from = from,
                event = event,
                candidates = satisfied,
            )
        }
    }
}
