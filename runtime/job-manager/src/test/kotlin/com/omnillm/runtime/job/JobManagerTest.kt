package com.omnillm.runtime.job

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.domain.JobId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobManagerTest {

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    private fun downloadParams() = JobParameters.Download(
        sourceUrl = "https://example.invalid/model.gguf",
        expectedSha256 = digestA,
        expectedBytes = 1024L,
    )

    private fun identity(
        jobId: String = "11111111-1111-1111-1111-111111111111",
        kind: JobKind = JobKind.DOWNLOAD,
        key: String = "idem-1",
        digest: String = digestA,
    ) = JobManager.jobIdentity(
        jobId = jobId,
        principalId = "principal-local",
        kind = kind,
        idempotencyKey = key,
        canonicalSpecDigest = digest,
    )

    @Test
    fun create_claimOrReturn_sameKeySameDigest() {
        val mgr = JobManager()
        val id = identity()
        val first = mgr.create(id, downloadParams()) as OmniResult.Ok
        assertTrue(first.value.createdNew)
        assertEquals("QUEUED", first.value.record.state)

        val second = mgr.create(id, downloadParams()) as OmniResult.Ok
        assertFalse(second.value.createdNew)
        assertEquals(first.value.record.jobId.value, second.value.record.jobId.value)
    }

    @Test
    fun create_idempotencyConflict_differentDigest() {
        val mgr = JobManager()
        val id = identity(digest = digestA)
        assertTrue(mgr.create(id, downloadParams()) is OmniResult.Ok)

        val conflict = mgr.create(identity(digest = digestB), downloadParams()) as OmniResult.Err
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, conflict.error.code)
    }

    @Test
    fun create_rejectsMismatchedKindAndParameters() {
        val mgr = JobManager()
        val result = mgr.create(identity(kind = JobKind.DOWNLOAD), JobParameters.Import(assetId = "a1"))
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (result as OmniResult.Err).error.code)
    }

    @Test
    fun create_rejectsNonHttpsDownloadUrl() {
        val mgr = JobManager()
        val result = mgr.create(
            identity(),
            JobParameters.Download(sourceUrl = "http://example.com/m.gguf"),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (result as OmniResult.Err).error.code)
    }

    @Test
    fun create_rejectsFileDownloadUrl() {
        val mgr = JobManager()
        val result = mgr.create(
            identity(key = "idem-file"),
            JobParameters.Download(sourceUrl = "file:///tmp/m.gguf"),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (result as OmniResult.Err).error.code)
    }

    @Test
    fun start_claimAttempt_andSucceed() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        val started = mgr.start(id.jobId) as OmniResult.Ok
        assertEquals("RUNNING", started.value.state)
        assertEquals(1, started.value.currentAttemptNo)
        assertEquals(1, started.value.attempts.size)

        val done = mgr.succeed(id.jobId) as OmniResult.Ok
        assertEquals("SUCCEEDED", done.value.state)
        assertTrue(done.value.isTerminal)
        assertNotNull(done.value.attempts.single().endedAtEpochMs)
    }

    @Test
    fun pause_network_and_resume_withCheckpoint() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        mgr.start(id.jobId)

        val paused = mgr.pause(id.jobId, JobPauseReason.WAITING_NETWORK) as OmniResult.Ok
        assertEquals("PAUSED_WAITING_NETWORK", paused.value.state)
        assertEquals(JobPauseReason.WAITING_NETWORK, paused.value.pauseReason)

        val cp = JobCheckpoint(
            attemptNo = 1,
            payloadHash = digestA,
            resumeCursor = "bytes=512",
            recordedAtEpochMs = 1L,
        )
        mgr.saveCheckpoint(id.jobId, cp)
        val resumed = mgr.resume(id.jobId, JobPauseReason.WAITING_NETWORK) as OmniResult.Ok
        assertEquals("RUNNING", resumed.value.state)
        assertNull(resumed.value.pauseReason)
        assertEquals("bytes=512", resumed.value.checkpoint?.resumeCursor)
    }

    @Test
    fun pause_input_cancel_notStuck() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        mgr.start(id.jobId)
        mgr.pause(id.jobId, JobPauseReason.WAITING_INPUT)

        val cancelled = mgr.cancel(id.jobId) as OmniResult.Ok
        assertEquals("CANCELLED", cancelled.value.state)
        assertTrue(cancelled.value.isTerminal)
    }

    @Test
    fun pause_foreground_deadline_fails() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        mgr.start(id.jobId)
        mgr.pause(id.jobId, JobPauseReason.WAITING_FOREGROUND)

        val failed = mgr.failPaused(
            id.jobId,
            OmniError.DEADLINE_EXCEEDED(message = "foreground deadline"),
        ) as OmniResult.Ok
        assertEquals("FAILED", failed.value.state)
        assertEquals(OmniErrorCode.DEADLINE_EXCEEDED, failed.value.error?.code)
    }

    @Test
    fun processLost_recover_withValidCheckpoint() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        mgr.start(id.jobId)
        mgr.updateProgress(
            id.jobId,
            JobProgress(networkBytes = 100, materializedBytes = 50, verifiedBytes = 50),
        )
        val cp = JobCheckpoint(
            attemptNo = 1,
            payloadHash = digestA,
            resumeCursor = "part=0",
            recordedAtEpochMs = 2L,
        )
        mgr.saveCheckpoint(id.jobId, cp)

        val recovering = mgr.markProcessLost(id.jobId) as OmniResult.Ok
        assertEquals("RECOVERING", recovering.value.state)

        val running = mgr.completeRecovery(id.jobId, checkpointValid = true) as OmniResult.Ok
        assertEquals("RUNNING", running.value.state)
    }

    @Test
    fun processLost_invalidCheckpoint_fails() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        mgr.start(id.jobId)
        mgr.updateProgress(id.jobId, JobProgress(networkBytes = 10))
        mgr.markProcessLost(id.jobId)

        val failed = mgr.completeRecovery(id.jobId, checkpointValid = false) as OmniResult.Ok
        assertEquals("FAILED", failed.value.state)
    }

    @Test
    fun progress_unknownTotal_noRatio() {
        val p = JobProgress(networkBytes = 10, totalBytesKnown = null)
        assertNull(p.ratioOrNull())
        val known = JobProgress(networkBytes = 50, totalBytesKnown = 100)
        assertEquals(0.5, known.ratioOrNull()!!, 0.0001)
    }

    @Test
    fun import_benchmark_delete_kinds() {
        val mgr = JobManager()
        val rev = "c".repeat(64)
        val profile = "d".repeat(64)

        val importId = identity(
            jobId = "22222222-2222-2222-2222-222222222222",
            kind = JobKind.IMPORT,
            key = "imp-1",
        )
        assertTrue(
            mgr.create(importId, JobParameters.Import(assetId = "asset-1")) is OmniResult.Ok,
        )

        val benchId = identity(
            jobId = "33333333-3333-3333-3333-333333333333",
            kind = JobKind.BENCHMARK,
            key = "bench-1",
        )
        assertTrue(
            mgr.create(
                benchId,
                JobParameters.Benchmark(
                    modelRevisionId = rev,
                    engineBuildId = "engine-build-1",
                    backend = "CPU",
                    measurementProfileId = profile,
                    iterations = 3,
                ),
            ) is OmniResult.Ok,
        )

        val delId = identity(
            jobId = "44444444-4444-4444-4444-444444444444",
            kind = JobKind.DELETE,
            key = "del-1",
        )
        assertTrue(
            mgr.create(
                delId,
                JobParameters.Delete(
                    resourceKind = DeleteResourceKind.INSTALLATION,
                    resourceId = "inst-1",
                    expectedResourceVersion = 0,
                ),
            ) is OmniResult.Ok,
        )

        assertEquals(3, mgr.listActive().size)
    }

    @Test
    fun cancel_fromQueued() {
        val mgr = JobManager()
        val id = identity()
        mgr.create(id, downloadParams())
        val cancelled = mgr.cancel(id.jobId) as OmniResult.Ok
        assertEquals("CANCELLED", cancelled.value.state)
    }

    @Test
    fun query_notFound() {
        val mgr = JobManager()
        val r = mgr.query(JobId("99999999-9999-9999-9999-999999999999")) as OmniResult.Err
        assertEquals(OmniErrorCode.NOT_FOUND, r.error.code)
    }
}
