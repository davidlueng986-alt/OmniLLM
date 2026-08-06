package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight session store: control-plane Session records survive reopen.
 *
 * Authority: CORE-SESSION, DATA-OWNERSHIP (owner/loadKey/state/epoch/disposition durable;
 * native KV **not** claimed).
 */
class SqlDelightSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "a".repeat(64)
    private val now = "2026-08-06T12:00:00Z"
    private val clock = { now }

    private fun sampleRow(
        sessionId: String = "sess-poison-0001",
        state: String = "POISONED",
        disposition: String = SessionRecoveryDispositions.POISONED,
    ): SessionRecordRow =
        SessionRecordRow(
            sessionId = sessionId,
            sessionEpoch = 3L,
            ownerKey = "owner-principal-u0",
            principalId = "owner-principal-u0",
            loadedModelId = "lm:test",
            modelRevisionId = digest,
            engineBuildId = "engine-build-1",
            backend = "cpu",
            deviceExecutionFingerprint = "device-fp-1",
            templateEpoch = 1L,
            tokenizerEpoch = 2L,
            loadConfigurationDigest = digest,
            tokenizerDigest = digest,
            contextConfig = "ctx-v1",
            committedTokenFingerprint = "fp-committed",
            state = state,
            allocationId = "alloc-sess-1",
            revocationEpoch = 0L,
            runtimeEpoch = 7L,
            recoveryDisposition = disposition,
            healthy = true,
            pinned = false,
            deliveredSeq = 0L,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun poisonedSession_survivesReopen() {
        val file = tmp.newFile("sessions-restart.db")
        val row = sampleRow()

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.sessions.tx.inTransaction { db.sessions.sessions.upsert(row) }
            val found = db.sessions.sessions.findBySessionId(row.sessionId)
            assertNotNull(found)
            assertEquals("POISONED", found!!.state)
            assertEquals(SessionRecoveryDispositions.POISONED, found.recoveryDisposition)
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val found = db.sessions.sessions.findBySessionId(row.sessionId)
            assertNotNull(found)
            assertEquals("POISONED", found!!.state)
            assertEquals(SessionRecoveryDispositions.POISONED, found.recoveryDisposition)
            assertEquals(row.ownerKey, found.ownerKey)
            assertEquals(row.sessionEpoch, found.sessionEpoch)
            assertEquals(row.runtimeEpoch, found.runtimeEpoch)
            assertEquals(row.revocationEpoch, found.revocationEpoch)
            assertEquals(row.modelRevisionId, found.modelRevisionId)
            assertEquals(row.templateEpoch, found.templateEpoch)
            assertEquals(row.tokenizerEpoch, found.tokenizerEpoch)
            assertEquals(1, db.sessions.sessions.listByState("POISONED").size)
        }
    }

    @Test
    fun activeSession_upsertUpdatesDispositionAndFingerprint() {
        val file = tmp.newFile("sessions-update.db")
        val active = sampleRow(
            sessionId = "sess-active-0001",
            state = "ACTIVE",
            disposition = SessionRecoveryDispositions.CONTROL_PLANE_ONLY,
        )

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.sessions.tx.inTransaction { db.sessions.sessions.upsert(active) }
            val ok = db.sessions.sessions.updateStateAndDisposition(
                sessionId = active.sessionId,
                state = "POISONED",
                recoveryDisposition = SessionRecoveryDispositions.POISONED,
                healthy = false,
                pinned = false,
                committedTokenFingerprint = "fp-after",
                deliveredSeq = 4L,
                updatedAt = "2026-08-06T13:00:00Z",
            )
            assertTrue(ok)
            val found = db.sessions.sessions.findBySessionId(active.sessionId)!!
            assertEquals("POISONED", found.state)
            assertEquals(SessionRecoveryDispositions.POISONED, found.recoveryDisposition)
            assertFalse(found.healthy)
            assertEquals("fp-after", found.committedTokenFingerprint)
            assertEquals(4L, found.deliveredSeq)
        }
    }

    @Test
    fun listNonTerminal_excludesClosed() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.sessions.tx.inTransaction {
                db.sessions.sessions.upsert(sampleRow(sessionId = "s-poison", state = "POISONED"))
                db.sessions.sessions.upsert(
                    sampleRow(
                        sessionId = "s-closed",
                        state = "CLOSED",
                        disposition = SessionRecoveryDispositions.CLOSED,
                    ),
                )
            }
            val live = db.sessions.sessions.listNonTerminal()
            assertEquals(1, live.size)
            assertEquals("s-poison", live[0].sessionId)
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
