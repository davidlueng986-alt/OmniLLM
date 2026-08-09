package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.RequestAggregate
import com.omnillm.core.state.domain.RequestId as DomainRequestId
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.runtime.requestregistry.RequestRegistry

/**
 * REQUEST FSM driver bound to Request Registry persistence
 * (CORE-ORCHESTRATOR §1, specs/state-machines.yaml#REQUEST).
 *
 * Transitions fail closed on illegal edges (INV-018).
 * Terminal states go through [RequestRegistry.recordTerminal].
 */
class RequestLifecycle(
    private val registry: RequestRegistry,
) {
    private val aggregates = linkedMapOf<String, RequestAggregate>()
    private val lock = Any()

    fun currentState(requestId: RequestId): String? {
        registry.queryRequest(requestId)?.let { return it.state }
        synchronized(lock) { return aggregates[requestId.value]?.state }
    }

    fun getAggregate(requestId: RequestId): RequestAggregate? =
        synchronized(lock) { aggregates[requestId.value] }

    /**
     * Attach or refresh aggregate from durable claim row (RECEIVED after claim).
     */
    fun bindFromClaim(
        requestId: RequestId,
        principalId: String,
        state: String = StateMachines.REQUEST.initial,
    ): RequestAggregate = synchronized(lock) {
        val agg = RequestAggregate(
            requestId = DomainRequestId(requestId.value),
            ownerKey = OwnerKey(principalId),
            state = state,
        )
        aggregates[requestId.value] = agg
        agg
    }

    /**
     * Apply a catalog event and persist non-terminal state via registry.
     * Terminal events must use [terminal].
     */
    fun apply(
        requestId: RequestId,
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): OmniResult<RequestAggregate> = synchronized(lock) {
        val current = aggregates[requestId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request aggregate not bound",
                    details = mapOf("requestId" to requestId.value),
                ),
            )

        when (val result = current.apply(event, guards)) {
            is com.omnillm.core.state.AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "REQUEST transition rejected: ${result.rejection.reason}",
                        details = mapOf(
                            "from" to current.state,
                            "event" to event,
                            "reason" to result.rejection.reason,
                        ),
                    ),
                )
            is com.omnillm.core.state.AggregateTransitionResult.Success -> {
                val next = result.aggregate
                if (StateMachines.REQUEST.isTerminal(next.state)) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = "use terminal() for terminal states",
                            details = mapOf("state" to next.state),
                        ),
                    )
                }
                val persisted = registry.updateState(requestId, next.state)
                if (persisted is OmniResult.Err) return persisted
                aggregates[requestId.value] = next
                return OmniResult.ok(next)
            }
        }
    }

    /**
     * Drive to a terminal REQUEST state and record durable terminal.
     */
    fun terminal(
        requestId: RequestId,
        event: String,
        terminalSeq: Long,
        outputDigest: com.omnillm.core.canonical.generated.Sha256Digest? = null,
        errorCode: String? = null,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): OmniResult<RequestAggregate> = synchronized(lock) {
        val current = aggregates[requestId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request aggregate not bound",
                    details = mapOf("requestId" to requestId.value),
                ),
            )

        when (val result = current.apply(event, guards)) {
            is com.omnillm.core.state.AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "REQUEST terminal transition rejected: ${result.rejection.reason}",
                        details = mapOf(
                            "from" to current.state,
                            "event" to event,
                        ),
                    ),
                )
            is com.omnillm.core.state.AggregateTransitionResult.Success -> {
                val next = result.aggregate
                if (!StateMachines.REQUEST.isTerminal(next.state)) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "event did not reach a terminal state",
                            details = mapOf("state" to next.state, "event" to event),
                        ),
                    )
                }
                val recorded = registry.recordTerminal(
                    requestId = requestId,
                    terminalState = next.state,
                    terminalSeq = terminalSeq,
                    outputDigest = outputDigest,
                    errorCode = errorCode,
                )
                if (recorded is OmniResult.Err) return recorded
                // COR-19: evict terminal aggregates — durable in the registry,
                // keeping this map bounded to non-terminal requests only.
                aggregates.remove(requestId.value)
                return OmniResult.ok(next)
            }
        }
    }

    /** Structural probe used by tests. */
    fun canTransition(from: String, event: String): Boolean {
        val outcome = com.omnillm.core.state.StateMachineDriver.transition(
            StateMachines.REQUEST,
            from,
            event,
            GuardEvaluator.ALWAYS_TRUE,
        )
        return outcome is TransitionOutcome.Accepted
    }
}
