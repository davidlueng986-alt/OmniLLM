package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight commit ledger: INTENT durable + RECONCILING on restart.
 *
 * Authority: REL-RECOVERY, ADR-004/005, runtime-recovery-fixtures RR-001..RR-007.
 */
class SqlDelightCommitLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "c".repeat(64)
    private val now = "2026-08-06T12:00:00Z"
    private val clock = { now }

    private fun sampleCommit(
        commitId: String = "commit-1111-1111-1111-111111111111",
        state: String = "INTENT_RECORDED",
        nonce: String = "d".repeat(64),
    ): CommitRecordRow =
        CommitRecordRow(
            commitId = commitId,
            requestId = "req-1111-1111-1111-111111111111",
            principalId = "principal-1",
            planId = "plan-1111-1111-1111-111111111111",
            reservationId = "resv-1111-1111-1111-111111111111",
            revisionLeaseId = "lease-1111-1111-1111-111111111111",
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            targetPolicyDigest = digest,
            engineBuildId = "engine-build-1",
            canonicalInputDigest = digest,
            commitNonceDigest = nonce,
            state = state,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun intentRecorded_survivesReopen() {
        val file = tmp.newFile("commits-restart.db")
        val row = sampleCommit()

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.commits.tx.inTransaction { db.commits.commits.insert(row) }
            assertEquals(1, db.commits.commits.listOpen().size)
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val found = db.commits.commits.findByCommitId(row.commitId)
            assertEquals("INTENT_RECORDED", found!!.state)
            assertEquals(row.commitNonceDigest, found.commitNonceDigest)
            assertEquals(1, db.commits.commits.listOpen().size)
        }
    }

    @Test
    fun reconcileUnfinished_marksOpenCommitsReconciling() {
        val file = tmp.newFile("commits-reconcile.db")
        val open = sampleCommit(commitId = "commit-open-0001-0001-000000000001")
        val terminal = sampleCommit(
            commitId = "commit-term-0002-0002-000000000002",
            state = "COMMITTED",
            nonce = "e".repeat(64),
        ).copy(
            planId = "plan-2222-2222-2222-222222222222",
            resultJson = """{"ok":true}""",
        )

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.commits.tx.inTransaction {
                db.commits.commits.insert(open)
                db.commits.commits.insert(terminal)
            }
            val result = db.reconcileUnfinishedCommits()
            assertTrue(result.recoveryComplete)
            assertEquals(1, result.openBefore)
            assertEquals(1, result.markedReconciling)
            assertEquals(
                "RECONCILING",
                db.commits.commits.findByCommitId(open.commitId)!!.state,
            )
            assertEquals(
                "COMMITTED",
                db.commits.commits.findByCommitId(terminal.commitId)!!.state,
            )
        }

        // After reinject, RECONCILING and terminal still durable.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            assertEquals(
                "RECONCILING",
                db.commits.commits.findByCommitId(open.commitId)!!.state,
            )
            assertEquals(
                "COMMITTED",
                db.commits.commits.findByCommitId(terminal.commitId)!!.state,
            )
            // Second reconcile is idempotent for already-RECONCILING.
            val second = db.reconcileUnfinishedCommits()
            assertEquals(1, second.openBefore)
            assertEquals(0, second.markedReconciling)
        }
    }

    @Test
    fun preparedAndBinding_roundTrip() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val commit = sampleCommit()
            db.commits.tx.inTransaction {
                db.commits.commits.insert(commit)
                db.commits.prepared.insert(
                    PreparedOperationRow(
                        preparedOperationId = "prep-1111-1111-1111-111111111111",
                        operationId = "op-1",
                        requestId = commit.requestId,
                        commitId = commit.commitId,
                        principalId = commit.principalId,
                        reservationId = commit.reservationId,
                        revisionLeaseId = commit.revisionLeaseId,
                        issuerBootId = commit.issuerBootId,
                        runtimeEpoch = commit.runtimeEpoch,
                        revocationEpoch = commit.revocationEpoch,
                        canonicalInputDigest = digest,
                        state = "PREPARED",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                db.commits.bindings.upsert(
                    CommitResourceBindingRow(
                        commitId = commit.commitId,
                        allocationId = "alloc-1",
                        bindingRole = "SOURCE",
                        resourceVectorDigest = digest,
                        disposition = "PREPARED",
                        updatedAt = now,
                    ),
                )
            }
            assertEquals(
                "PREPARED",
                db.commits.prepared.findByCommitId(commit.commitId)!!.state,
            )
            assertEquals(1, db.commits.bindings.listByCommitId(commit.commitId).size)
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
