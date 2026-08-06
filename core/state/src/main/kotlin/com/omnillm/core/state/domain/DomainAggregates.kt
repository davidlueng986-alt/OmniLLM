package com.omnillm.core.state.domain

import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.FsmMachineDefinition
import com.omnillm.core.state.generated.StateMachines

/**
 * Pure domain aggregate snapshots (DATA-DOMAIN).
 *
 * Each aggregate:
 * - holds catalog state as a canonical state ID string (DATA-STATES)
 * - transitions only via [StateMachineDriver] (no illegal edges)
 * - never writes DB / model store / tokens (ADR-010 single writer is runtime)
 *
 * Installation vs LoadedModel remain separate roots (CORE-MODEL / ADR-008).
 */

/** Marker for aggregates driven by a catalog FSM. */
interface DomainAggregate {
    val machineId: String
    val state: String
    val machine: FsmMachineDefinition
    fun isTerminal(): Boolean = machine.isTerminal(state)
}

private fun <A : DomainAggregate> applyEdge(
    aggregate: A,
    event: String,
    guards: GuardEvaluator,
    copyWithState: (A, String) -> A,
): AggregateTransitionResult<A> {
    return when (
        val outcome = StateMachineDriver.transition(aggregate.machine, aggregate.state, event, guards)
    ) {
        is TransitionOutcome.Accepted ->
            AggregateTransitionResult.Success(
                aggregate = copyWithState(aggregate, outcome.to),
                outcome = outcome,
            )
        is TransitionOutcome.Rejected ->
            AggregateTransitionResult.Rejected(outcome)
    }
}

// ---------------------------------------------------------------------------
// REQUEST — claim → plan → reserve → commit → stream → terminal
// ---------------------------------------------------------------------------

data class RequestAggregate(
    val requestId: RequestId,
    val ownerKey: OwnerKey,
    override val state: String = StateMachines.REQUEST.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) {
            "RequestAggregate unknown state: $state"
        }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<RequestAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.REQUEST

        fun initial(requestId: RequestId, ownerKey: OwnerKey): RequestAggregate =
            RequestAggregate(requestId, ownerKey, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// COMMIT — intent → execute → result / reconcile
// ---------------------------------------------------------------------------

data class CommitAggregate(
    val commitId: CommitId,
    val requestId: RequestId,
    override val state: String = StateMachines.COMMIT.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "CommitAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<CommitAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.COMMIT

        fun initial(commitId: CommitId, requestId: RequestId): CommitAggregate =
            CommitAggregate(commitId, requestId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// RESERVATION — held → convert/release/expire
// ---------------------------------------------------------------------------

data class ReservationAggregate(
    val reservationId: ReservationId,
    override val state: String = StateMachines.RESERVATION.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "ReservationAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<ReservationAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.RESERVATION

        fun initial(reservationId: ReservationId): ReservationAggregate =
            ReservationAggregate(reservationId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// SESSION — NEW → ACTIVE → drain/poison/orphan → CLOSED
// ---------------------------------------------------------------------------

data class SessionAggregate(
    val sessionId: SessionId,
    val ownerKey: OwnerKey,
    val sessionEpoch: Long,
    override val state: String = StateMachines.SESSION.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(sessionEpoch >= 0) { "sessionEpoch must be non-negative" }
        require(MACHINE.isKnownState(state)) { "SessionAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<SessionAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.SESSION

        fun initial(
            sessionId: SessionId,
            ownerKey: OwnerKey,
            sessionEpoch: Long,
        ): SessionAggregate = SessionAggregate(sessionId, ownerKey, sessionEpoch, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// MODEL_INSTALLATION — discover → acquire → verify → READY → delete
// ---------------------------------------------------------------------------

data class ModelInstallationAggregate(
    val installationId: InstallationId,
    override val state: String = StateMachines.MODEL_INSTALLATION.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) {
            "ModelInstallationAggregate unknown state: $state"
        }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<ModelInstallationAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.MODEL_INSTALLATION

        fun initial(installationId: InstallationId): ModelInstallationAggregate =
            ModelInstallationAggregate(installationId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// LOADED_MODEL — plan → reserve → load → drain → unload (separate from install)
// ---------------------------------------------------------------------------

data class LoadedModelAggregate(
    val loadedModelId: LoadedModelId,
    val installationId: InstallationId,
    override val state: String = StateMachines.LOADED_MODEL.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "LoadedModelAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<LoadedModelAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.LOADED_MODEL

        fun initial(
            loadedModelId: LoadedModelId,
            installationId: InstallationId,
        ): LoadedModelAggregate =
            LoadedModelAggregate(loadedModelId, installationId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// REVISION_LEASE — pin revision for request lifetime (CORE-MODEL §9)
// ---------------------------------------------------------------------------

/**
 * Request-bound pin on a [ModelRevisionId] storage identity.
 * Delete cannot remove a revision while any lease is ACTIVE or DRAINING.
 * Alias changes do not alter the pinned revision (DATA-STATES REVISION_LEASE).
 */
data class RevisionLeaseAggregate(
    val leaseId: RevisionLeaseId,
    val requestId: RequestId,
    override val state: String = StateMachines.REVISION_LEASE.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "RevisionLeaseAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<RevisionLeaseAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.REVISION_LEASE

        fun initial(leaseId: RevisionLeaseId, requestId: RequestId): RevisionLeaseAggregate =
            RevisionLeaseAggregate(leaseId, requestId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// JOB — long-running recoverable work
// ---------------------------------------------------------------------------

data class JobAggregate(
    val jobId: JobId,
    val ownerKey: OwnerKey,
    override val state: String = StateMachines.JOB.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "JobAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<JobAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.JOB

        fun initial(jobId: JobId, ownerKey: OwnerKey): JobAggregate =
            JobAggregate(jobId, ownerKey, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// ASSET — upload/PFD materialize → verify → pin → consume/expire
// ---------------------------------------------------------------------------

data class AssetAggregate(
    val assetId: AssetId,
    val ownerKey: OwnerKey,
    override val state: String = StateMachines.ASSET.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "AssetAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<AssetAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.ASSET

        fun initial(assetId: AssetId, ownerKey: OwnerKey): AssetAggregate =
            AssetAggregate(assetId, ownerKey, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// OPERATION — prepared one-shot start / cancel / reconcile
// ---------------------------------------------------------------------------

data class OperationAggregate(
    val operationId: OperationId,
    val requestId: RequestId,
    override val state: String = StateMachines.OPERATION.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(MACHINE.isKnownState(state)) { "OperationAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<OperationAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.OPERATION

        fun initial(operationId: OperationId, requestId: RequestId): OperationAggregate =
            OperationAggregate(operationId, requestId, MACHINE.initial)
    }
}

// ---------------------------------------------------------------------------
// RUNTIME — cyclic control-plane lifecycle (no terminal)
// ---------------------------------------------------------------------------

data class RuntimeAggregate(
    val runtimeInstanceId: RuntimeInstanceId,
    val runtimeEpoch: Long,
    override val state: String = StateMachines.RUNTIME.initial,
) : DomainAggregate {
    override val machineId: String get() = MACHINE.id
    override val machine: FsmMachineDefinition get() = MACHINE

    init {
        require(runtimeEpoch >= 0) { "runtimeEpoch must be non-negative" }
        require(MACHINE.isKnownState(state)) { "RuntimeAggregate unknown state: $state" }
    }

    fun apply(
        event: String,
        guards: GuardEvaluator = GuardEvaluator.ALWAYS_TRUE,
    ): AggregateTransitionResult<RuntimeAggregate> =
        applyEdge(this, event, guards) { a, s -> a.copy(state = s) }

    companion object {
        val MACHINE: FsmMachineDefinition = StateMachines.RUNTIME

        fun initial(
            runtimeInstanceId: RuntimeInstanceId,
            runtimeEpoch: Long,
        ): RuntimeAggregate = RuntimeAggregate(runtimeInstanceId, runtimeEpoch, MACHINE.initial)
    }
}

/**
 * Registry of pure aggregate factories keyed by machine id (minimum required set).
 */
object DomainAggregates {
    val REQUIRED: List<FsmMachineDefinition> = StateMachineDriver.requiredMachines()

    fun machineFor(aggregateKind: String): FsmMachineDefinition =
        StateMachines.require(aggregateKind)
}
