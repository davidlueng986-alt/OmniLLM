package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.data.persistence.InferenceRequestClaimRow
import com.omnillm.data.persistence.RequestTerminalRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C-08a / D5 (COR-19 residual): the production restart-recovery path
 * ([RuntimeControlPlane.finishRecovery]) must fence non-terminal REQUEST rows
 * into RECONCILING — not only commits. `reconcileUnfinishedRequests` had no
 * production caller: after a restart, non-terminal requests stayed stuck
 * (QUEUED/STREAMING/...) instead of being fenced for reconciliation.
 *
 * Exercises the exact production recovery orchestration
 * ([RuntimeControlPlane.runRecoveryFences]) against a file-backed SQLite DB.
 *
 * RED on current code: the recovery path never calls
 * reconcileUnfinishedRequests — non-terminal rows survive untouched.
 */
class RuntimeRecoveryRestartFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-11T12:00:00Z"
    private val clock = { now }

    private fun sampleRequest(requestId: String, state: String): InferenceRequestClaimRow =
        InferenceRequestClaimRow(
            requestId = requestId,
            principalId = "principal-1",
            operationKind = "CHAT",
            idempotencyKey = "idem-$requestId",
            canonicalRequestDigest = "a".repeat(64),
            revisionId = null,
            state = state,
            resourceVersion = 0L,
            createdAt = now,
            updatedAt = now,
        )

    private fun terminalRow(requestId: String): RequestTerminalRow =
        RequestTerminalRow(
            requestId = requestId,
            terminalState = "COMPLETED",
            outputDigest = "b".repeat(64),
            errorCode = null,
            terminalSeq = 1L,
            completedAt = now,
        )

    private fun <T> withDb(file: java.io.File, block: (ControlPlaneDatabase) -> T): T {
        val db = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun productionRecovery_fencesNonTerminalRequests_leavesTerminalsIntact() {
        val file = tmp.newFile("recovery-request-fence.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-streaming", "STREAMING"))
                db.claims.requests.insert(sampleRequest("req-queued", "QUEUED"))
                db.claims.requests.insert(sampleRequest("req-completed", "COMPLETED"))
                assertTrue(db.claims.terminals.insertIfAbsent(terminalRow("req-completed")))
            }
        }

        withDb(file) { db ->
            val fences = RuntimeControlPlane.runRecoveryFences(db)
            assertNotNull(fences)
            // Request fence ran as part of production recovery.
            assertEquals(2, fences!!.second.openBefore)
            assertEquals(2, fences.second.markedReconciling)

            fun stateOf(requestId: String): String? =
                db.claims.requests.findByRequestId(requestId)?.state

            assertEquals("RECONCILING", stateOf("req-streaming"))
            assertEquals("RECONCILING", stateOf("req-queued"))
            assertEquals("COMPLETED", stateOf("req-completed"))
            assertNotNull(db.claims.terminals.findByRequestId("req-completed"))
        }
    }

    @Test
    fun productionRecovery_requestFenceIsIdempotent() {
        val file = tmp.newFile("recovery-request-fence-idem.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-open-1", "STARTING"))
            }
        }

        withDb(file) { db ->
            val first = RuntimeControlPlane.runRecoveryFences(db)!!
            assertEquals(1, first.second.openBefore)
            assertEquals(1, first.second.markedReconciling)
            val second = RuntimeControlPlane.runRecoveryFences(db)!!
            assertEquals(0, second.second.markedReconciling)
            assertEquals("RECONCILING", db.claims.requests.findByRequestId("req-open-1")!!.state)
        }
    }

    @Test
    fun productionRecovery_noOpenRequests_reportsZero() {
        val file = tmp.newFile("recovery-request-fence-empty.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-done", "COMPLETED"))
                assertTrue(db.claims.terminals.insertIfAbsent(terminalRow("req-done")))
            }
        }

        withDb(file) { db ->
            val fences = RuntimeControlPlane.runRecoveryFences(db)!!
            assertEquals(0, fences.second.openBefore)
            assertEquals(0, fences.second.markedReconciling)
            assertNull(db.claims.requests.findByRequestId("req-done")?.state?.takeIf { it == "RECONCILING" })
            assertEquals("COMPLETED", db.claims.requests.findByRequestId("req-done")!!.state)
        }
    }
}
