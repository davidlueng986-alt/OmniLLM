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
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-engine routing [InferenceEnginePort] (C-07 control-plane binding).
 *
 * Route rules (honest, fail-closed):
 * - Plan/commit route by [EngineBuildId]: the model's engine → its matching
 *   backend adapter; no match → [fallback] (primary llama adapter when bound);
 *   no engine at all → fail-closed.
 * - start/nextEvents dispatch by the engine that actually committed the
 *   prepared operation (recorded at commit; removed once the terminal is
 *   delivered — bounded by in-flight operations).
 * - queryCommit dispatches by the engine that recorded the commitId.
 *
 * Never fabricates capability: registry cells stay UNKNOWN/UNQUALIFIED; the
 * routing decision is executability only.
 */
class MultiEngineInferenceRouter(
    /** engineBuildId → bound engine adapter (one per attached engine). */
    private val byEngineBuildId: Map<String, InferenceEnginePort>,
    /** Primary fallback engine (llama-cpp) for unknown/unmatched builds; null ⇒ fail-closed. */
    private val fallback: InferenceEnginePort?,
) : InferenceEnginePort {

    private val portByPreparedOperation = ConcurrentHashMap<String, InferenceEnginePort>()
    private val portByCommitId = ConcurrentHashMap<String, InferenceEnginePort>()

    /** engineBuildIds this router can route to (diagnostics). */
    val attachedBuildIds: Set<String> get() = byEngineBuildId.keys

    private fun adapterForBuild(buildId: String): InferenceEnginePort =
        byEngineBuildId[buildId] ?: fallback ?: FailClosedInferenceEngine()

    private fun adapterForPrepared(preparedOperationId: String): InferenceEnginePort =
        portByPreparedOperation[preparedOperationId] ?: fallback ?: FailClosedInferenceEngine()

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> =
        adapterForBuild(candidate.engineBuildId.value).planInference(request, candidate)

    override suspend fun commitInference(
        plan: Plan,
        reservation: Reservation,
        commit: Commit,
    ): OmniResult<PreparedOperation> {
        val adapter = adapterForBuild(plan.engineBuildId.value)
        val out = adapter.commitInference(plan, reservation, commit)
        if (out is OmniResult.Ok) {
            portByPreparedOperation[out.value.preparedOperationId.value] = adapter
            portByCommitId[commit.commitId.value] = adapter
        }
        return out
    }

    override suspend fun start(
        prepared: PreparedOperation,
        operationId: String,
        runtimeEpoch: Long,
    ): OmniResult<Unit> =
        adapterForPrepared(prepared.preparedOperationId.value)
            .start(prepared, operationId, runtimeEpoch)

    override suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome> {
        val adapter = adapterForPrepared(prepared.preparedOperationId.value)
        val out = adapter.nextEvents(prepared, fromSeq)
        if (out is OmniResult.Ok && out.value.terminal != null) {
            // Terminal delivered — this operation's route is dead; drop the
            // record so the map stays bounded by in-flight operations.
            portByPreparedOperation.remove(prepared.preparedOperationId.value)
        }
        return out
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        (portByCommitId[commitId.value] ?: fallback ?: FailClosedInferenceEngine())
            .queryCommit(commitId)
}
