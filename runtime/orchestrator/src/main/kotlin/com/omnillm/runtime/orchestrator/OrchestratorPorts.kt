package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.resource.Reservation
import com.omnillm.engines.api.CommitQueryState

/**
 * Capability cell lookup for candidate planning (CORE-ORCHESTRATOR §2 filter 1).
 * Unknown capability ⇒ fail closed (INV-018) via [CapabilityState.UNKNOWN].
 */
fun interface CapabilityLookup {
    fun state(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState
}

/**
 * Health / thermal / revocation facts for filter 8 (CORE-ORCHESTRATOR §2).
 * Implementations must not invent states outside operational labels used here.
 */
data class HealthSnapshot(
    val engineHealthy: Boolean = true,
    val modelHealthy: Boolean = true,
    val thermalOk: Boolean = true,
    val revocationEpoch: Long = 0L,
    val notes: Map<String, String> = emptyMap(),
) {
    init {
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }
}

fun interface HealthLookup {
    fun snapshot(candidate: RoutingCandidate): HealthSnapshot
}

/**
 * Session ownership / fingerprint check for filter 6 (CORE-ORCHESTRATOR §2).
 * Returns null when compatible; otherwise a stable rejection message.
 */
fun interface SessionCompatibilityCheck {
    fun incompatibilityReason(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): String?
}

/**
 * Pure inference plan port (ADR-002). No domain mutation.
 * Mirrors engine load SPI shape for generation/embedding operations.
 */
interface InferenceEnginePort {
    /**
     * Pure plan: resource envelope + digests. Must not open weights or mutate Session/DB.
     */
    suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome>

    /**
     * Commit after durable intent + reservation (ARCH-SEQUENCE-FLOWS §2).
     * Adapter must not write OmniLLM DB (ENGINE-STANDARD).
     */
    suspend fun commitInference(
        plan: Plan,
        reservation: Reservation,
        commit: Commit,
    ): OmniResult<PreparedOperation>

    /** Start prepared operation (PREPARED → STARTING). */
    suspend fun start(
        prepared: PreparedOperation,
        operationId: String,
        runtimeEpoch: Long,
    ): OmniResult<Unit>

    /**
     * Pull next stream batch or terminal. Empty [events] with [terminal] set ends stream.
     * Reply-loss: caller queries rather than replaying (ADR-004/005).
     */
    suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome>

    /** Query commit for uncertain/reply-loss paths. */
    suspend fun queryCommit(commitId: com.omnillm.core.contracts.CommitId): OmniResult<CommitQueryState>
}

/** Pure plan outcome from [InferenceEnginePort.planInference]. */
data class InferencePlanOutcome(
    val plan: Plan,
    val resourceEnvelope: ResourceEnvelope,
    val planInputDigest: Sha256Digest,
)

/**
 * Minimal stream batch projection for the control plane
 * (canonical StreamBatch range is half-open [seqFrom, seqTo)).
 */
data class StreamBatchOutcome(
    val seqFrom: Long,
    val seqTo: Long,
    val events: List<StreamEvent>,
    val terminal: StreamTerminal? = null,
) {
    init {
        require(seqFrom >= 0L && seqTo >= seqFrom) { "invalid stream sequence range" }
    }
}

data class StreamEvent(
    val seq: Long,
    val kind: String,
    val payloadDigest: Sha256Digest? = null,
) {
    init {
        require(seq >= 0L) { "seq must be non-negative" }
        require(kind.isNotEmpty()) { "event kind must be non-empty" }
    }
}

/**
 * Terminal dispositions map to REQUEST TERMINATING outcomes
 * (TERMINAL_SUCCESS / TERMINAL_FAILURE / TERMINAL_CANCELLED).
 */
enum class StreamTerminalKind {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class StreamTerminal(
    val kind: StreamTerminalKind,
    val outputDigest: Sha256Digest? = null,
    val errorCode: String? = null,
)

/** Earliest-start estimate exposed with policy version (not an SLA). */
data class EarliestStartEstimate(
    val enqueueSeq: Long,
    val estimatedStartMonotonic: Long,
    val policyVersion: String,
    val confidence: String,
    val requestId: RequestId,
) {
    init {
        require(enqueueSeq >= 0L) { "enqueueSeq must be non-negative" }
        require(estimatedStartMonotonic >= 0L) { "estimatedStartMonotonic must be non-negative" }
        require(policyVersion.isNotEmpty()) { "policyVersion must be non-empty" }
        require(confidence.isNotEmpty()) { "confidence must be non-empty" }
    }
}
