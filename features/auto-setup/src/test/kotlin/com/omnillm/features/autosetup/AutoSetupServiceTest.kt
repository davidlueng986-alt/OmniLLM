package com.omnillm.features.autosetup

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.autosetup.domain.ModelSourceKinds
import com.omnillm.features.autosetup.domain.SetupJourneyPhases
import com.omnillm.features.autosetup.ports.AcquisitionClaim
import com.omnillm.features.autosetup.ports.FirstInferenceClaim
import com.omnillm.features.autosetup.usecase.AutoSetupService
import com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlannedCandidate
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.CostClassLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSetupServiceTest {

    @Test
    fun happyPath_discoverRecommendSelectAcquire() = runBlocking {
        val catalog = FakeCatalog(
            candidates = listOf(
                candidate(id = "small", name = "Small", quality = 0.5, speed = 0.8),
                candidate(
                    id = "large",
                    name = "Large",
                    rev = revision('4'),
                    quality = 0.9,
                    speed = 0.2,
                    peak = 6L * 1024 * 1024 * 1024,
                    packageBytes = 3L * 1024 * 1024 * 1024,
                ),
            ),
        )
        val jobPort = FakeJobPort()
        val orch = FakeOrchestratorPort()
        val api = AutoSetupService(ports(catalog = catalog, jobs = jobPort, orch = orch))

        assertEquals(SetupJourneyPhases.IDLE, api.journey().phase)

        val disc = api.discoverDevice()
        assertTrue(disc is OmniResult.Ok)
        assertEquals(SetupJourneyPhases.DEVICE_READY, api.journey().phase)
        assertNotNull(api.journey().device?.humanSummary())

        val rec = api.recommend(prefs())
        assertTrue(rec is OmniResult.Ok)
        val ranked = (rec as OmniResult.Ok).value
        assertTrue(ranked.hasViable)
        assertEquals(SetupJourneyPhases.RECOMMENDATION_READY, api.journey().phase)

        val cfg = api.selectCandidate(ranked.top!!.candidate.candidateId)
        assertTrue(cfg is OmniResult.Ok)
        assertEquals(SetupJourneyPhases.CONFIGURING, api.journey().phase)
        assertTrue((cfg as OmniResult.Ok).value.requiresPlanReservation)

        val claim = AcquisitionClaim(
            jobId = jobId(),
            principalId = principal(),
            idempotencyKey = idem("acq-1"),
            canonicalSpecDigest = "b".repeat(64),
        )
        val job = api.startAcquisition(
            claim = claim,
            sourceKind = ModelSourceKinds.CATALOG,
            parameters = JobParameters.Download(
                sourceUrl = "https://example.test/model.bin",
                expectedBytes = 200L * 1024 * 1024,
            ),
        )
        assertTrue(job is OmniResult.Ok)
        assertEquals(SetupJourneyPhases.ACQUIRING, api.journey().phase)
        assertEquals("QUEUED", api.journey().jobState)

        // Worker would drive job; simulate start then success via manager.
        jobPort.underlying.start((job as OmniResult.Ok).value.jobId)
        api.refreshJob()
        assertEquals("RUNNING", api.journey().jobState)

        jobPort.underlying.succeed(job.value.jobId)
        api.refreshJob()
        // Job terminal SUCCEEDED, no install state → still acquiring-ish or device path;
        // without installationState, job terminal leaves phase from resolve (job terminal success
        // falls through). Refresh still exposes SUCCEEDED.
        assertEquals("SUCCEEDED", api.journey().jobState)
    }

    @Test
    fun failure_noViableRecommendation() = runBlocking {
        val catalog = FakeCatalog(
            candidates = listOf(
                candidate(id = "bad", capState = CapabilityState.UNSUPPORTED),
            ),
        )
        val api = AutoSetupService(ports(catalog = catalog))
        api.discoverDevice()
        val rec = api.recommend(prefs())
        assertTrue(rec is OmniResult.Err)
        assertEquals(SetupJourneyPhases.FAILED, api.journey().phase)
        assertEquals("ADMISSION_REJECTED", api.journey().error?.code?.code)
    }

    @Test
    fun failure_deviceProbe() = runBlocking {
        val probe = FakeDeviceProbe(fail = true)
        val api = AutoSetupService(ports(device = probe))
        val r = api.discoverDevice()
        assertTrue(r is OmniResult.Err)
        assertEquals(SetupJourneyPhases.FAILED, api.journey().phase)
    }

    @Test
    fun cancel_activeJob() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate()))
        val jobPort = FakeJobPort()
        val api = AutoSetupService(ports(catalog = catalog, jobs = jobPort))
        api.discoverDevice()
        val rec = (api.recommend(prefs()) as OmniResult.Ok).value
        api.selectCandidate(rec.top!!.candidate.candidateId)
        val created = api.startAcquisition(
            AcquisitionClaim(jobId(), principal(), idem("cxl-1"), "c".repeat(64)),
            ModelSourceKinds.PINNED_DOWNLOAD,
            JobParameters.Download(sourceUrl = "https://example.test/x.bin"),
        ) as OmniResult.Ok
        jobPort.underlying.start(created.value.jobId)

        val cancelled = api.cancel(principal())
        assertTrue(cancelled is OmniResult.Ok)
        assertEquals(SetupJourneyPhases.CANCELLED, api.journey().phase)
        assertEquals("CANCELLED", api.journey().jobState)
    }

    @Test
    fun cancel_firstInferenceRequest() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate(readyInstall = "550e8400-e29b-41d4-a716-446655440000")))
        val orch = FakeOrchestratorPort(submitState = "QUEUED")
        val api = AutoSetupService(ports(catalog = catalog, orch = orch))
        api.discoverDevice()
        val rec = (api.recommend(prefs()) as OmniResult.Ok).value
        api.selectCandidate(rec.top!!.candidate.candidateId)

        val rid = requestId()
        val req = sampleOrchestrationRequest(rid)
        val claim = FirstInferenceClaim(
            requestId = rid,
            principalId = principal(),
            idempotencyKey = req.idempotencyKey,
            canonicalRequestDigest = req.canonicalRequestDigest,
            deadlineMonotonic = req.deadlineMonotonic,
        )
        val sub = api.submitFirstInference(claim, req)
        assertTrue(sub is OmniResult.Ok)
        assertEquals(SetupJourneyPhases.FIRST_INFERENCE, api.journey().phase)

        api.cancel(principal())
        assertEquals(SetupJourneyPhases.CANCELLED, api.journey().phase)
        assertEquals(1, orch.cancelled.size)
    }

    @Test
    fun firstInference_planAdmissionRejected() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate()))
        val orch = FakeOrchestratorPort(
            planResult = OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.ADMISSION_REJECTED(
                    message = "resource envelope does not fit",
                ),
            ),
        )
        val api = AutoSetupService(ports(catalog = catalog, orch = orch))
        api.discoverDevice()
        val rec = (api.recommend(prefs()) as OmniResult.Ok).value
        api.selectCandidate(rec.top!!.candidate.candidateId)

        val plan = api.planFirstInference(sampleOrchestrationRequest(requestId()))
        assertTrue(plan is OmniResult.Err)
        assertEquals(SetupJourneyPhases.FAILED, api.journey().phase)
        assertEquals("ADMISSION_REJECTED", api.journey().error?.code?.code)
    }

    @Test
    fun viewModel_projectsActionsAndBusy() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate()))
        val api = AutoSetupService(ports(catalog = catalog))
        val vm = AutoSetupViewModel(api)
        var last = vm.uiState()
        val unsub = vm.observe { last = it }

        vm.onDiscoverDevice()
        assertEquals(SetupJourneyPhases.DEVICE_READY, last.phase)
        assertTrue(last.actions.contains("recommend"))
        assertNotNull(last.humanDeviceSummary)

        vm.onRecommend(prefs())
        assertEquals(SetupJourneyPhases.RECOMMENDATION_READY, last.phase)
        assertTrue(last.recommendationCount >= 1)
        assertTrue(last.topReasonCodes.isNotEmpty())

        unsub()
    }

    @Test
    fun unknownSourceKind_failClosed() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate()))
        val api = AutoSetupService(ports(catalog = catalog))
        api.discoverDevice()
        val rec = (api.recommend(prefs()) as OmniResult.Ok).value
        api.selectCandidate(rec.top!!.candidate.candidateId)
        val r = api.startAcquisition(
            AcquisitionClaim(jobId(), principal(), idem("bad-src"), "d".repeat(64)),
            "MAGIC_USB",
            JobParameters.Download(sourceUrl = "https://example.test/x"),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals("INVALID_REQUEST", (r as OmniResult.Err).error.code.code)
    }

    @Test
    fun idempotentJobCreate_returnsExisting() = runBlocking {
        val catalog = FakeCatalog(candidates = listOf(candidate()))
        val jobs = FakeJobPort()
        val api = AutoSetupService(ports(catalog = catalog, jobs = jobs))
        api.discoverDevice()
        val rec = (api.recommend(prefs()) as OmniResult.Ok).value
        api.selectCandidate(rec.top!!.candidate.candidateId)
        val jid = jobId()
        val key = idem("same-key")
        val digest = "e".repeat(64)
        val p = JobParameters.Download(sourceUrl = "https://example.test/m")
        val first = api.startAcquisition(
            AcquisitionClaim(jid, principal(), key, digest),
            ModelSourceKinds.CATALOG,
            p,
        ) as OmniResult.Ok
        val second = api.startAcquisition(
            AcquisitionClaim(jid, principal(), key, digest),
            ModelSourceKinds.CATALOG,
            p,
        ) as OmniResult.Ok
        assertEquals(first.value.jobId.value, second.value.jobId.value)
        assertEquals(first.value.resourceVersion, second.value.resourceVersion)
    }

    private fun sampleOrchestrationRequest(rid: RequestId): OrchestrationRequest {
        val rev = revision('1')
        val cand = RoutingCandidate(
            candidateId = "cand-primary",
            modelRevisionId = rev,
            installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            backend = "cpu",
            placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
            loadKeyDigest = digest('c'),
            isPrimary = true,
            deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-test-auto-setup-1"),
        )
        return OrchestrationRequest(
            requestId = rid,
            principalId = principal(),
            idempotencyKey = IdempotencyKey.parse("idem-inf-${rid.value}"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest('d'),
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = rev,
            candidates = listOf(cand),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 9_000_000L,
        )
    }
}
