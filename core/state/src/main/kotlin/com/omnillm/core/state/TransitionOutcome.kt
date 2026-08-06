package com.omnillm.core.state

import com.omnillm.core.state.generated.FsmTransition

/**
 * Pure FSM transition result: either a taken edge with new state, or a typed rejection.
 * No domain mutation occurs here (ADR-002 Plan purity; runtime applies actions).
 */
sealed class TransitionOutcome {
    abstract val machineId: String
    abstract val from: String
    abstract val event: String

    /** Catalog edge accepted; [to] is the next durable state candidate. */
    data class Accepted(
        override val machineId: String,
        override val from: String,
        override val event: String,
        val to: String,
        val transitionId: String,
        val guard: String,
        val actions: List<String>,
    ) : TransitionOutcome() {
        val transition: FsmTransition
            get() = FsmTransition(
                id = transitionId,
                from = from,
                event = event,
                to = to,
                guard = guard,
                actions = actions,
            )

        companion object {
            fun from(machineId: String, transition: FsmTransition): Accepted =
                Accepted(
                    machineId = machineId,
                    from = transition.from,
                    event = transition.event,
                    to = transition.to,
                    transitionId = transition.id,
                    guard = transition.guard,
                    actions = transition.actions,
                )
        }
    }

    /** Typed rejection — fail closed (INV-018). */
    sealed class Rejected : TransitionOutcome() {
        abstract val reason: String

        data class UnknownState(
            override val machineId: String,
            override val from: String,
            override val event: String,
        ) : Rejected() {
            override val reason: String = "unknown state"
        }

        data class TerminalHasNoOutbound(
            override val machineId: String,
            override val from: String,
            override val event: String,
        ) : Rejected() {
            override val reason: String = "terminal state has no outbound transitions"
        }

        data class NoTransition(
            override val machineId: String,
            override val from: String,
            override val event: String,
        ) : Rejected() {
            override val reason: String = "no transition for event"
        }

        data class GuardFailed(
            override val machineId: String,
            override val from: String,
            override val event: String,
            val candidates: List<FsmTransition>,
        ) : Rejected() {
            override val reason: String = "guard not satisfied"
        }

        /**
         * Multiple catalog edges share (from, event) and more than one guard is true,
         * or the structural step is ambiguous without a resolving guard evaluator.
         */
        data class Ambiguous(
            override val machineId: String,
            override val from: String,
            override val event: String,
            val candidates: List<FsmTransition>,
        ) : Rejected() {
            override val reason: String = "ambiguous transitions require exclusive guard"
        }
    }
}

/**
 * Aggregate-level apply result: new aggregate snapshot on success, typed rejection otherwise.
 */
sealed class AggregateTransitionResult<out A> {
    data class Success<A>(
        val aggregate: A,
        val outcome: TransitionOutcome.Accepted,
    ) : AggregateTransitionResult<A>()

    data class Rejected(
        val rejection: TransitionOutcome.Rejected,
    ) : AggregateTransitionResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isRejected: Boolean get() = this is Rejected

    fun getOrNull(): A? = when (this) {
        is Success -> aggregate
        is Rejected -> null
    }

    fun rejectionOrNull(): TransitionOutcome.Rejected? = when (this) {
        is Success -> null
        is Rejected -> rejection
    }
}
