package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Reservation
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome

/** Fail-closed inference until Engine Pack attach. Never invents QUALIFIED. */
class FailClosedInferenceEngine : InferenceEnginePort {
    override suspend fun planInference(request: OrchestrationRequest, candidate: RoutingCandidate): OmniResult<InferencePlanOutcome> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "engine execute path not attached (fail closed)", details = mapOf("requestId" to request.requestId.value)))

    override suspend fun commitInference(plan: Plan, reservation: Reservation, commit: Commit): OmniResult<PreparedOperation> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "engine commit not attached (fail closed)"))

    override suspend fun start(prepared: PreparedOperation, operationId: String, runtimeEpoch: Long): OmniResult<Unit> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "engine start not attached (fail closed)"))

    override suspend fun nextEvents(prepared: PreparedOperation, fromSeq: Long): OmniResult<StreamBatchOutcome> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "engine stream not attached (fail closed)"))

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        OmniResult.err(OmniError.NOT_FOUND(message = "commit not found (engine not attached)", details = mapOf("commitId" to commitId.value)))
}
