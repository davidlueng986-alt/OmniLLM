package com.omnillm.engines.mlcllm

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.EmbeddingInput
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCellKey
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.api.ProbeInput
import com.omnillm.engines.mlcllm.lock.UpstreamLock
import com.omnillm.engines.mlcllm.runtime.StubRuntimeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan → Reserve → Commit → Execute on [MlcLlmEngine] + [StubRuntimeBackend].
 * Asserts design-complete registration stays UNQUALIFIED / UNKNOWN at runtime.
 */
class MlcLlmEnginePipelineTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val installation = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
    private val principal = PrincipalId.parse("principal-mlc-test")
    private val device = DeviceExecutionFingerprint.parse("device-fp-mlc")

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun requestId(): RequestId = RequestId.parse(uuid())

    private fun reservation(envelope: ResourceEnvelope): Reservation =
        Reservation(
            reservationId = ReservationId.parse("res-${uuid().take(8)}"),
            principalId = principal.value,
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            nonce = "nonce-${uuid().take(8)}",
            deadlineMonotonic = Long.MAX_VALUE / 4,
            envelope = envelope,
        )

    private fun commitContext(requestId: RequestId, reservationId: ReservationId): CommitContext =
        CommitContext(
            commitId = CommitId.parse(uuid()),
            requestId = requestId,
            principalId = principal,
            reservationId = reservationId,
            revisionLeaseId = RevisionLeaseId.parse("lease-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            oneShotNonce = "nonce-${uuid()}",
            privilegedLoadTicketId = "ticket-1",
        )

    private fun loadInput(requestId: RequestId, engine: MlcLlmEngine, backend: String = "cpu"): LoadInput {
        val loadKey = LoadKey(
            modelRevisionId = revision,
            engineBuildId = engine.engineBuildId,
            backend = backend,
            deviceExecutionFingerprint = device,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            loadConfigurationDigest = digest,
        )
        return LoadInput(
            requestId = requestId,
            principalId = principal,
            installationId = installation,
            modelRevisionId = revision,
            loadKey = loadKey,
            device = DeviceDescriptor(deviceExecutionFingerprint = device),
            storageRootKey = "storage-root-test",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
        )
    }

    /** Default production-like engine: exploratory off → UNKNOWN execute. */
    private fun engineFailClosed(): MlcLlmEngine =
        MlcLlmModule.createEngine(
            lock = UpstreamLock.template(),
            backend = StubRuntimeBackend(exploratoryDryRun = false),
        )

    /** Architecture wiring engine: CPU exploratory dry-run only. */
    private fun engineExploratory(stub: StubRuntimeBackend = StubRuntimeBackend(exploratoryDryRun = true)): MlcLlmEngine =
        MlcLlmModule.createEngine(
            lock = UpstreamLock.template(),
            backend = stub,
        )

    private fun opContext(requestId: RequestId = requestId()): OperationContext =
        OperationContext(
            operationId = "op-${uuid().take(8)}",
            principalId = principal.value,
            deadline = Long.MAX_VALUE / 4,
            cancelHandle = "cancel-${uuid().take(8)}",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
        )

    @Test
    fun describe_exposesUnknownCancellationAndDesignBaseline() = runBlocking {
        val eng = engineFailClosed()
        val desc = (eng.describe(DeviceDescriptor(device)) as OmniResult.Ok).value
        assertEquals(MlcLlmModule.ENGINE_ID, desc.engineId)
        assertTrue(desc.backends.contains("cpu"))
        assertTrue(desc.backends.contains("opencl"))
        assertTrue(desc.supportedFormats.contains("mlc-model-lib"))
        assertTrue(desc.phaseCancellation.values.all { it == "UNKNOWN" })
        assertEquals("NOT_LOCKED", desc.notes["lockState"])
        assertEquals(UpstreamLock.LOCK_ELIGIBILITY, desc.notes["lockEligibility"])
        assertEquals(MlcLlmModule.DESIGN_STATUS, desc.notes["designStatus"])
        assertEquals(MlcLlmModule.QUALIFICATION_STATUS, desc.notes["qualificationStatus"])
        assertEquals("UNKNOWN", desc.notes["registryExposure"])
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.EXTERNAL_UID_ACCELERATED))
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
    }

    @Test
    fun planProbeAndPlanLoad_arePureAndSucceed() = runBlocking {
        val eng = engineFailClosed()
        val req = requestId()
        val probePlan = eng.planProbe(
            ProbeInput(
                requestId = req,
                principalId = principal,
                device = DeviceDescriptor(device),
                backend = "cpu",
                runtimeEpoch = 1L,
            ),
        )
        assertTrue(probePlan is OmniResult.Ok)

        val loadPlan = eng.planLoad(loadInput(requestId(), eng))
        assertTrue(loadPlan is OmniResult.Ok)
        val plan = (loadPlan as OmniResult.Ok).value
        assertEquals(PlacementClassLabels.CRASH_CONTAINED_TRUSTED, plan.proposedPlacementClass)
    }

    @Test
    fun defaultStub_probeAndCommitLoad_returnCapabilityUnknown() = runBlocking {
        val eng = engineFailClosed()
        val req = requestId()
        val probePlan = (eng.planProbe(
            ProbeInput(
                requestId = req,
                principalId = principal,
                device = DeviceDescriptor(device),
                backend = "cpu",
                runtimeEpoch = 1L,
            ),
        ) as OmniResult.Ok).value
        val probeRes = eng.probe(
            probePlan,
            reservation(probePlan.resourceEnvelope),
            opContext(req),
        )
        assertTrue(probeRes is OmniResult.Err)
        assertTrue((probeRes as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

        val loadPlan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(loadPlan.resourceEnvelope)
        val commit = eng.commitLoad(loadPlan, res, commitContext(req, res.reservationId))
        assertTrue(commit is OmniResult.Err)
        assertTrue((commit as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun planReserveCommitExecute_generationPipeline_exploratory() = runBlocking {
        val eng = engineExploratory(StubRuntimeBackend(exploratoryDryRun = true, deltaCount = 3))

        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        assertEquals(eng.engineBuildId, plan.engineBuildId)

        val resLoad = reservation(plan.resourceEnvelope)
        val commitLoad = commitContext(reqLoad, resLoad.reservationId)
        val handle = (eng.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertTrue(handle.loadedModelId.value.startsWith("mlc-lm-"))

        // Idempotent re-commit
        val again = (eng.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertEquals(handle.loadedModelId, again.loadedModelId)

        val q = (eng.queryCommit(commitLoad.commitId) as OmniResult.Ok).value
        assertEquals("COMMITTED", q.state)

        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val reqInf = requestId()
        val infPlan = (
            port.planInference(
                InferenceInput(
                    requestId = reqInf,
                    principalId = principal,
                    canonicalInputDigest = digest,
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    deadlineMonotonic = Long.MAX_VALUE / 4,
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value

        val resInf = reservation(infPlan.resourceEnvelope)
        val commitInf = commitContext(reqInf, resInf.reservationId)
        val prepared = (
            port.commitInference(infPlan, resInf, commitInf) as OmniResult.Ok
            ).value

        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val start = port.start(
            prepared,
            opContext(reqInf).copy(operationId = prepared.operationId),
        ) { events.add(it) }
        assertTrue(start is OmniResult.Ok)
        assertTrue(events.any { it.isTerminal })
        assertTrue(events.any { it.kind == "delta" })

        // close + unload
        val sessionId = prepared.targetSessionId!!
        val closed = port.closeSession(sessionId, opContext())
        assertTrue(closed is OmniResult.Ok)
        assertTrue((closed as OmniResult.Ok).value.closed)

        val unloaded = port.unload(opContext())
        assertTrue(unloaded is OmniResult.Ok)
        assertTrue((unloaded as OmniResult.Ok).value.unloaded)
    }

    @Test
    fun prefixFork_failsClosedAsCapabilityUnknown() = runBlocking {
        val eng = engineExploratory()
        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val handle = (
            eng.commitLoad(plan, res, commitContext(reqLoad, res.reservationId)) as OmniResult.Ok
            ).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val result = port.planInference(
            InferenceInput(
                requestId = requestId(),
                principalId = principal,
                canonicalInputDigest = digest,
                runtimeEpoch = 1L,
                revocationEpoch = 0L,
                deadlineMonotonic = 100L,
                attributes = mapOf("prefixMode" to "FORK"),
            ),
            EngineSourceSessionRef.None,
        )
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun embed_commitFailsClosedAsCapabilityUnknown() = runBlocking {
        val eng = engineExploratory()
        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val handle = (
            eng.commitLoad(plan, res, commitContext(reqLoad, res.reservationId)) as OmniResult.Ok
            ).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val embPlan = (
            port.planEmbedding(
                EmbeddingInput(
                    requestId = requestId(),
                    principalId = principal,
                    canonicalInputDigest = digest,
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    deadlineMonotonic = 100L,
                ),
            ) as OmniResult.Ok
            ).value

        val resEmb = reservation(embPlan.resourceEnvelope)
        val commit = commitContext(embPlan.requestId, resEmb.reservationId)
        val result = port.commitEmbedding(embPlan, resEmb, commit)
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun acceleratorBackend_probeAndLoad_stayCapabilityUnknown_evenExploratory() = runBlocking {
        val eng = engineExploratory()
        val plan = (
            eng.planProbe(
                ProbeInput(
                    requestId = requestId(),
                    principalId = principal,
                    device = DeviceDescriptor(device),
                    backend = "opencl",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        val res = reservation(plan.resourceEnvelope)
        val probe = eng.probe(plan, res, opContext())
        assertTrue(probe is OmniResult.Err)
        assertTrue((probe as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

        val reqLoad = requestId()
        val loadPlan = (eng.planLoad(loadInput(reqLoad, eng, backend = "vulkan")) as OmniResult.Ok).value
        assertEquals(PlacementClassLabels.EXTERNAL_UID_ACCELERATED, loadPlan.proposedPlacementClass)
        val loadRes = reservation(loadPlan.resourceEnvelope)
        val commit = eng.commitLoad(loadPlan, loadRes, commitContext(reqLoad, loadRes.reservationId))
        assertTrue(commit is OmniResult.Err)
        assertTrue((commit as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun lockIncomplete_registrationNotUpstreamLocked() {
        val reg = EngineRegistry()
        val registration = MlcLlmModule.registerWith(reg, UpstreamLock.template())
        assertFalse(registration.upstreamLocked)
        assertEquals(MlcLlmModule.ENGINE_ID, registration.engineId)
        assertEquals(MlcLlmModule.DESIGN_STATUS, registration.designStatus)
    }

    @Test
    fun seedUnqualifiedPlaceholders_projectUnknownNeverSupported() {
        val reg = EngineRegistry()
        val registration = MlcLlmModule.registerWith(reg, seedPlaceholderCells = false)
        val cells = MlcLlmModule.seedUnqualifiedPlaceholders(reg, device, registration.engineBuildId)
        assertTrue(cells.isNotEmpty())
        assertEquals(QualificationCells.PLACEHOLDER_ROWS.size, cells.size)
        assertTrue(cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED })
        assertTrue(cells.all { it.evidenceStatus == EvidenceStatusLabels.NOT_EXECUTED })
        assertTrue(cells.all { it.cancellationMode == "UNKNOWN" })

        val sample = cells.first()
        val key = EngineQualificationCellKey(
            engineBuildId = sample.engineBuildId,
            backend = sample.backend,
            phase = sample.phase,
            platform = sample.platform,
            deviceFingerprint = sample.deviceFingerprint,
            driverFingerprint = sample.driverFingerprint,
            modelEnvelope = sample.modelEnvelope,
            workloadEnvelope = sample.workloadEnvelope,
        )
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key))
        assertFalse(reg.isSupported(key))

        // opencl GENERATE remains UNKNOWN (no silent inheritance from CPU)
        val openclKey = EngineQualificationCellKey(
            engineBuildId = registration.engineBuildId,
            backend = "opencl",
            phase = EnginePhases.GENERATE,
            platform = "android",
            deviceFingerprint = device,
            driverFingerprint = sample.driverFingerprint,
            modelEnvelope = "*",
            workloadEnvelope = "default",
        )
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(openclKey))
    }

    @Test
    fun queryCommit_missing_failsClosed() = runBlocking {
        val eng = engineFailClosed()
        val result = eng.queryCommit(CommitId.parse(uuid()))
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.NOT_FOUND)
    }
}
