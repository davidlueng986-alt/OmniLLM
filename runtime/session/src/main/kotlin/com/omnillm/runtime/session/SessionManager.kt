package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId

/**
 * Session Manager control-plane API (CORE-SESSION, INV-006/007, ADR-006).
 *
 * Responsibilities:
 * - Owner-partitioned session registry
 * - Pool policy (owner + revision + loadKey + epochs); never re-pool POISONED
 * - Poison / drain / orphan lifecycle via SESSION FSM
 * - Prefix decision storage
 * - Commit checkpoints; ASSISTANT_ACKNOWLEDGED only via AIDL application ACK
 *
 * Does **not** load native engines. Process-local [InMemorySessionManager] holds
 * live pool/op counts; [DurableSessionManager] (wired by runtime-service) writes
 * control-plane Session records + disposition via ADR-010 sole writer.
 * Native KV is never claimed durable without an engine snapshot protocol.
 */
interface SessionManager {

    // --- create / publish / lookup ---

    /**
     * Create a session in NEW. Does not charge allocation until [publish].
     * [allocationHandleId] is attached at publish (chargeAllocation action).
     */
    fun create(
        sessionId: SessionId,
        ownerKey: OwnerKey,
        sessionEpoch: Long,
        modelRevisionId: ModelRevisionId,
        loadKey: LoadKey,
        tokenizerDigest: Sha256Digest,
        contextConfig: String,
        allocationHandleId: AllocationHandleId,
        revocationEpoch: Long = 0L,
        runtimeEpoch: Long = 0L,
        committedFingerprint: String? = null,
    ): OmniResult<SessionRecord>

    /** NEW → ACTIVE with ownerAndEpochMatch guard (SES-001). */
    fun publish(sessionId: SessionId): OmniResult<SessionRecord>

    /** NEW create failure path (SES-002). */
    fun createFailed(sessionId: SessionId): OmniResult<SessionRecord>

    fun get(sessionId: SessionId): SessionRecord?

    fun getRequired(sessionId: SessionId): OmniResult<SessionRecord>

    /**
     * Resolve [SourceSessionRef] against live registry.
     * [SourceSessionRef.None] always succeeds with null session.
     * [SourceSessionRef.Existing] requires id+epoch+owner match and non-terminal usable state.
     */
    fun resolveSource(ref: SourceSessionRef): OmniResult<SessionRecord?>

    // --- lifecycle: drain / poison / orphan / close ---

    /** ACTIVE → DRAINING; removes from pool (SES-003). */
    fun requestDrain(sessionId: SessionId): OmniResult<SessionRecord>

    /**
     * ACTIVE → POISONED on uncertain partial mutation / untrusted epoch (SES-004).
     * Never returns to pool (INV-007).
     */
    fun poison(sessionId: SessionId, reason: String? = null): OmniResult<SessionRecord>

    /**
     * ACTIVE → ORPHANED when supervisor/worker lost (SES-005).
     * Reconciler-only; no automatic reuse.
     */
    fun markOrphaned(sessionId: SessionId): OmniResult<SessionRecord>

    /** DRAINING → CLOSING when no active operations (SES-006). */
    fun markQuiescent(sessionId: SessionId): OmniResult<SessionRecord>

    /** POISONED → CLOSING (SES-007). */
    fun closePoisoned(sessionId: SessionId): OmniResult<SessionRecord>

    /** ORPHANED → CLOSING (SES-008). */
    fun closeOrphaned(sessionId: SessionId): OmniResult<SessionRecord>

    /** NEW → CLOSING (SES-010). */
    fun closeNew(sessionId: SessionId): OmniResult<SessionRecord>

    /**
     * CLOSING → CLOSED after native release barrier (SES-009).
     * Allocation may be released by the governor after this signal.
     */
    fun confirmResourceBarrier(sessionId: SessionId): OmniResult<SessionRecord>

    // --- operations / pin / health ---

    fun beginOperation(sessionId: SessionId): OmniResult<SessionRecord>

    fun endOperation(sessionId: SessionId): OmniResult<SessionRecord>

    fun setPinned(sessionId: SessionId, pinned: Boolean): OmniResult<SessionRecord>

    fun markUnhealthy(sessionId: SessionId): OmniResult<SessionRecord>

    // --- prefix decision storage ---

    /** Store planned (pre-commit) prefix decision. Plan has no domain mutation. */
    fun storePlannedPrefixDecision(
        sessionId: SessionId,
        decision: PrefixDecision,
        tokenFingerprint: String?,
        committedTokenCount: Long? = null,
        plannedAtMonotonic: Long = 0L,
    ): OmniResult<SessionRecord>

    /**
     * Mark the stored prefix decision as commit-executed and optionally update
     * committed fingerprint on the descriptor.
     */
    fun commitPrefixDecision(
        sessionId: SessionId,
        committedFingerprint: String? = null,
    ): OmniResult<SessionRecord>

    // --- commit checkpoints / delivery (INV-006 / ADR-006) ---

    /**
     * Advance an **engine/domain** checkpoint.
     * Rejects [CommitCheckpoint.ASSISTANT_ACKNOWLEDGED] — use [applyDelivery].
     */
    fun advanceEngineCheckpoint(
        sessionId: SessionId,
        checkpoint: CommitCheckpoint,
    ): OmniResult<SessionRecord>

    /**
     * Apply transport delivery signal.
     * - [DeliverySemantics.SseStatelessByDefault]: no-op for client-delivered checkpoint
     * - [DeliverySemantics.AidlApplicationAck]: may advance ASSISTANT_ACKNOWLEDGED
     */
    fun applyDelivery(
        sessionId: SessionId,
        delivery: DeliverySemantics,
    ): OmniResult<SessionRecord>

    // --- pool ---

    /**
     * Offer session into free pool when policy allows.
     * Fails closed for POISONED / ORPHANED / non-ACTIVE (INV-007).
     */
    fun offerToPool(sessionId: SessionId, nowMonotonic: Long = 0L): OmniResult<SessionRecord>

    /** Remove from free pool without changing FSM state. */
    fun withdrawFromPool(sessionId: SessionId): OmniResult<SessionRecord>

    /**
     * Acquire best free candidate for [query] (LRU: least recently used first among matches).
     * Leaves pool (caller holds session for a request).
     */
    fun acquireFromPool(query: PoolCandidateQuery): OmniResult<SessionRecord>

    fun listPoolCandidates(query: PoolCandidateQuery): List<SessionRecord>

    /** Idle ACTIVE sessions eligible for drain under memory pressure (CORE-SESSION §7). */
    fun listIdleForPressureDrain(limit: Int = Int.MAX_VALUE): List<SessionRecord>

    fun listByOwner(ownerKey: OwnerKey): List<SessionRecord>

    fun allSessions(): List<SessionRecord>
}
