package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * COR-19 / REL-RECOVERY: after process restart, non-terminal requests must be
 * fenced into RECONCILING (never resumed blind) while terminal rows stay
 * intact. Mirrors the commit-ledger fence (reconcileUnfinishedCommits).
 */
class RequestRestartFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-07T12:00:00Z"
    private val clock = { now }

    private fun sampleRequest(
        requestId: String,
        state: String,
        resourceVersion: Long = 0L,
    ): InferenceRequestClaimRow =
        InferenceRequestClaimRow(
            requestId = requestId,
            principalId = "principal-1",
            operationKind = "CHAT",
            idempotencyKey = "idem-$requestId",
            canonicalRequestDigest = "a".repeat(64),
            revisionId = null,
            state = state,
            resourceVersion = resourceVersion,
            createdAt = now,
            updatedAt = now,
        )

    private fun terminalRow(requestId: String, terminalState: String): RequestTerminalRow =
        RequestTerminalRow(
            requestId = requestId,
            terminalState = terminalState,
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
    fun restart_fencesNonTerminalRequests_leavesTerminalsIntact() {
        val file = tmp.newFile("requests-fence.db")

        // Pre-restart rows: two non-terminal (STREAMING, QUEUED), one already
        // RECONCILING (must be skipped), two terminal (COMPLETED + terminal row,
        // ABORTED_UNCERTAIN + terminal row — must be untouched).
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-streaming", "STREAMING"))
                db.claims.requests.insert(sampleRequest("req-queued", "QUEUED"))
                db.claims.requests.insert(sampleRequest("req-reconciling", "RECONCILING"))
                db.claims.requests.insert(sampleRequest("req-completed", "COMPLETED"))
                assertTrue(
                    db.claims.terminals.insertIfAbsent(
                        terminalRow("req-completed", "COMPLETED"),
                    ),
                )
                db.claims.requests.insert(sampleRequest("req-aborted", "ABORTED_UNCERTAIN"))
                assertTrue(
                    db.claims.terminals.insertIfAbsent(
                        terminalRow("req-aborted", "ABORTED_UNCERTAIN"),
                    ),
                )
            }
        }

        // Process restart: fresh manager on the same file.
        withDb(file) { db ->
            val result = db.reconcileUnfinishedRequests()
            assertEquals(3, result.openBefore)
            assertEquals(2, result.markedReconciling)
            assertTrue(result.recoveryComplete)

            fun stateOf(requestId: String): String? =
                db.claims.requests.findByRequestId(requestId)?.state

            // Non-terminal requests fenced into RECONCILING.
            assertEquals("RECONCILING", stateOf("req-streaming"))
            assertEquals("RECONCILING", stateOf("req-queued"))
            // Already reconciling is not double-marked.
            assertEquals("RECONCILING", stateOf("req-reconciling"))
            // Terminal requests untouched (exactly-one-terminal invariant).
            assertEquals("COMPLETED", stateOf("req-completed"))
            assertEquals("ABORTED_UNCERTAIN", stateOf("req-aborted"))
            // Terminal rows intact after fence.
            assertNotNull(db.claims.terminals.findByRequestId("req-completed"))
            assertNotNull(db.claims.terminals.findByRequestId("req-aborted"))
        }
    }

    @Test
    fun restart_fenceIsIdempotent() {
        val file = tmp.newFile("requests-fence-idem.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-open-1", "STARTING"))
            }
        }

        withDb(file) { db ->
            val first = db.reconcileUnfinishedRequests()
            assertEquals(1, first.openBefore)
            assertEquals(1, first.markedReconciling)
            val second = db.reconcileUnfinishedRequests()
            assertEquals(1, second.openBefore)
            assertEquals(0, second.markedReconciling)
            assertEquals("RECONCILING", db.claims.requests.findByRequestId("req-open-1")!!.state)
        }
    }

    @Test
    fun restart_fenceUpdatesResourceVersion() {
        val file = tmp.newFile("requests-fence-rv.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-rv-1", "PLANNING", resourceVersion = 4L))
            }
        }

        withDb(file) { db ->
            db.reconcileUnfinishedRequests()
            val row = db.claims.requests.findByRequestId("req-rv-1")
            assertEquals("RECONCILING", row!!.state)
            assertEquals(5L, row.resourceVersion)
        }
    }

    @Test
    fun fenceWithNoOpenRequests_reportsZero() {
        val file = tmp.newFile("requests-fence-empty.db")
        withDb(file) { db ->
            db.claims.tx.inTransaction {
                db.claims.requests.insert(sampleRequest("req-done", "COMPLETED"))
                assertTrue(
                    db.claims.terminals.insertIfAbsent(
                        terminalRow("req-done", "COMPLETED"),
                    ),
                )
            }
        }

        withDb(file) { db ->
            val result = db.reconcileUnfinishedRequests()
            assertEquals(0, result.openBefore)
            assertEquals(0, result.markedReconciling)
            assertEquals("COMPLETED", db.claims.requests.findByRequestId("req-done")!!.state)
        }
    }
}
