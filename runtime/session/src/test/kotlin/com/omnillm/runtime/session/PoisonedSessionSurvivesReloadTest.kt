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
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.data.persistence.SessionRecoveryDispositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * POISONED control-plane Session records must survive runtime restart and must
 * never re-enter the free pool after reload (INV-007 / CORE-SESSION §5 / Q-005).
 *
 * Durable: descriptors, poison disposition, owner, epochs.
 * Not claimed: native KV bytes (no engine snapshot).
 */
class PoisonedSessionSurvivesReloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private val now = "2026-08-06T14:00:00Z"
    private val clock = { now }

    private fun loadKey(): LoadKey = LoadKey(
        modelRevisionId = ModelRevisionId.parse(digestA),
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = "cpu",
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1"),
        templateEpoch = 1L,
        tokenizerEpoch = 2L,
        loadConfigurationDigest = Sha256Digest.parse(digestB),
    )

    private fun createPublished(mgr: SessionManager, id: String = "poison-reload-1"): SessionRecord {
        val sid = SessionId(id)
        val created = mgr.create(
            sessionId = sid,
            ownerKey = OwnerKey("owner-reload"),
            sessionEpoch = 5L,
            modelRevisionId = loadKey().modelRevisionId,
            loadKey = loadKey(),
            tokenizerDigest = Sha256Digest.parse(digestA),
            contextConfig = "ctx-v1",
            allocationHandleId = AllocationHandleId.parse("alloc-$id"),
            revocationEpoch = 1L,
            runtimeEpoch = 9L,
        )
        assertTrue(created is OmniResult.Ok)
        val published = mgr.publish(sid)
        assertTrue(published is OmniResult.Ok)
        return (published as OmniResult.Ok).value
    }

    @Test
    fun poisoned_notRePooled_afterFileReload() {
        val file = tmp.newFile("session-poison-reload.db")
        val sessionId = "poison-reload-1"

        // --- process 1: create, publish, poison ---
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val mgr = DurableSessionManager(store = db.sessions, clock = clock)
            val rec = createPublished(mgr, id = sessionId)
            assertTrue(mgr.offerToPool(rec.sessionId) is OmniResult.Ok)

            val poisoned = mgr.poison(rec.sessionId, "uncertain partial mutation")
            assertTrue(poisoned is OmniResult.Ok)
            assertEquals("POISONED", (poisoned as OmniResult.Ok).value.aggregateState)
            assertFalse(poisoned.value.inPool)

            val reoffer = mgr.offerToPool(rec.sessionId)
            assertTrue(reoffer is OmniResult.Err)
            assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (reoffer as OmniResult.Err).error.code)

            // Durable row present before close.
            val row = db.sessions.sessions.findBySessionId(sessionId)
            assertNotNull(row)
            assertEquals("POISONED", row!!.state)
            assertEquals(SessionRecoveryDispositions.POISONED, row.recoveryDisposition)
            assertEquals("owner-reload", row.ownerKey)
            assertEquals(5L, row.sessionEpoch)
            assertEquals(9L, row.runtimeEpoch)
            assertEquals(1L, row.revocationEpoch)
        }

        // --- process 2: reopen DB + rehydrate manager ---
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val mgr = DurableSessionManager(store = db.sessions, clock = clock)

            val live = mgr.get(SessionId(sessionId))
            assertNotNull("POISONED session must rehydrate into process memory", live)
            assertEquals("POISONED", live!!.aggregateState)
            assertEquals("POISONED", live.descriptor.state)
            assertEquals(OwnerKey("owner-reload"), live.ownerKey)
            assertEquals(5L, live.descriptor.sessionEpoch)
            assertEquals(9L, live.descriptor.runtimeEpoch)
            assertEquals(1L, live.descriptor.revocationEpoch)
            assertFalse("rehydrate must never auto re-pool (INV-007)", live.inPool)

            // Explicit re-pool attempt must fail closed.
            val reoffer = mgr.offerToPool(SessionId(sessionId))
            assertTrue(reoffer is OmniResult.Err)
            assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (reoffer as OmniResult.Err).error.code)

            val candidates = mgr.listPoolCandidates(
                PoolCandidateQuery(poolKey = live.poolKey, revocationEpoch = 1L),
            )
            assertTrue(candidates.isEmpty())

            // Source reuse also fails closed.
            val resolve = mgr.resolveSource(
                SourceSessionRef.Existing(
                    sessionId = SessionId(sessionId),
                    sessionEpoch = 5L,
                    ownerKey = OwnerKey("owner-reload"),
                ),
            )
            assertTrue(resolve is OmniResult.Err)
            assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (resolve as OmniResult.Err).error.code)
        }
    }

    @Test
    fun activeAfterReload_notAutoPooled_nativeKvNotClaimed() {
        val file = tmp.newFile("session-active-reload.db")
        val sessionId = "active-reload-1"

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val mgr = DurableSessionManager(store = db.sessions, clock = clock)
            val rec = createPublished(mgr, id = sessionId)
            assertTrue(mgr.offerToPool(rec.sessionId) is OmniResult.Ok)
            assertTrue(mgr.get(rec.sessionId)!!.inPool)
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val mgr = DurableSessionManager(store = db.sessions, clock = clock)
            val live = mgr.get(SessionId(sessionId))
            assertNotNull(live)
            assertEquals("ACTIVE", live!!.aggregateState)
            // Free pool is process-local; native KV not proven — do not auto re-pool.
            assertFalse(live.inPool)
            val durable = db.sessions.sessions.findBySessionId(sessionId)!!
            assertEquals(
                SessionRecoveryDispositions.CONTROL_PLANE_ONLY,
                durable.recoveryDisposition,
            )
        }
    }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
