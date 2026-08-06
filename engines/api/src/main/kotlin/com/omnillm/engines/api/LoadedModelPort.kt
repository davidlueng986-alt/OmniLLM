package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.SessionHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.LoadedModelId

/**
 * Per-loaded-model engine port (CORE-ENGINE §2 [LoadedModelPort]).
 *
 * Inference and embedding share Plan → Reserve → Commit → Execute.
 * Embeddings do not depend on a generation Session (CORE-ENGINE §10).
 *
 * Adapters must not write DB/model store; native crash maps to structured
 * [OmniResult] and Session/Allocation reconciliation by the control plane.
 */
interface LoadedModelPort {
    val loadedModelId: LoadedModelId
    val engineBuildId: EngineBuildId

    /** Pure inference plan — no KV mutation (ADR-002). */
    suspend fun planInference(
        input: InferenceInput,
        source: EngineSourceSessionRef,
    ): OmniResult<InferencePlan>

    /**
     * Durable prepare after reservation. Idempotent by [CommitContext.commitId]
     * (claim-or-return; reply loss ⇒ [queryCommit], not blind replay).
     */
    suspend fun commitInference(
        plan: InferencePlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<PreparedOperation>

    /**
     * Idempotent start claim: PREPARED → STARTING → RUNNING (OPERATION FSM).
     * Events delivered via [sink]; terminal is unique per request/epoch.
     */
    suspend fun start(
        prepared: PreparedOperation,
        op: OperationContext,
        sink: EventSink,
    ): OmniResult<OperationHandle>

    /** Pure embedding plan — same resource principles as generation. */
    suspend fun planEmbedding(input: EmbeddingInput): OmniResult<EmbeddingPlan>

    suspend fun commitEmbedding(
        plan: EmbeddingPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<PreparedOperation>

    suspend fun closeSession(
        handle: SessionHandleId,
        op: OperationContext,
    ): OmniResult<CloseResult>

    suspend fun unload(op: OperationContext): OmniResult<UnloadResult>

    /**
     * Query a prior commit for reply-loss recovery (ADR-004/005).
     * Same semantics as [EngineLoadPort.queryCommit].
     */
    suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState>
}

/**
 * Source session reference for planInference (engine SPI; no runtime module edge).
 *
 * Mirrors CORE-SESSION: first request uses [None]; continuation uses [Existing]
 * with owner + epoch match at commit time.
 */
sealed class EngineSourceSessionRef {
    data object None : EngineSourceSessionRef()

    data class Existing(
        val sessionHandleId: SessionHandleId,
        val sessionEpoch: Long,
        val ownerKey: String,
    ) : EngineSourceSessionRef() {
        init {
            require(sessionEpoch >= 0L) { "sessionEpoch must be non-negative" }
            require(ownerKey.isNotEmpty()) { "ownerKey must be non-empty" }
        }
    }
}

/** Generation / chat inference input (opaque digests — no raw prompt storage here). */
data class InferenceInput(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val canonicalInputDigest: Sha256Digest,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val deadlineMonotonic: Long,
    /** Opaque workload envelope label for qualification cell matching. */
    val workloadEnvelope: String = "default",
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
        require(workloadEnvelope.isNotEmpty()) { "workloadEnvelope must be non-empty" }
    }
}

/**
 * Pure inference plan (CORE-ENGINE §3).
 * Opaque [planId]; includes ResourceVector envelope, phase capability, prefix decision.
 */
data class InferencePlan(
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val engineBuildId: EngineBuildId,
    val loadedModelId: LoadedModelId,
    val resourceEnvelope: ResourceEnvelope,
    val phaseCapabilityDigest: Sha256Digest,
    val canonicalInputDigest: Sha256Digest,
    val proposedPrefixDecision: PrefixDecision = PrefixDecision.NONE,
    val sourceSessionEpoch: Long? = null,
    val expiryMonotonic: Long,
    val runtimeEpoch: Long,
) {
    init {
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        sourceSessionEpoch?.let {
            require(it >= 0L) { "sourceSessionEpoch must be non-negative" }
        }
    }
}

data class EmbeddingInput(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val canonicalInputDigest: Sha256Digest,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val deadlineMonotonic: Long,
    val workloadEnvelope: String = "embedding",
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
        require(workloadEnvelope.isNotEmpty()) { "workloadEnvelope must be non-empty" }
    }
}

data class EmbeddingPlan(
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val engineBuildId: EngineBuildId,
    val loadedModelId: LoadedModelId,
    val resourceEnvelope: ResourceEnvelope,
    val phaseCapabilityDigest: Sha256Digest,
    val canonicalInputDigest: Sha256Digest,
    val expiryMonotonic: Long,
    val runtimeEpoch: Long,
) {
    init {
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}

data class CloseResult(
    val sessionHandleId: SessionHandleId,
    val closed: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)

data class UnloadResult(
    val loadedModelId: LoadedModelId,
    val unloaded: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)
