package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight job ledger: claim key + attempts/events + process-death reopen.
 *
 * Authority: ADR-010, DATA-OWNERSHIP, FEAT-ADMIN, REL-RECOVERY, omnillm-schema jobs tables.
 */
class SqlDelightJobLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-06T12:00:00Z"
    private val clock = { now }

    @Test
    fun claimKey_insertAndFindByIdAndClaim() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.jobs
            val row = JobLedgerRow(
                jobId = "11111111-1111-1111-1111-111111111111",
                principalId = "principal-1",
                jobKind = "DOWNLOAD",
                idempotencyKey = "idem-job-1",
                canonicalSpecJson = """{"canonicalSpecDigest":"${"a".repeat(64)}"}""",
                state = "QUEUED",
                resourceVersion = 0L,
                checkpointJson = null,
                createdAt = now,
                updatedAt = now,
            )
            ports.tx.inTransaction { ports.jobs.insert(row) }
            val byId = ports.jobs.findByJobId(row.jobId)
            val byClaim = ports.jobs.findByClaimKey(
                row.principalId,
                row.jobKind,
                row.idempotencyKey,
            )
            assertNotNull(byId)
            assertEquals(byId!!.jobId, byClaim!!.jobId)
            assertEquals("QUEUED", byClaim.state)
        }
    }

    @Test
    fun attemptsAndEvents_sameTransaction() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.jobs
            val jobId = "22222222-2222-2222-2222-222222222222"
            ports.tx.inTransaction {
                ports.jobs.insert(
                    JobLedgerRow(
                        jobId = jobId,
                        principalId = "principal-1",
                        jobKind = "DOWNLOAD",
                        idempotencyKey = "idem-job-2",
                        canonicalSpecJson = """{"ok":true}""",
                        state = "RUNNING",
                        resourceVersion = 1L,
                        checkpointJson = """{"attemptNo":1,"resumeCursor":"byte:100"}""",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ports.attempts.insert(
                    JobAttemptLedgerRow(
                        jobId = jobId,
                        attemptNo = 1,
                        state = "RUNNING",
                        startedAt = now,
                    ),
                )
                ports.events.insert(
                    JobEventLedgerRow(
                        eventId = 1L,
                        jobId = jobId,
                        attemptNo = null,
                        eventKind = "CREATED",
                        payloadJson = """{"kind":"DOWNLOAD"}""",
                        occurredAt = now,
                    ),
                )
                ports.events.insert(
                    JobEventLedgerRow(
                        eventId = 2L,
                        jobId = jobId,
                        attemptNo = 1,
                        eventKind = "START",
                        payloadJson = """{"attemptNo":"1"}""",
                        occurredAt = now,
                    ),
                )
            }
            assertEquals(1, ports.attempts.listByJobId(jobId).size)
            assertEquals(2, ports.events.listByJobId(jobId).size)
            assertEquals("RUNNING", ports.jobs.findByJobId(jobId)!!.state)
            assertNotNull(ports.jobs.findByJobId(jobId)!!.checkpointJson)
        }
    }

    @Test
    fun processDeath_jobAttemptCheckpoint_stillQueryableAfterReopen() {
        val file = tmp.newFile("jobs-restart.db")
        val jobId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val ports = db.jobs
            ports.tx.inTransaction {
                ports.jobs.insert(
                    JobLedgerRow(
                        jobId = jobId,
                        principalId = "principal-1",
                        jobKind = "DOWNLOAD",
                        idempotencyKey = "idem-restart",
                        canonicalSpecJson = """{"canonicalSpecDigest":"${"b".repeat(64)}"}""",
                        state = "RUNNING",
                        resourceVersion = 3L,
                        checkpointJson = """{"attemptNo":1,"payloadHash":"${"c".repeat(64)}","resumeCursor":"part:2"}""",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ports.attempts.insert(
                    JobAttemptLedgerRow(
                        jobId = jobId,
                        attemptNo = 1,
                        state = "RUNNING",
                        startedAt = now,
                    ),
                )
                ports.events.insert(
                    JobEventLedgerRow(
                        eventId = 1L,
                        jobId = jobId,
                        attemptNo = null,
                        eventKind = "CREATED",
                        payloadJson = null,
                        occurredAt = now,
                    ),
                )
                ports.events.insert(
                    JobEventLedgerRow(
                        eventId = 2L,
                        jobId = jobId,
                        attemptNo = 1,
                        eventKind = "CHECKPOINT",
                        payloadJson = """{"payloadHash":"${"c".repeat(64)}"}""",
                        occurredAt = now,
                    ),
                )
            }
        }

        // Simulate process death: reopen file-backed DB.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val ports = db.jobs
            val row = ports.jobs.findByJobId(jobId)
            assertNotNull(row)
            assertEquals("RUNNING", row!!.state)
            assertEquals(3L, row.resourceVersion)
            assertTrue(row.checkpointJson!!.contains("part:2"))
            val attempts = ports.attempts.listByJobId(jobId)
            assertEquals(1, attempts.size)
            assertNull(attempts[0].endedAt)
            val events = ports.events.listByJobId(jobId)
            assertEquals(2, events.size)
            assertEquals("CHECKPOINT", events[1].eventKind)
            assertEquals(1, ports.jobs.listActive().size)
        }
    }

    @Test
    fun listActive_excludesTerminal() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.jobs
            ports.tx.inTransaction {
                ports.jobs.insert(
                    JobLedgerRow(
                        jobId = "33333333-3333-3333-3333-333333333333",
                        principalId = "p",
                        jobKind = "DELETE",
                        idempotencyKey = "k1",
                        canonicalSpecJson = "{}",
                        state = "QUEUED",
                        resourceVersion = 0L,
                        checkpointJson = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ports.jobs.insert(
                    JobLedgerRow(
                        jobId = "44444444-4444-4444-4444-444444444444",
                        principalId = "p",
                        jobKind = "DELETE",
                        idempotencyKey = "k2",
                        canonicalSpecJson = "{}",
                        state = "SUCCEEDED",
                        resourceVersion = 0L,
                        checkpointJson = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            assertEquals(1, ports.jobs.listActive().size)
            assertEquals("QUEUED", ports.jobs.listActive().single().state)
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
