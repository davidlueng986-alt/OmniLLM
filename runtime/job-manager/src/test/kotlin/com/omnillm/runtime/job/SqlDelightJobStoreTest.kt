package com.omnillm.runtime.job

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.runtime.JobManagerModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable JobStore + JobManager: attempts, checkpoints, recover after process restart.
 *
 * Authority: ADR-010, FEAT-ADMIN §3, REL-RECOVERY, DATA-OWNERSHIP.
 */
class SqlDelightJobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private var clockMs = 1_700_000_000_000L
    private val clock = { clockMs }

    private fun downloadParams() = JobParameters.Download(
        sourceUrl = "https://example.invalid/model.gguf",
        expectedSha256 = digestA,
        expectedBytes = 1024L,
    )

    private fun identity(
        jobId: String = "11111111-1111-1111-1111-111111111111",
        kind: JobKind = JobKind.DOWNLOAD,
        key: String = "idem-durable-1",
        digest: String = digestA,
    ) = JobManager.jobIdentity(
        jobId = jobId,
        principalId = "principal-local",
        kind = kind,
        idempotencyKey = key,
        canonicalSpecDigest = digest,
    )

    private fun openDb(file: java.io.File? = null): ControlPlaneDatabase =
        if (file == null) {
            ControlPlaneDatabase.openInMemory(clock = { "2026-08-06T00:00:00Z" })
        } else {
            ControlPlaneDatabase.openJdbcFile(file, clock = { "2026-08-06T00:00:00Z" })
        }

    private fun manager(db: ControlPlaneDatabase): JobManager =
        JobManagerModule.createDurableManager(ports = db.jobs, clock = clock)

    @Test
    fun claimOrReturn_andQueryAfterWrite() {
        openDb().use { db ->
            val mgr = manager(db)
            val id = identity()
            val first = mgr.create(id, downloadParams()) as OmniResult.Ok
            assertTrue(first.value.createdNew)
            assertEquals("QUEUED", first.value.record.state)

            val second = mgr.create(id, downloadParams()) as OmniResult.Ok
            assertFalse(second.value.createdNew)
            assertEquals(first.value.record.jobId.value, second.value.record.jobId.value)

            val queried = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals("QUEUED", queried.value.state)
            assertEquals(1, queried.value.events.size)
            assertEquals("CREATED", queried.value.events.single().eventKind)
        }
    }

    @Test
    fun attemptAndCheckpoint_persistAndReload() {
        openDb().use { db ->
            val mgr = manager(db)
            val id = identity()
            mgr.create(id, downloadParams())
            val started = mgr.start(id.jobId) as OmniResult.Ok
            assertEquals("RUNNING", started.value.state)
            assertEquals(1, started.value.currentAttemptNo)
            assertEquals(1, started.value.attempts.size)

            val cp = JobCheckpoint(
                attemptNo = 1,
                payloadHash = digestB,
                resumeCursor = "byte:4096",
                durableFields = mapOf("phase" to "NETWORK"),
                recordedAtEpochMs = clockMs,
            )
            val saved = mgr.saveCheckpoint(id.jobId, cp) as OmniResult.Ok
            assertEquals("byte:4096", saved.value.checkpoint!!.resumeCursor)

            val progress = JobProgress(
                networkBytes = 4096L,
                totalBytesKnown = 10_000L,
                currentPhase = "NETWORK",
            )
            mgr.updateProgress(id.jobId, progress)

            val reloaded = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals(4096L, reloaded.value.progress.networkBytes)
            assertEquals("NETWORK", reloaded.value.progress.currentPhase)
            assertEquals(digestB, reloaded.value.checkpoint!!.payloadHash)
            assertEquals("RUNNING", reloaded.value.attempts.single().state)
            assertNull(reloaded.value.attempts.single().endedAtEpochMs)
            assertTrue(reloaded.value.events.any { it.eventKind == "CHECKPOINT" })
        }
    }

    @Test
    fun processDeath_survivesReopen_andReconcileRunningToRecovering() {
        val file = tmp.newFile("job-manager-restart.db")
        val id = identity(jobId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", key = "idem-restart")

        openDb(file).use { db ->
            val mgr = manager(db)
            mgr.create(id, downloadParams())
            mgr.start(id.jobId)
            val cp = JobCheckpoint(
                attemptNo = 1,
                payloadHash = digestB,
                resumeCursor = "part:3",
                recordedAtEpochMs = clockMs,
            )
            mgr.saveCheckpoint(id.jobId, cp)
            mgr.updateProgress(
                id.jobId,
                JobProgress(networkBytes = 8192L, totalBytesKnown = 20_000L),
            )
            val mid = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals("RUNNING", mid.value.state)
        }

        // Process death: new connection + new manager (simulate RuntimeControlPlane.attach).
        openDb(file).use { db ->
            val store = SqlDelightJobStore(db.jobs)
            val before = store.findById(id.jobId)
            assertNotNull(before)
            assertEquals("RUNNING", before!!.state)
            assertEquals(1, before.currentAttemptNo)
            assertEquals("part:3", before.checkpoint!!.resumeCursor)
            assertEquals(8192L, before.progress.networkBytes)
            assertEquals(1, before.attempts.size)
            assertTrue(before.events.any { it.eventKind == "CHECKPOINT" })

            val mgr = JobManagerModule.createDurableManager(ports = db.jobs, clock = clock)
            val recoveredIds = mgr.reconcileAfterRestart()
            assertEquals(listOf(id.jobId), recoveredIds)

            val after = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals("RECOVERING", after.value.state)
            // Checkpoint retained for CHECKPOINT_READY path.
            assertEquals("part:3", after.value.checkpoint!!.resumeCursor)
            assertEquals(1, after.value.currentAttemptNo)

            val resumed = mgr.completeRecovery(
                jobId = id.jobId,
                checkpointValid = true,
                checkpoint = after.value.checkpoint,
            ) as OmniResult.Ok
            assertEquals("RUNNING", resumed.value.state)

            val done = mgr.succeed(id.jobId) as OmniResult.Ok
            assertEquals("SUCCEEDED", done.value.state)
            assertNotNull(done.value.attempts.single().endedAtEpochMs)
            assertEquals("SUCCEEDED", done.value.attempts.single().state)
        }

        // Terminal survives another reopen.
        openDb(file).use { db ->
            val mgr = manager(db)
            val terminal = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals("SUCCEEDED", terminal.value.state)
            assertEquals(0, mgr.reconcileAfterRestart().size)
            assertEquals(0, mgr.listActive().size)
        }
    }

    @Test
    fun idempotencyConflict_survivesAcrossRestart() {
        val file = tmp.newFile("job-idempotency.db")
        val id = identity(key = "idem-conflict")

        openDb(file).use { db ->
            val mgr = manager(db)
            assertTrue(mgr.create(id, downloadParams()) is OmniResult.Ok)
        }

        openDb(file).use { db ->
            val mgr = manager(db)
            val conflict = mgr.create(
                identity(key = "idem-conflict", digest = digestB),
                downloadParams(),
            ) as OmniResult.Err
            assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, conflict.error.code)

            val same = mgr.create(id, downloadParams()) as OmniResult.Ok
            assertFalse(same.value.createdNew)
        }
    }

    @Test
    fun failPersistsErrorAndClosesAttempt() {
        openDb().use { db ->
            val mgr = manager(db)
            val id = identity(key = "idem-fail")
            mgr.create(id, downloadParams())
            mgr.start(id.jobId)
            val failed = mgr.fail(
                id.jobId,
                OmniError.INTERNAL(message = "worker died"),
            ) as OmniResult.Ok
            assertEquals("FAILED", failed.value.state)
            assertEquals(OmniErrorCode.INTERNAL, failed.value.error!!.code)
            assertEquals("FAILED", failed.value.attempts.single().state)
            assertNotNull(failed.value.attempts.single().endedAtEpochMs)

            val reloaded = mgr.query(id.jobId) as OmniResult.Ok
            assertEquals("worker died", reloaded.value.error!!.message)
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
