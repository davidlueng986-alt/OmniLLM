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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TST-05(c): multi-threaded pool races against [InMemorySessionManager] —
 * single-winner pool acquisition (no double lease), no underflow on concurrent
 * begin/endOperation, and pool re-entry invariants under contention.
 * Real threads + start barrier.
 */
class SessionManagerConcurrencyTest {

    private val digestA = "a".repeat(64)

    private fun loadKey(): LoadKey = LoadKey(
        modelRevisionId = ModelRevisionId.parse(digestA),
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = "cpu",
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1"),
        templateEpoch = 1L,
        tokenizerEpoch = 2L,
        loadConfigurationDigest = Sha256Digest.parse("b".repeat(64)),
    )

    private fun createPublished(mgr: SessionManager, id: String = "sess-race"): SessionRecord {
        val created = mgr.create(
            sessionId = SessionId(id),
            ownerKey = OwnerKey("principal:u0:revA"),
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
        val published = mgr.publish(SessionId(id))
        assertTrue(published is OmniResult.Ok)
        return (published as OmniResult.Ok).value
    }

    private fun poolQuery(owner: String = "principal:u0:revA"): PoolCandidateQuery =
        PoolCandidateQuery(
            poolKey = SessionPoolKey.of(OwnerKey(owner), loadKey()),
            revocationEpoch = 0L,
        )

    private fun <T> runConcurrently(threads: Int, block: () -> T): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val futures = (0 until threads).map {
            executor.submit<T> {
                start.await()
                block()
            }
        }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        return results
    }

    @Test
    fun concurrentPoolAcquisition_singleWinner_noDoubleLease() {
        val mgr = InMemorySessionManager()
        val sid = SessionId("sess-race")
        createPublished(mgr, "sess-race")
        assertTrue(mgr.offerToPool(sid, nowMonotonic = 1L) is OmniResult.Ok)

        val threads = 8
        val results = runConcurrently(threads) {
            mgr.acquireFromPool(poolQuery())
        }

        val okCount = results.count { it is OmniResult.Ok }
        val notFound = results.count {
            it is OmniResult.Err &&
                (it as OmniResult.Err).error.code == OmniErrorCode.NOT_FOUND
        }
        assertEquals(1, okCount)
        assertEquals(threads - 1, notFound)

        // Winner left the pool; no double lease possible.
        assertTrue(mgr.listPoolCandidates(poolQuery()).isEmpty())
        val winner = results.filterIsInstance<OmniResult.Ok<SessionRecord>>().single().value
        assertFalse(winner.inPool)
        assertEquals(0, winner.activeOperationCount)
    }

    @Test
    fun concurrentBeginEndOperations_noUnderflow_noPoolReentryWhileActive() {
        val mgr = InMemorySessionManager()
        val sid = SessionId("sess-ops")
        createPublished(mgr, "sess-ops")

        val threads = 8
        val begins = runConcurrently(threads) {
            mgr.beginOperation(sid)
        }
        assertTrue(begins.all { it is OmniResult.Ok })
        assertEquals(threads, mgr.getRequired(sid).getOrNull()!!.activeOperationCount)
        assertFalse(mgr.getRequired(sid).getOrNull()!!.inPool)

        val ends = runConcurrently(threads) {
            mgr.endOperation(sid)
        }
        assertTrue(ends.all { it is OmniResult.Ok })
        val after = mgr.getRequired(sid).getOrNull()!!
        assertEquals(0, after.activeOperationCount)
        assertFalse(after.inPool)

        // Re-entry after quiescence works and is single-winner again.
        assertTrue(mgr.offerToPool(sid, nowMonotonic = 2L) is OmniResult.Ok)
        val acquires = runConcurrently(threads) {
            mgr.acquireFromPool(poolQuery())
        }
        assertEquals(1, acquires.count { it is OmniResult.Ok })
        assertEquals(threads - 1, acquires.count {
            it is OmniResult.Err &&
                (it as OmniResult.Err).error.code == OmniErrorCode.NOT_FOUND
        })
    }

    @Test
    fun concurrentOfferAndAcquire_poolInvariantHolds() {
        val mgr = InMemorySessionManager()
        val sid = SessionId("sess-offer")
        createPublished(mgr, "sess-offer")

        // Race: some threads re-offer, some acquire; pool membership must stay
        // a single consistent boolean per record (inPool), never negative ops.
        val threads = 12
        val results = runConcurrently(threads) {
            val outcome: String
            if (Thread.currentThread().id % 2 == 0L) {
                val r = mgr.offerToPool(sid, nowMonotonic = 10L)
                outcome = if (r is OmniResult.Ok) "offered" else "offer-rejected"
            } else {
                val r = mgr.acquireFromPool(poolQuery())
                outcome = if (r is OmniResult.Ok) "acquired" else "acquire-miss"
            }
            outcome
        }

        val record = mgr.getRequired(sid).getOrNull()!!
        assertTrue(record.activeOperationCount == 0)
        // If an acquire won, inPool is false; otherwise the session stays pooled.
        if ("acquired" in results) {
            assertFalse(record.inPool)
            // Re-offer must succeed afterwards (still ACTIVE and quiescent).
            assertTrue(mgr.offerToPool(sid, nowMonotonic = 11L) is OmniResult.Ok)
        }
        assertTrue(mgr.listPoolCandidates(poolQuery()).size <= 1)
    }

    @Test
    fun concurrentPoisonAndOffer_poisonedNeverRepools() {
        val mgr = InMemorySessionManager()
        val sid = SessionId("sess-poison")
        createPublished(mgr, "sess-poison")

        val threads = 6
        val results = runConcurrently(threads) {
            if (Thread.currentThread().id % threads == 0L) {
                mgr.poison(sid, reason = "race") // MUTATION_UNCERTAIN -> POISONED
            } else {
                mgr.offerToPool(sid, nowMonotonic = 1L)
            }
        }

        val poisoned = results.filterIsInstance<OmniResult.Ok<SessionRecord>>()
            .firstOrNull { it.value.aggregateState == "POISONED" }
        assertNotNull("one poison must win", poisoned)
        assertEquals("POISONED", poisoned!!.value.aggregateState)
        // INV-007: POISONED never returns to the pool.
        assertFalse(mgr.getRequired(sid).getOrNull()!!.inPool)
        assertTrue(mgr.listPoolCandidates(poolQuery()).isEmpty())
        // Later offers fail closed.
        assertTrue(mgr.offerToPool(sid, nowMonotonic = 2L) is OmniResult.Err)
    }
}
