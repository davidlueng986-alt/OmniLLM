package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.domain.RuntimeAggregate
import com.omnillm.core.state.domain.RuntimeInstanceId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process owner of the catalog RUNTIME state machine
 * (`specs/state-machines.yaml#RUNTIME` / ARCH-RUNTIME-LIFECYCLE).
 *
 * Pure transitions only — no invented state names. Domain DB durability for
 * `runtime_instances` is a TODO until persistence bootstrap lands; epoch advance
 * is still enforced in memory before capabilities may be issued.
 *
 * **No fixed recovery SLA.** START_STICKY / process restart does not imply a
 * timed recovery guarantee (ARCH-RUNTIME-LIFECYCLE §6).
 */
class RuntimeLifecycleController(
    private val idSource: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val identityRef = AtomicReference(
        RuntimeIdentity(
            runtimeInstanceId = idSource(),
            bootId = idSource(),
            runtimeEpoch = 0L,
        ),
    )
    private var aggregate: RuntimeAggregate =
        RuntimeAggregate.initial(
            runtimeInstanceId = RuntimeInstanceId(identityRef.get().runtimeInstanceId),
            runtimeEpoch = 0L,
        )

    val state: String
        get() = synchronized(lock) { aggregate.state }

    val identity: RuntimeIdentity
        get() = identityRef.get()

    /** States that may accept policy-permitted work (READY or DEGRADED). */
    fun acceptsWork(): Boolean {
        val s = state
        return s == "READY" || s == "DEGRADED"
    }

    /**
     * Advance epoch and attempt LEGAL_START → STARTING.
     *
     * [foregroundLegal] means a platform-allowed start trigger exists (user-visible
     * action, eligible binding, etc.) — not a promise that FGS already started.
     */
    fun legalStart(foregroundLegal: Boolean): LifecycleStepResult = synchronized(lock) {
        if (aggregate.state != "STOPPED") {
            return LifecycleStepResult.Rejected(
                "LEGAL_START only from STOPPED (current=${aggregate.state})",
            )
        }
        val previous = identityRef.get()
        val nextEpoch = previous.runtimeEpoch + 1L
        val nextIdentity = previous.copy(
            bootId = idSource(),
            runtimeEpoch = nextEpoch,
        )
        identityRef.set(nextIdentity)
        aggregate = RuntimeAggregate(
            runtimeInstanceId = RuntimeInstanceId(nextIdentity.runtimeInstanceId),
            runtimeEpoch = nextEpoch,
            state = "STOPPED",
        )
        return applyEvent(
            event = "LEGAL_START",
            guards = GuardEvaluator.of(
                "foregroundLegal" to foregroundLegal,
                "epochAdvanced" to true,
                "recoveryComplete" to false,
            ),
        )
    }

    fun beginRecovery(): LifecycleStepResult = synchronized(lock) {
        applyEvent("BEGIN_RECOVERY", recoveryGuards(complete = false))
    }

    fun recoveryOk(): LifecycleStepResult = synchronized(lock) {
        applyEvent("RECOVERY_OK", recoveryGuards(complete = true))
    }

    fun recoveryPartial(): LifecycleStepResult = synchronized(lock) {
        applyEvent("RECOVERY_PARTIAL", recoveryGuards(complete = false))
    }

    fun recoveryFailed(): LifecycleStepResult = synchronized(lock) {
        applyEvent("RECOVERY_FAILED", recoveryGuards(complete = false))
    }

    fun healthDegraded(): LifecycleStepResult = synchronized(lock) {
        applyEvent("HEALTH_DEGRADED", recoveryGuards(complete = true))
    }

    fun healthRecovered(): LifecycleStepResult = synchronized(lock) {
        applyEvent("HEALTH_RECOVERED", recoveryGuards(complete = true))
    }

    fun stopRequested(): LifecycleStepResult = synchronized(lock) {
        applyEvent("STOP_REQUESTED", recoveryGuards(complete = true))
    }

    fun drainComplete(): LifecycleStepResult = synchronized(lock) {
        applyEvent("DRAIN_COMPLETE", recoveryGuards(complete = true))
    }

    fun foregroundNotAllowed(): LifecycleStepResult = synchronized(lock) {
        applyEvent("FOREGROUND_NOT_ALLOWED", recoveryGuards(complete = false))
    }

    fun userStart(foregroundLegal: Boolean): LifecycleStepResult = synchronized(lock) {
        applyEvent(
            event = "USER_START",
            guards = GuardEvaluator.of(
                "foregroundLegal" to foregroundLegal,
                "epochAdvanced" to true,
                "recoveryComplete" to false,
            ),
        )
    }

    fun resetOrStop(): LifecycleStepResult = synchronized(lock) {
        applyEvent("RESET_OR_STOP", recoveryGuards(complete = false))
    }

    /**
     * Convenience bootstrap used by [RuntimeControlPlane]:
     * STOPPED → STARTING → RECOVERING → READY|DEGRADED|FAULTED.
     */
    fun bootstrapToReady(
        foregroundLegal: Boolean,
        recoveryComplete: Boolean = true,
        partial: Boolean = false,
    ): LifecycleStepResult {
        val start = legalStart(foregroundLegal)
        if (start is LifecycleStepResult.Rejected) return start
        val recover = beginRecovery()
        if (recover is LifecycleStepResult.Rejected) return recover
        return when {
            !recoveryComplete -> recoveryFailed()
            partial -> recoveryPartial()
            else -> recoveryOk()
        }
    }

    private fun recoveryGuards(complete: Boolean): GuardEvaluator =
        GuardEvaluator.of(
            "foregroundLegal" to true,
            "epochAdvanced" to true,
            "recoveryComplete" to complete,
        )

    private fun applyEvent(event: String, guards: GuardEvaluator): LifecycleStepResult {
        return when (val result = aggregate.apply(event, guards)) {
            is AggregateTransitionResult.Success -> {
                aggregate = result.aggregate
                LifecycleStepResult.Accepted(
                    from = result.outcome.from,
                    event = result.outcome.event,
                    to = result.outcome.to,
                    transitionId = result.outcome.transitionId,
                    actions = result.outcome.actions,
                    identity = identityRef.get(),
                )
            }
            is AggregateTransitionResult.Rejected ->
                LifecycleStepResult.Rejected(
                    "RUNTIME ${result.rejection.reason}: " +
                        "${result.rejection.from} + ${result.rejection.event}",
                )
        }
    }
}

sealed class LifecycleStepResult {
    data class Accepted(
        val from: String,
        val event: String,
        val to: String,
        val transitionId: String,
        val actions: List<String>,
        val identity: RuntimeIdentity,
    ) : LifecycleStepResult()

    data class Rejected(val reason: String) : LifecycleStepResult()
}
