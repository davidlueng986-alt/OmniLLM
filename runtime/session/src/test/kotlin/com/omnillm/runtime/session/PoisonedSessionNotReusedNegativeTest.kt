package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poisoned sessions must never re-enter the free pool or be reused as source (INV-007).
 *
 * SEC-THREAT: "Session跨租戶 — owner partition、revocation epoch、ACK checkpoint、poison".
 * REL-RECOVERY: post-commit / uncertain paths poison rather than silent reuse.
 *
 * Quality scenario: **Q-005** (unprovable hidden session state never reused),
 * session integrity for **Q-007** epoch fences.
 */
class PoisonedSessionNotReusedNegativeTest {

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    private fun loadKey(): LoadKey = LoadKey(
        modelRevisionId = ModelRevisionId.parse(digestA),
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = "cpu",
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1"),
        templateEpoch = 1L,
        tokenizerEpoch = 2L,
        loadConfigurationDigest = Sha256Digest.parse(digestB),
    )

    private fun createPublished(
        mgr: SessionManager,
        id: String = "poison-sess-1",
        owner: String = "owner-neg",
    ): SessionRecord {
        val sid = SessionId(id)
        val created = mgr.create(
            sessionId = sid,
            ownerKey = OwnerKey(owner),
            sessionEpoch = 1L,
            modelRevisionId = loadKey().modelRevisionId,
            loadKey = loadKey(),
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
    fun poisonedSession_notReusedAsSource_q005() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)

        val poisoned = mgr.poison(rec.sessionId, "uncertain post-commit")
        assertTrue(poisoned is OmniResult.Ok)
        assertEquals("POISONED", (poisoned as OmniResult.Ok).value.aggregateState)

        val resolve = mgr.resolveSource(
            SourceSessionRef.Existing(rec.sessionId, rec.descriptor.sessionEpoch, rec.ownerKey),
        )
        assertTrue(resolve is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (resolve as OmniResult.Err).error.code)
        val msg = resolve.error.message.orEmpty()
        assertTrue(msg.contains("POISONED") || msg.contains("never reuse"))
    }

    @Test
    fun poisonedSession_neverRePools() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr)
        assertTrue(mgr.offerToPool(rec.sessionId) is OmniResult.Ok)

        mgr.poison(rec.sessionId, "partial mutation")
        val reoffer = mgr.offerToPool(rec.sessionId)
        assertTrue(reoffer is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (reoffer as OmniResult.Err).error.code)

        val candidates = mgr.listPoolCandidates(
            PoolCandidateQuery(poolKey = rec.poolKey, revocationEpoch = 0L),
        )
        assertTrue(candidates.isEmpty())
        assertFalse(mgr.get(rec.sessionId)!!.inPool)
    }

    @Test
    fun orphanedSession_alsoNotAutoReused() {
        val mgr = InMemorySessionManager()
        val rec = createPublished(mgr, id = "orphan-sess-1")
        mgr.offerToPool(rec.sessionId)
        val orphaned = mgr.markOrphaned(rec.sessionId)
        assertTrue(orphaned is OmniResult.Ok)
        assertEquals("ORPHANED", (orphaned as OmniResult.Ok).value.aggregateState)

        val resolve = mgr.resolveSource(
            SourceSessionRef.Existing(rec.sessionId, rec.descriptor.sessionEpoch, rec.ownerKey),
        )
        assertTrue(resolve is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (resolve as OmniResult.Err).error.code)
    }
}
