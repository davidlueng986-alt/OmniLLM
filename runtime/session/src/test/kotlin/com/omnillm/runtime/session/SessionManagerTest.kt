package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session Manager: owner partition, poison/drain/orphan, prefix storage,
 * AIDL-only ACK checkpoints, pool policy (CORE-SESSION / INV-006 / INV-007 / ADR-006).
 */
class SessionManagerTest {

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    private fun loadKey(
        revisionHex: String = digestA,
        templateEpoch: Long = 1L,
        tokenizerEpoch: Long = 2L,
        backend: String = "cpu",
    ): LoadKey = LoadKey(
        modelRevisionId = ModelRevisionId.parse(revisionHex),
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = backend,
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1"),
        templateEpoch = templateEpoch,
        tokenizerEpoch = tokenizerEpoch,
        loadConfigurationDigest = Sha256Digest.parse(digestB),
    )

    private fun createPublished(
        mgr: SessionManager,
        id: String = "sess-1",
        owner: String = "principal:u0:revA",
        sessionEpoch: Long = 1L,
        loadKey: LoadKey = loadKey(),
    ): SessionRecord {
        val sid = SessionId(id)
        val ok = OwnerKey(owner)
        val created = mgr.create(
            sessionId = sid,
            ownerKey = ok,
            sessionEpoch = sessionEpoch,
            modelRevisionId = loadKey.modelRevisionId,
            loadKey = loadKey,
            tokenizerDigest = Sha256Digest.parse(digestA),
            contextConfig = "ctx-v1",
            allocationHandleId = AllocationHandleId.parse("alloc-$id"),
            revocationEpoch = 0L,
            runtimeEpoch = 1L,
        )
        assertTrue(created is OmniResult.Ok)
        val published = mgr.publish(sid)
        assertTrue(published is OmniResult.Ok)
        return (published as OmniResult.Ok).value
    }

    @Test
    fun createAndPublish_reachesActive() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        assertEquals("ACTIVE", rec.aggregateState)
        assertEquals("ACTIVE", rec.descriptor.state)
        assertFalse(rec.inPool)
    }

    @Test
    fun sourceSessionRef_noneResolvesNull() {
        val mgr = InMemorySessionManager()
        val r = mgr.resolveSource(SourceSessionRef.None)
        assertTrue(r is OmniResult.Ok)
        assertNull((r as OmniResult.Ok).value)
    }

    @Test
    fun sourceSessionRef_existingRequiresOwnerAndEpoch() {
        val mgr = InMemorySessionManager()
        createPublished(mgr, id = "s1", owner = "owner-a", sessionEpoch = 3L)

        val ok = mgr.resolveSource(
            SourceSessionRef.Existing(SessionId("s1"), 3L, OwnerKey("owner-a")),
        )
        assertTrue(ok is OmniResult.Ok)
        assertNotNull((ok as OmniResult.Ok).value)

        val badEpoch = mgr.resolveSource(
            SourceSessionRef.Existing(SessionId("s1"), 9L, OwnerKey("owner-a")),
        )
        assertTrue(badEpoch is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (badEpoch as OmniResult.Err).error.code)

        val badOwner = mgr.resolveSource(
            SourceSessionRef.Existing(SessionId("s1"), 3L, OwnerKey("owner-b")),
        )
        assertTrue(badOwner is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (badOwner as OmniResult.Err).error.code)
    }

    @Test
    fun poison_neverRePools_inv007() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        assertTrue(mgr.offerToPool(rec.sessionId) is OmniResult.Ok)
        assertTrue(mgr.get(rec.sessionId)!!.inPool)

        val poisoned = mgr.poison(rec.sessionId, "partial mutation")
        assertTrue(poisoned is OmniResult.Ok)
        assertEquals("POISONED", (poisoned as OmniResult.Ok).value.aggregateState)
        assertFalse(poisoned.value.inPool)

        val reoffer = mgr.offerToPool(rec.sessionId)
        assertTrue(reoffer is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (reoffer as OmniResult.Err).error.code)

        val query = PoolCandidateQuery(
            poolKey = rec.poolKey,
            revocationEpoch = 0L,
        )
        assertTrue(mgr.listPoolCandidates(query).isEmpty())
    }

    @Test
    fun drainAndQuiescent_closePath() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.offerToPool(rec.sessionId)

        val draining = mgr.requestDrain(rec.sessionId)
        assertTrue(draining is OmniResult.Ok)
        assertEquals("DRAINING", (draining as OmniResult.Ok).value.aggregateState)
        assertFalse(draining.value.inPool)

        val quiescent = mgr.markQuiescent(rec.sessionId)
        assertTrue(quiescent is OmniResult.Ok)
        assertEquals("CLOSING", (quiescent as OmniResult.Ok).value.aggregateState)

        val closed = mgr.confirmResourceBarrier(rec.sessionId)
        assertTrue(closed is OmniResult.Ok)
        assertEquals("CLOSED", (closed as OmniResult.Ok).value.aggregateState)
    }

    @Test
    fun drainWithActiveOps_quiescentGuardFails() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.requestDrain(rec.sessionId)
        mgr.beginOperation(rec.sessionId)

        val rejected = mgr.markQuiescent(rec.sessionId)
        assertTrue(rejected is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (rejected as OmniResult.Err).error.code)
    }

    @Test
    fun orphan_reconcilerOnly_noAutoReuse() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.offerToPool(rec.sessionId)

        val orphaned = mgr.markOrphaned(rec.sessionId)
        assertTrue(orphaned is OmniResult.Ok)
        assertEquals("ORPHANED", (orphaned as OmniResult.Ok).value.aggregateState)
        assertFalse(orphaned.value.inPool)

        val resolve = mgr.resolveSource(
            SourceSessionRef.Existing(rec.sessionId, rec.descriptor.sessionEpoch, rec.ownerKey),
        )
        assertTrue(resolve is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (resolve as OmniResult.Err).error.code)

        assertTrue(mgr.offerToPool(rec.sessionId) is OmniResult.Err)
    }

    @Test
    fun poolPartition_ownerRevisionLoadKeyEpochs() {
        val mgr = InMemorySessionManager()
        val lk1 = loadKey(templateEpoch = 1L, tokenizerEpoch = 1L)
        val lk2 = loadKey(templateEpoch = 2L, tokenizerEpoch = 1L)

        val a = createPublished(mgr, id = "a", owner = "owner-1", loadKey = lk1)
        val b = createPublished(mgr, id = "b", owner = "owner-1", loadKey = lk2)
        val c = createPublished(mgr, id = "c", owner = "owner-2", loadKey = lk1)

        mgr.offerToPool(a.sessionId, nowMonotonic = 10L)
        mgr.offerToPool(b.sessionId, nowMonotonic = 20L)
        mgr.offerToPool(c.sessionId, nowMonotonic = 30L)

        val qOwner1Lk1 = PoolCandidateQuery(SessionPoolKey.of(OwnerKey("owner-1"), lk1), 0L)
        val matches = mgr.listPoolCandidates(qOwner1Lk1)
        assertEquals(1, matches.size)
        assertEquals("a", matches.single().sessionId.value)

        // Cross-owner must not appear even with same load key.
        assertTrue(
            matches.none { it.ownerKey == OwnerKey("owner-2") },
        )
    }

    @Test
    fun acquireFromPool_withdrawsCandidate() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.offerToPool(rec.sessionId, nowMonotonic = 5L)

        val acquired = mgr.acquireFromPool(
            PoolCandidateQuery(rec.poolKey, revocationEpoch = 0L),
        )
        assertTrue(acquired is OmniResult.Ok)
        assertFalse((acquired as OmniResult.Ok).value.inPool)
        assertTrue(mgr.listPoolCandidates(PoolCandidateQuery(rec.poolKey, 0L)).isEmpty())
    }

    @Test
    fun prefixDecision_planThenCommit() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)

        val planned = mgr.storePlannedPrefixDecision(
            sessionId = rec.sessionId,
            decision = PrefixDecision.EXACT_SAME_SESSION,
            tokenFingerprint = "fp-full-tokens",
            committedTokenCount = 12L,
            plannedAtMonotonic = 100L,
        )
        assertTrue(planned is OmniResult.Ok)
        assertFalse((planned as OmniResult.Ok).value.lastPrefixDecision!!.committed)

        val committed = mgr.commitPrefixDecision(rec.sessionId, committedFingerprint = "fp-full-tokens")
        assertTrue(committed is OmniResult.Ok)
        val r = (committed as OmniResult.Ok).value
        assertTrue(r.lastPrefixDecision!!.committed)
        assertEquals("fp-full-tokens", r.descriptor.committedFingerprint)
        assertEquals(PrefixDecision.EXACT_SAME_SESSION, r.lastPrefixDecision!!.decision)
    }

    @Test
    fun sseWrite_doesNotEstablishAssistantAcknowledged_inv006() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.advanceEngineCheckpoint(rec.sessionId, CommitCheckpoint.PROMPT_COMMITTED)
        mgr.advanceEngineCheckpoint(rec.sessionId, CommitCheckpoint.ASSISTANT_PRODUCED)

        val afterSse = mgr.applyDelivery(rec.sessionId, DeliverySemantics.SseStatelessByDefault)
        assertTrue(afterSse is OmniResult.Ok)
        val ledger = (afterSse as OmniResult.Ok).value.checkpoints
        assertFalse(ledger.has(CommitCheckpoint.ASSISTANT_ACKNOWLEDGED))
        assertFalse(ledger.assistantAcknowledgedViaAidl)
    }

    @Test
    fun aidlApplicationAck_advancesAssistantAcknowledged_adr006() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.advanceEngineCheckpoint(rec.sessionId, CommitCheckpoint.PROMPT_COMMITTED)
        mgr.advanceEngineCheckpoint(rec.sessionId, CommitCheckpoint.ASSISTANT_PRODUCED)

        // Direct engine path for ACK must fail closed.
        val illegal = mgr.advanceEngineCheckpoint(
            rec.sessionId,
            CommitCheckpoint.ASSISTANT_ACKNOWLEDGED,
        )
        assertTrue(illegal is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (illegal as OmniResult.Err).error.code)

        val ack = mgr.applyDelivery(
            rec.sessionId,
            DeliverySemantics.AidlApplicationAck(streamEpoch = 1L, seqToExclusive = 10L),
        )
        assertTrue(ack is OmniResult.Ok)
        val ledger = (ack as OmniResult.Ok).value.checkpoints
        assertTrue(ledger.has(CommitCheckpoint.ASSISTANT_ACKNOWLEDGED))
        assertTrue(ledger.assistantAcknowledgedViaAidl)
        assertEquals(1L, ledger.aidlStreamEpoch)
        assertEquals(10L, ledger.aidlAckedSeqToExclusive)
    }

    @Test
    fun aidlAck_requiresAssistantProduced() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.advanceEngineCheckpoint(rec.sessionId, CommitCheckpoint.PROMPT_COMMITTED)

        val ack = mgr.applyDelivery(
            rec.sessionId,
            DeliverySemantics.AidlApplicationAck(streamEpoch = 0L, seqToExclusive = 1L),
        )
        assertTrue(ack is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (ack as OmniResult.Err).error.code)
    }

    @Test
    fun deliverySemantics_sseDoesNotEstablishClientDelivered() {
        assertFalse(DeliverySemantics.SseStatelessByDefault.establishesClientDeliveredCheckpoint())
        assertTrue(
            DeliverySemantics.AidlApplicationAck(0L, 1L).establishesClientDeliveredCheckpoint(),
        )
    }

    @Test
    fun pool_revocationEpochFence() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        // Session created with revocationEpoch=0
        mgr.offerToPool(rec.sessionId)

        val fenced = mgr.listPoolCandidates(
            PoolCandidateQuery(rec.poolKey, revocationEpoch = 5L),
        )
        assertTrue(fenced.isEmpty())

        val ok = mgr.listPoolCandidates(
            PoolCandidateQuery(rec.poolKey, revocationEpoch = 0L),
        )
        assertEquals(1, ok.size)
    }

    @Test
    fun pressureDrain_listsIdleActive() {
        val mgr = InMemorySessionManager()
        val a = createPublished(mgr, id = "idle-a")
        val b = createPublished(mgr, id = "busy-b")
        mgr.offerToPool(a.sessionId, nowMonotonic = 1L)
        mgr.beginOperation(b.sessionId)

        val idle = mgr.listIdleForPressureDrain()
        assertTrue(idle.any { it.sessionId.value == "idle-a" })
        assertFalse(idle.any { it.sessionId.value == "busy-b" })
    }

    @Test
    fun closePoisoned_thenBarrier() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        mgr.poison(rec.sessionId)
        val closing = mgr.closePoisoned(rec.sessionId)
        assertEquals("CLOSING", (closing as OmniResult.Ok).value.aggregateState)
        val closed = mgr.confirmResourceBarrier(rec.sessionId)
        assertEquals("CLOSED", (closed as OmniResult.Ok).value.aggregateState)
    }
}
