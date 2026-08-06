package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId
import com.omnillm.data.persistence.SessionLedgerPorts
import com.omnillm.data.persistence.SessionRecordRow
import com.omnillm.data.persistence.SessionRecoveryDispositions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SessionManager] with durable control-plane Session records (CORE-SESSION /
 * DATA-OWNERSHIP / INV-007 / ADR-010).
 *
 * Persists descriptors, owner, epochs, FSM state, and recovery disposition via
 * [SessionLedgerPorts]. On construction, rehydrates non-CLOSED rows into the
 * in-process registry.
 *
 * ## Explicit non-claims
 * - Native KV bytes / engine session handles are **not** restored.
 * - Free-pool membership is **not** restored (`inPool=false` after reload).
 * - Active operation counts reset to 0.
 * - ACTIVE/NEW/DRAINING rows reload as [SessionRecoveryDispositions.CONTROL_PLANE_ONLY]
 *   — callers must not treat them as native-ready without engine snapshot evidence.
 *
 * POISONED / ORPHANED dispositions survive restart and never re-enter the free
 * pool (INV-007).
 */
class DurableSessionManager(
    private val store: SessionLedgerPorts,
    private val clock: () -> String = { java.time.Instant.now().toString() },
    private val memory: InMemorySessionManager = InMemorySessionManager(),
) : SessionManager {

    private val rehydrated = AtomicBoolean(false)
    private val createdAtById = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        rehydrateFromStore()
    }

    /**
     * Reload durable non-CLOSED rows into memory.
     * Safe to call once at construct; subsequent calls no-op unless [force].
     */
    fun rehydrateFromStore(force: Boolean = false) {
        if (!force && !rehydrated.compareAndSet(false, true)) return
        if (force) rehydrated.set(true)

        for (row in store.sessions.listNonTerminal()) {
            injectDurableRow(row)
        }
    }

    // --- SessionManager: create / publish / lookup ---

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
    ): OmniResult<SessionRecord> {
        val result = memory.create(
            sessionId = sessionId,
            ownerKey = ownerKey,
            sessionEpoch = sessionEpoch,
            modelRevisionId = modelRevisionId,
            loadKey = loadKey,
            tokenizerDigest = tokenizerDigest,
            contextConfig = contextConfig,
            allocationHandleId = allocationHandleId,
            revocationEpoch = revocationEpoch,
            runtimeEpoch = runtimeEpoch,
            committedFingerprint = committedFingerprint,
        )
        return persistIfOk(result)
    }

    override fun publish(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.publish(sessionId))

    override fun createFailed(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.createFailed(sessionId))

    override fun get(sessionId: SessionId): SessionRecord? = memory.get(sessionId)

    override fun getRequired(sessionId: SessionId): OmniResult<SessionRecord> =
        memory.getRequired(sessionId)

    override fun resolveSource(ref: SourceSessionRef): OmniResult<SessionRecord?> =
        memory.resolveSource(ref)

    // --- lifecycle ---

    override fun requestDrain(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.requestDrain(sessionId))

    override fun poison(sessionId: SessionId, reason: String?): OmniResult<SessionRecord> =
        persistIfOk(memory.poison(sessionId, reason))

    override fun markOrphaned(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.markOrphaned(sessionId))

    override fun markQuiescent(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.markQuiescent(sessionId))

    override fun closePoisoned(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.closePoisoned(sessionId))

    override fun closeOrphaned(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.closeOrphaned(sessionId))

    override fun closeNew(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.closeNew(sessionId))

    override fun confirmResourceBarrier(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.confirmResourceBarrier(sessionId))

    // --- operations / pin / health ---

    override fun beginOperation(sessionId: SessionId): OmniResult<SessionRecord> =
        // Operation counts are process-local; still refresh durable healthy/pin via record.
        persistIfOk(memory.beginOperation(sessionId))

    override fun endOperation(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.endOperation(sessionId))

    override fun setPinned(sessionId: SessionId, pinned: Boolean): OmniResult<SessionRecord> =
        persistIfOk(memory.setPinned(sessionId, pinned))

    override fun markUnhealthy(sessionId: SessionId): OmniResult<SessionRecord> =
        persistIfOk(memory.markUnhealthy(sessionId))

    // --- prefix / checkpoints ---

    override fun storePlannedPrefixDecision(
        sessionId: SessionId,
        decision: PrefixDecision,
        tokenFingerprint: String?,
        committedTokenCount: Long?,
        plannedAtMonotonic: Long,
    ): OmniResult<SessionRecord> =
        // Prefix plan is pre-commit (INV-003); persist fingerprint only on commit.
        memory.storePlannedPrefixDecision(
            sessionId, decision, tokenFingerprint, committedTokenCount, plannedAtMonotonic,
        )

    override fun commitPrefixDecision(
        sessionId: SessionId,
        committedFingerprint: String?,
    ): OmniResult<SessionRecord> =
        persistIfOk(memory.commitPrefixDecision(sessionId, committedFingerprint))

    override fun advanceEngineCheckpoint(
        sessionId: SessionId,
        checkpoint: CommitCheckpoint,
    ): OmniResult<SessionRecord> =
        // Checkpoint ledger is process-local until dedicated ACK durability lands.
        memory.advanceEngineCheckpoint(sessionId, checkpoint)

    override fun applyDelivery(
        sessionId: SessionId,
        delivery: DeliverySemantics,
    ): OmniResult<SessionRecord> =
        memory.applyDelivery(sessionId, delivery)

    // --- pool (INV-007) ---

    override fun offerToPool(sessionId: SessionId, nowMonotonic: Long): OmniResult<SessionRecord> {
        // Durable disposition fence: POISONED never re-pools even after reload.
        val durable = store.sessions.findBySessionId(sessionId.value)
        if (durable != null) {
            val poisoned = durable.state == "POISONED" ||
                durable.recoveryDisposition == SessionRecoveryDispositions.POISONED
            val orphaned = durable.state == "ORPHANED" ||
                durable.recoveryDisposition == SessionRecoveryDispositions.ORPHANED
            if (poisoned || orphaned) {
                // Keep memory aligned, then fail closed via normal policy path.
                val live = memory.get(sessionId)
                if (live != null && live.aggregateState != durable.state) {
                    // Re-apply durable state if memory drifted.
                    injectDurableRow(durable)
                }
                return memory.offerToPool(sessionId, nowMonotonic)
            }
        }
        // Pool membership is process-local — do not persist inPool=true.
        return memory.offerToPool(sessionId, nowMonotonic)
    }

    override fun withdrawFromPool(sessionId: SessionId): OmniResult<SessionRecord> =
        memory.withdrawFromPool(sessionId)

    override fun acquireFromPool(query: PoolCandidateQuery): OmniResult<SessionRecord> =
        memory.acquireFromPool(query)

    override fun listPoolCandidates(query: PoolCandidateQuery): List<SessionRecord> =
        memory.listPoolCandidates(query)

    override fun listIdleForPressureDrain(limit: Int): List<SessionRecord> =
        memory.listIdleForPressureDrain(limit)

    override fun listByOwner(ownerKey: OwnerKey): List<SessionRecord> =
        memory.listByOwner(ownerKey)

    override fun allSessions(): List<SessionRecord> = memory.allSessions()

    // ------------------------------------------------------------------
    // persistence helpers
    // ------------------------------------------------------------------

    private fun persistIfOk(result: OmniResult<SessionRecord>): OmniResult<SessionRecord> {
        if (result is OmniResult.Ok) {
            persistRecord(result.value)
        }
        return result
    }

    private fun persistRecord(record: SessionRecord) {
        val now = clock()
        val id = record.sessionId.value
        val createdAt = createdAtById.getOrPut(id) { now }
        val row = record.toDurableRow(createdAt = createdAt, updatedAt = now)
        store.tx.inTransaction {
            store.sessions.upsert(row)
        }
    }

    /**
     * Inject a durable row into memory without re-writing.
     * Always `inPool=false` — free pool is process-local (INV-007 after restart).
     */
    private fun injectDurableRow(row: SessionRecordRow) {
        createdAtById[row.sessionId] = row.createdAt
        val record = row.toSessionRecord()
        memory.loadDurableRecord(record)
    }
}

internal fun SessionRecord.toDurableRow(
    createdAt: String,
    updatedAt: String,
): SessionRecordRow {
    val d = descriptor
    val lk = d.loadKey
    // Synthetic loaded-model projection until model-manager attaches a real id.
    val loadedModelId = "lm:${lk.modelRevisionId.hex}:${lk.engineBuildId}:${lk.backend}:" +
        "${lk.templateEpoch}:${lk.tokenizerEpoch}"
    // Principal projection: owner key is the partition authority (CORE-SESSION).
    val principalId = d.ownerKey.value
    return SessionRecordRow(
        sessionId = sessionId.value,
        sessionEpoch = d.sessionEpoch,
        ownerKey = d.ownerKey.value,
        principalId = principalId,
        loadedModelId = loadedModelId,
        modelRevisionId = lk.modelRevisionId.hex,
        engineBuildId = lk.engineBuildId.value,
        backend = lk.backend,
        deviceExecutionFingerprint = lk.deviceExecutionFingerprint.value,
        templateEpoch = lk.templateEpoch,
        tokenizerEpoch = lk.tokenizerEpoch,
        loadConfigurationDigest = lk.loadConfigurationDigest.hex,
        tokenizerDigest = d.tokenizerDigest.hex,
        contextConfig = d.contextConfig,
        committedTokenFingerprint = d.committedFingerprint,
        state = aggregateState,
        allocationId = d.allocationHandleId.value,
        revocationEpoch = d.revocationEpoch,
        runtimeEpoch = d.runtimeEpoch,
        recoveryDisposition = SessionRecoveryDispositions.fromAggregateState(aggregateState),
        healthy = healthy,
        pinned = pinned,
        deliveredSeq = checkpoints.aidlAckedSeqToExclusive,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun SessionRecordRow.toSessionRecord(): SessionRecord {
    val loadKey = LoadKey(
        modelRevisionId = ModelRevisionId.parse(modelRevisionId),
        engineBuildId = EngineBuildId.parse(engineBuildId),
        backend = backend,
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse(deviceExecutionFingerprint),
        templateEpoch = templateEpoch,
        tokenizerEpoch = tokenizerEpoch,
        loadConfigurationDigest = Sha256Digest.parse(loadConfigurationDigest),
    )
    val descriptor = SessionDescriptor(
        sessionId = SessionId(sessionId),
        sessionEpoch = sessionEpoch,
        ownerKey = OwnerKey(ownerKey),
        modelRevisionId = loadKey.modelRevisionId,
        loadKey = loadKey,
        tokenizerDigest = Sha256Digest.parse(tokenizerDigest),
        contextConfig = contextConfig,
        committedFingerprint = committedTokenFingerprint,
        state = state,
        allocationHandleId = AllocationHandleId.parse(allocationId),
        revocationEpoch = revocationEpoch,
        runtimeEpoch = runtimeEpoch,
    )
    return SessionRecord(
        descriptor = descriptor,
        aggregateState = state,
        healthy = healthy,
        pinned = pinned,
        // Process-local after restart — never auto re-pool (INV-007).
        inPool = false,
        activeOperationCount = 0,
        lastUsedMonotonic = 0L,
    )
}
