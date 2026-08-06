package com.omnillm.features.autosetup

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.autosetup.domain.SetupJourneyPhases
import com.omnillm.features.autosetup.projection.AutoSetupStateProjection
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.core.contracts.IdempotencyKey
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSetupStateProjectionTest {

    private fun job(state: String): JobRecord =
        JobRecord(
            identity = JobIdentity(
                jobId = JobId("job-1"),
                principalId = principal(),
                kind = JobKind.DOWNLOAD,
                idempotencyKey = IdempotencyKey.parse("idem-1"),
                canonicalSpecDigest = "a".repeat(64),
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.test/m.bin"),
            state = state,
            resourceVersion = 1L,
            currentAttemptNo = 1,
            attempts = emptyList(),
            checkpoint = null,
            progress = JobProgress(currentPhase = "materialize"),
            pauseReason = null,
            error = null,
            events = emptyList(),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

    @Test
    fun mapsDeviceOnlyToDeviceReady() {
        val snap = AutoSetupStateProjection.project(
            previousPhase = SetupJourneyPhases.DISCOVERING_DEVICE,
            device = device(),
            recommendation = null,
            selectedCandidateId = null,
            configuration = null,
            job = null,
            installationState = null,
            installationId = null,
            requestId = null,
            requestState = null,
            cancelRequested = false,
            error = null,
            nowEpochMs = 1L,
        )
        assertEquals(SetupJourneyPhases.DEVICE_READY, snap.phase)
    }

    @Test
    fun mapsRunningJobToAcquiring() {
        val snap = AutoSetupStateProjection.project(
            previousPhase = SetupJourneyPhases.CONFIGURING,
            device = device(),
            recommendation = null,
            selectedCandidateId = "c1",
            configuration = null,
            job = job("RUNNING"),
            installationState = "ACQUIRING",
            installationId = "inst-1",
            requestId = null,
            requestState = null,
            cancelRequested = false,
            error = null,
            nowEpochMs = 1L,
        )
        assertEquals(SetupJourneyPhases.ACQUIRING, snap.phase)
        assertEquals("RUNNING", snap.jobState)
    }

    @Test
    fun mapsJobCancelledToCancelled() {
        val snap = AutoSetupStateProjection.project(
            previousPhase = SetupJourneyPhases.ACQUIRING,
            device = device(),
            recommendation = null,
            selectedCandidateId = "c1",
            configuration = null,
            job = job("CANCELLED"),
            installationState = null,
            installationId = null,
            requestId = null,
            requestState = null,
            cancelRequested = true,
            error = null,
            nowEpochMs = 1L,
        )
        assertEquals(SetupJourneyPhases.CANCELLED, snap.phase)
    }

    @Test
    fun mapsRequestCompletedToCompleted() {
        val snap = AutoSetupStateProjection.project(
            previousPhase = SetupJourneyPhases.FIRST_INFERENCE,
            device = device(),
            recommendation = null,
            selectedCandidateId = "c1",
            configuration = null,
            job = job("SUCCEEDED"),
            installationState = "READY",
            installationId = "inst-1",
            requestId = "req-1",
            requestState = "COMPLETED",
            cancelRequested = false,
            error = null,
            nowEpochMs = 1L,
        )
        assertEquals(SetupJourneyPhases.COMPLETED, snap.phase)
    }

    @Test
    fun mapsAdmissionErrorDuringPlanToFailed() {
        val snap = AutoSetupStateProjection.project(
            previousPhase = SetupJourneyPhases.PLANNING_LOAD,
            device = device(),
            recommendation = null,
            selectedCandidateId = "c1",
            configuration = null,
            job = null,
            installationState = "READY",
            installationId = "inst-1",
            requestId = null,
            requestState = null,
            cancelRequested = false,
            error = OmniError.ADMISSION_REJECTED(message = "no capacity"),
            nowEpochMs = 1L,
        )
        assertEquals(SetupJourneyPhases.FAILED, snap.phase)
    }

    @Test
    fun rejectsUnknownInstallationState() {
        try {
            AutoSetupStateProjection.project(
                previousPhase = SetupJourneyPhases.IDLE,
                device = null,
                recommendation = null,
                selectedCandidateId = null,
                configuration = null,
                job = null,
                installationState = "HALF_INSTALLED",
                installationId = "x",
                requestId = null,
                requestState = null,
                cancelRequested = false,
                error = null,
                nowEpochMs = 1L,
            )
            org.junit.Assert.fail("expected fail-closed on unknown installation state")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fail closed"))
        }
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
