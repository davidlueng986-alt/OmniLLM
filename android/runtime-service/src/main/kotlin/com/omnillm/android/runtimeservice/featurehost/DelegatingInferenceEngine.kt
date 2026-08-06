package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.resource.Reservation
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import java.util.concurrent.atomic.AtomicReference

/**
 * Switchable [InferenceEnginePort] for control-plane wiring (ADR-010).
 *
 * Starts fail-closed; [bind] replaces the delegate after
 * [com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment]
 * succeeds with a real llama-cpp adapter (or test Fake).
 *
 * Orchestrator / CandidatePlanner hold this instance for the process lifetime
 * so engine attach does not require reconstructing the Orchestrator graph.
 */
class DelegatingInferenceEngine(
    initial: InferenceEnginePort = FailClosedInferenceEngine(),
) : InferenceEnginePort {

    private val delegateRef = AtomicReference(initial)

    /** Currently active delegate (never null). */
    val delegate: InferenceEnginePort get() = delegateRef.get()

    /** True when a non-fail-closed engine is bound. */
    val isBoundToRealEngine: Boolean
        get() = delegate !is FailClosedInferenceEngine

    /**
     * Replace the execute path. Idempotent rebinds allowed (e.g. test reset).
     * Does not elevate registry capability cells (ENGINE-QUALIFICATION-STATUS).
     */
    fun bind(engine: InferenceEnginePort) {
        delegateRef.set(engine)
    }

    /** Restore fail-closed (missing native / detach). */
    fun unbind() {
        delegateRef.set(FailClosedInferenceEngine())
    }

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> = delegate.planInference(request, candidate)

    override suspend fun commitInference(
        plan: Plan,
        reservation: Reservation,
        commit: Commit,
    ): OmniResult<PreparedOperation> = delegate.commitInference(plan, reservation, commit)

    override suspend fun start(
        prepared: PreparedOperation,
        operationId: String,
        runtimeEpoch: Long,
    ): OmniResult<Unit> = delegate.start(prepared, operationId, runtimeEpoch)

    override suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome> = delegate.nextEvents(prepared, fromSeq)

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        delegate.queryCommit(commitId)
}
