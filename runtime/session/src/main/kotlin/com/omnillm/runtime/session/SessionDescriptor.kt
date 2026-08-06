package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId

/**
 * Session identity descriptor (CORE-SESSION §1).
 *
 * Minimum fields: sessionId, sessionEpoch, ownerKey, modelRevisionId, loadKey,
 * templateEpoch, tokenizerDigest, contextConfig, committedFingerprint, state,
 * allocationHandleId.
 */
data class SessionDescriptor(
    val sessionId: SessionId,
    val sessionEpoch: Long,
    val ownerKey: OwnerKey,
    val modelRevisionId: ModelRevisionId,
    val loadKey: LoadKey,
    /** Tokenizer content digest (distinct from loadKey.tokenizerEpoch). */
    val tokenizerDigest: Sha256Digest,
    /**
     * Opaque context configuration snapshot / digest string.
     * Exact schema is engine/capability scoped; non-blank required.
     */
    val contextConfig: String,
    /** Full committed token fingerprint when known; null if empty session. */
    val committedFingerprint: String?,
    /** SESSION FSM state id from specs/state-machines.yaml. */
    val state: String,
    val allocationHandleId: AllocationHandleId,
    /** Revocation epoch at last fence check (INV-017). */
    val revocationEpoch: Long = 0L,
    val runtimeEpoch: Long = 0L,
) {
    init {
        require(sessionEpoch >= 0L) { "sessionEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(contextConfig.isNotEmpty()) { "contextConfig must be non-empty" }
        require(loadKey.modelRevisionId == modelRevisionId) {
            "modelRevisionId must equal loadKey.modelRevisionId"
        }
    }

    val templateEpoch: Long get() = loadKey.templateEpoch
    val tokenizerEpoch: Long get() = loadKey.tokenizerEpoch

    val poolKey: SessionPoolKey
        get() = SessionPoolKey.of(ownerKey, loadKey)
}

/**
 * Mutable runtime bookkeeping for one session (control-plane memory).
 * Domain state transitions go through [com.omnillm.core.state.domain.SessionAggregate].
 */
data class SessionRecord(
    val descriptor: SessionDescriptor,
    val aggregateState: String,
    val checkpoints: CheckpointLedger = CheckpointLedger(),
    val lastPrefixDecision: PrefixDecisionRecord? = null,
    /** Healthy means engine/native path has not reported corruption. */
    val healthy: Boolean = true,
    /** Explicit pin blocks LRU/TTL eviction while set. */
    val pinned: Boolean = false,
    /** Active operation reference count (REQUEST / OPERATION handles). */
    val activeOperationCount: Int = 0,
    /**
     * When true, session is currently listed as a free pool candidate.
     * Never true for POISONED / ORPHANED / DRAINING / CLOSING / CLOSED (INV-007).
     */
    val inPool: Boolean = false,
    val lastUsedMonotonic: Long = 0L,
) {
    init {
        require(activeOperationCount >= 0) { "activeOperationCount must be non-negative" }
        require(descriptor.state == aggregateState) {
            "descriptor.state and aggregateState must stay aligned"
        }
        if (inPool) {
            require(aggregateState == "ACTIVE") {
                "only ACTIVE sessions may be in pool (got $aggregateState)"
            }
            require(healthy) { "unhealthy sessions must not be in pool" }
        }
    }

    val sessionId: SessionId get() = descriptor.sessionId
    val ownerKey: OwnerKey get() = descriptor.ownerKey
    val poolKey: SessionPoolKey get() = descriptor.poolKey

    fun isPoolEligible(): Boolean =
        aggregateState == "ACTIVE" &&
            healthy &&
            !pinned &&
            activeOperationCount == 0 &&
            aggregateState != "POISONED" // belt-and-suspenders (INV-007)

    /** ORPHANED is for reconciler only — never auto-reuse (CORE-SESSION §5). */
    fun allowsAutoReuse(): Boolean =
        isPoolEligible() && inPool && aggregateState != "ORPHANED"
}
