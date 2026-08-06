package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.errors.ErrorMapping
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionAggregate
import com.omnillm.core.state.domain.SessionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [SessionManager].
 *
 * Single process control-plane store. [DurableSessionManager] layers ADR-010
 * sole-writer persistence on top; this class never opens Room/SQLite itself.
 */
class InMemorySessionManager : SessionManager {

    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    override fun create(
        sessionId: SessionId,
        ownerKey: OwnerKey,
        sessionEpoch: Long,
        modelRevisionId: ModelRevisionId,
        loadKey: LoadKey,
        tokenizerDigest: Sha256Digest,
        contextConfig: String,
        allocationHandleId: AllocationHandleId,
        revocationEpoch: Long,
        runtimeEpoch: Long,
        committedFingerprint: String?,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        if (sessions.containsKey(sessionId.value)) {
            return conflict("session already exists", "sessionId" to sessionId.value)
        }
        val aggregate = SessionAggregate.initial(sessionId, ownerKey, sessionEpoch)
        val descriptor = SessionDescriptor(
            sessionId = sessionId,
            sessionEpoch = sessionEpoch,
            ownerKey = ownerKey,
            modelRevisionId = modelRevisionId,
            loadKey = loadKey,
            tokenizerDigest = tokenizerDigest,
            contextConfig = contextConfig,
            committedFingerprint = committedFingerprint,
            state = aggregate.state,
            allocationHandleId = allocationHandleId,
            revocationEpoch = revocationEpoch,
            runtimeEpoch = runtimeEpoch,
        )
        val record = SessionRecord(
            descriptor = descriptor,
            aggregateState = aggregate.state,
            inPool = false,
        )
        sessions[sessionId.value] = record
        OmniResult.ok(record)
    }

    override fun publish(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "PUBLISH") { current ->
            GuardEvaluator.of(
                "ownerAndEpochMatch" to true,
            )
        }

    override fun createFailed(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "CREATE_FAILED")

    override fun get(sessionId: SessionId): SessionRecord? = sessions[sessionId.value]

    override fun getRequired(sessionId: SessionId): OmniResult<SessionRecord> {
        val r = sessions[sessionId.value]
            ?: return notFound(sessionId)
        return OmniResult.ok(r)
    }

    override fun resolveSource(ref: SourceSessionRef): OmniResult<SessionRecord?> =
        when (ref) {
            is SourceSessionRef.None -> OmniResult.ok(null)
            is SourceSessionRef.Existing -> synchronized(lock) {
                val live = sessions[ref.sessionId.value]
                    ?: return notFound(ref.sessionId)
                if (live.descriptor.sessionEpoch != ref.sessionEpoch) {
                    return conflict(
                        "source sessionEpoch mismatch",
                        "expected" to ref.sessionEpoch.toString(),
                        "actual" to live.descriptor.sessionEpoch.toString(),
                    )
                }
                if (live.ownerKey != ref.ownerKey) {
                    return forbiddenCrossOwner(ref.ownerKey, live.ownerKey)
                }
                when (live.aggregateState) {
                    "POISONED" ->
                        err(
                            OmniErrorCode.ABORTED_UNCERTAIN,
                            "source session is POISONED; never reuse",
                        )
                    "ORPHANED" ->
                        err(
                            OmniErrorCode.ABORTED_UNCERTAIN,
                            "source session is ORPHANED; reconciler only",
                        )
                    "CLOSED", "CLOSING", "DRAINING" ->
                        err(
                            OmniErrorCode.STATE_CONFLICT,
                            "source session state ${live.aggregateState} is not reusable",
                        )
                    else -> OmniResult.ok(live)
                }
            }
        }

    override fun requestDrain(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "DRAIN_REQUESTED")

    override fun poison(sessionId: SessionId, reason: String?): OmniResult<SessionRecord> =
        applyEvent(sessionId, "MUTATION_UNCERTAIN")

    override fun markOrphaned(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "SUPERVISOR_LOST")

    override fun markQuiescent(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "QUIESCENT") { current ->
            GuardEvaluator.of("noActiveOperations" to (current.activeOperationCount == 0))
        }

    override fun closePoisoned(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "CLOSE")

    override fun closeOrphaned(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "CLOSE_OR_KILL")

    override fun closeNew(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "CLOSE")

    override fun confirmResourceBarrier(sessionId: SessionId): OmniResult<SessionRecord> =
        applyEvent(sessionId, "RESOURCE_BARRIER_CONFIRMED")

    override fun beginOperation(sessionId: SessionId): OmniResult<SessionRecord> =
        synchronized(lock) {
            val current = sessions[sessionId.value] ?: return notFound(sessionId)
            if (current.aggregateState != "ACTIVE" && current.aggregateState != "DRAINING") {
                return conflict(
                    "cannot begin operation in state ${current.aggregateState}",
                    "state" to current.aggregateState,
                )
            }
            // Active use leaves free pool.
            val next = current.copy(
                activeOperationCount = current.activeOperationCount + 1,
                inPool = false,
            )
            sessions[sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun endOperation(sessionId: SessionId): OmniResult<SessionRecord> =
        synchronized(lock) {
            val current = sessions[sessionId.value] ?: return notFound(sessionId)
            if (current.activeOperationCount <= 0) {
                return conflict("activeOperationCount already zero")
            }
            val next = current.copy(activeOperationCount = current.activeOperationCount - 1)
            sessions[sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun setPinned(sessionId: SessionId, pinned: Boolean): OmniResult<SessionRecord> =
        synchronized(lock) {
            val current = sessions[sessionId.value] ?: return notFound(sessionId)
            // Pin always leaves the free pool; unpin does not auto re-offer.
            var next = current.copy(
                pinned = pinned,
                inPool = if (pinned) false else current.inPool,
            )
            if (next.inPool && !SessionPoolPolicy.mayEnterPool(next.copy(inPool = true))) {
                next = next.copy(inPool = false)
            }
            sessions[sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun markUnhealthy(sessionId: SessionId): OmniResult<SessionRecord> =
        synchronized(lock) {
            val current = sessions[sessionId.value] ?: return notFound(sessionId)
            val next = current.copy(healthy = false, inPool = false)
            sessions[sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun storePlannedPrefixDecision(
        sessionId: SessionId,
        decision: PrefixDecision,
        tokenFingerprint: String?,
        committedTokenCount: Long?,
        plannedAtMonotonic: Long,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        if (current.aggregateState != "ACTIVE" && current.aggregateState != "NEW") {
            return conflict(
                "cannot store prefix decision in state ${current.aggregateState}",
            )
        }
        val record = PrefixDecisionRecord(
            decision = decision,
            tokenFingerprint = tokenFingerprint,
            committedTokenCount = committedTokenCount,
            committed = false,
            plannedAtMonotonic = plannedAtMonotonic,
        )
        val next = current.copy(lastPrefixDecision = record)
        sessions[sessionId.value] = next
        OmniResult.ok(next)
    }

    override fun commitPrefixDecision(
        sessionId: SessionId,
        committedFingerprint: String?,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        val planned = current.lastPrefixDecision
            ?: return err(OmniErrorCode.INVALID_REQUEST, "no planned prefix decision")
        if (planned.committed) {
            return conflict("prefix decision already committed")
        }
        if (current.aggregateState != "ACTIVE") {
            return conflict("prefix commit requires ACTIVE session")
        }
        val committed = planned.markCommitted()
        val fp = committedFingerprint ?: committed.tokenFingerprint
        val next = current.copy(
            lastPrefixDecision = committed,
            descriptor = current.descriptor.copy(committedFingerprint = fp),
        )
        sessions[sessionId.value] = next
        OmniResult.ok(next)
    }

    override fun advanceEngineCheckpoint(
        sessionId: SessionId,
        checkpoint: CommitCheckpoint,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        if (checkpoint == CommitCheckpoint.ASSISTANT_ACKNOWLEDGED) {
            return err(
                OmniErrorCode.INVALID_REQUEST,
                "ASSISTANT_ACKNOWLEDGED requires AIDL application ACK (INV-006)",
            )
        }
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        val ledger = try {
            current.checkpoints.withEngineCheckpoint(checkpoint)
        } catch (e: IllegalArgumentException) {
            return err(OmniErrorCode.INVALID_REQUEST, e.message ?: "invalid checkpoint")
        }
        val next = current.copy(checkpoints = ledger)
        sessions[sessionId.value] = next
        OmniResult.ok(next)
    }

    override fun applyDelivery(
        sessionId: SessionId,
        delivery: DeliverySemantics,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        when (delivery) {
            is DeliverySemantics.SseStatelessByDefault -> {
                // INV-006 / ADR-006: SSE socket write does not establish client-delivered checkpoint.
                OmniResult.ok(current)
            }
            is DeliverySemantics.AidlApplicationAck -> {
                val ledger = try {
                    current.checkpoints.withAidlApplicationAck(
                        streamEpoch = delivery.streamEpoch,
                        seqToExclusive = delivery.seqToExclusive,
                    )
                } catch (e: IllegalArgumentException) {
                    return err(
                        OmniErrorCode.INVALID_REQUEST,
                        e.message ?: "invalid AIDL application ACK",
                    )
                } catch (e: IllegalStateException) {
                    return err(
                        OmniErrorCode.STATE_CONFLICT,
                        e.message ?: "stale AIDL streamEpoch",
                    )
                }
                val next = current.copy(checkpoints = ledger)
                sessions[sessionId.value] = next
                OmniResult.ok(next)
            }
        }
    }

    override fun offerToPool(
        sessionId: SessionId,
        nowMonotonic: Long,
    ): OmniResult<SessionRecord> = synchronized(lock) {
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        if (current.aggregateState == "POISONED") {
            return err(
                OmniErrorCode.ABORTED_UNCERTAIN,
                "POISONED sessions never re-pool (INV-007)",
            )
        }
        if (current.aggregateState == "ORPHANED") {
            return err(
                OmniErrorCode.ABORTED_UNCERTAIN,
                "ORPHANED sessions are reconciler-only; no automatic pool",
            )
        }
        if (!SessionPoolPolicy.mayEnterPool(current)) {
            return conflict(
                "session not eligible for pool (state=${current.aggregateState}, " +
                    "healthy=${current.healthy}, pinned=${current.pinned}, " +
                    "activeOps=${current.activeOperationCount})",
            )
        }
        val next = current.copy(
            inPool = true,
            lastUsedMonotonic = nowMonotonic,
        )
        sessions[sessionId.value] = next
        OmniResult.ok(next)
    }

    override fun withdrawFromPool(sessionId: SessionId): OmniResult<SessionRecord> =
        synchronized(lock) {
            val current = sessions[sessionId.value] ?: return notFound(sessionId)
            val next = current.copy(inPool = false)
            sessions[sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun acquireFromPool(query: PoolCandidateQuery): OmniResult<SessionRecord> =
        synchronized(lock) {
            val candidate = listPoolCandidatesUnlocked(query).minByOrNull { it.lastUsedMonotonic }
                ?: return err(
                    OmniErrorCode.NOT_FOUND,
                    "no pooled session for partition",
                )
            val next = candidate.copy(inPool = false)
            sessions[candidate.sessionId.value] = next
            OmniResult.ok(next)
        }

    override fun listPoolCandidates(query: PoolCandidateQuery): List<SessionRecord> =
        synchronized(lock) { listPoolCandidatesUnlocked(query) }

    override fun listIdleForPressureDrain(limit: Int): List<SessionRecord> =
        synchronized(lock) {
            sessions.values
                .filter { SessionPoolPolicy.mayLruOrTtlEvict(it) && it.aggregateState == "ACTIVE" }
                .sortedBy { it.lastUsedMonotonic }
                .take(limit.coerceAtLeast(0))
        }

    override fun listByOwner(ownerKey: OwnerKey): List<SessionRecord> =
        synchronized(lock) {
            sessions.values.filter { it.ownerKey == ownerKey }.toList()
        }

    override fun allSessions(): List<SessionRecord> =
        synchronized(lock) { sessions.values.toList() }

    /**
     * Reload a durable control-plane [SessionRecord] into process memory.
     *
     * Used by [DurableSessionManager] after restart. Always clears [SessionRecord.inPool]
     * and [SessionRecord.activeOperationCount] (process-local; not durable).
     * Does **not** restore native KV.
     */
    internal fun loadDurableRecord(record: SessionRecord) {
        synchronized(lock) {
            // INV-007: never rehydrate into free pool.
            sessions[record.sessionId.value] = record.copy(
                inPool = false,
                activeOperationCount = 0,
            )
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun listPoolCandidatesUnlocked(query: PoolCandidateQuery): List<SessionRecord> =
        sessions.values.filter { SessionPoolPolicy.matchesCandidate(it, query) }

    private fun applyEvent(
        sessionId: SessionId,
        event: String,
        guards: (SessionRecord) -> GuardEvaluator = { GuardEvaluator.ALWAYS_TRUE },
    ): OmniResult<SessionRecord> = synchronized(lock) {
        val current = sessions[sessionId.value] ?: return notFound(sessionId)
        val aggregate = SessionAggregate(
            sessionId = current.sessionId,
            ownerKey = current.ownerKey,
            sessionEpoch = current.descriptor.sessionEpoch,
            state = current.aggregateState,
        )
        when (val result = aggregate.apply(event, guards(current))) {
            is AggregateTransitionResult.Rejected ->
                conflict(
                    "SESSION transition rejected: ${result.rejection.reason}",
                    "from" to current.aggregateState,
                    "event" to event,
                )
            is AggregateTransitionResult.Success -> {
                val to = result.aggregate.state
                val leavePool = SessionPoolPolicy.mustLeavePoolOnState(to) ||
                    result.outcome.actions.contains("blockNewUse")
                val next = current.copy(
                    aggregateState = to,
                    descriptor = current.descriptor.copy(state = to),
                    inPool = if (leavePool) false else current.inPool,
                )
                // INV-007: never keep POISONED in pool regardless of flags.
                val safe = if (to == "POISONED" || to == "ORPHANED") {
                    next.copy(inPool = false)
                } else {
                    next
                }
                sessions[sessionId.value] = safe
                OmniResult.ok(safe)
            }
        }
    }

    private fun notFound(sessionId: SessionId): OmniResult<Nothing> =
        err(OmniErrorCode.NOT_FOUND, "session not found", "sessionId" to sessionId.value)

    private fun conflict(
        message: String,
        vararg details: Pair<String, String>,
    ): OmniResult<Nothing> =
        err(OmniErrorCode.STATE_CONFLICT, message, *details)

    private fun forbiddenCrossOwner(requested: OwnerKey, actual: OwnerKey): OmniResult<Nothing> =
        err(
            OmniErrorCode.FORBIDDEN,
            "source session owner partition mismatch; auto reuse cannot cross owner",
            "requestedOwner" to requested.value,
            "actualOwner" to actual.value,
        )

    private fun err(
        code: OmniErrorCode,
        message: String,
        vararg details: Pair<String, String>,
    ): OmniResult<Nothing> =
        OmniResult.err(ErrorMapping.fromCode(code, message, details.toMap()))
}
