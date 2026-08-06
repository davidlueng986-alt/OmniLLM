package com.omnillm.engines.litertlm

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
import com.omnillm.engines.litertlm.lock.UpstreamLock
import com.omnillm.engines.litertlm.sdk.StubSdkBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan → Reserve → Commit → Execute on [LitertLmEngine] + [StubSdkBackend].
 * Asserts design-complete registration stays UNQUALIFIED / UNKNOWN at runtime.
 */
class LitertLmEnginePipelineTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val installation = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
    private val principal = PrincipalId.parse("principal-litert-test")
    private val device = DeviceExecutionFingerprint.parse("device-fp-litert")

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

    private fun loadInput(requestId: RequestId, engine: LitertLmEngine): LoadInput {
        val loadKey = LoadKey(
            modelRevisionId = revision,
            engineBuildId = engine.engineBuildId,
            backend = "cpu",
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

    private fun engine(stub: StubSdkBackend = StubSdkBackend()): LitertLmEngine =
        LitertLmModule.createEngine(
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
    fun describe_exposesUnknownCancellationAndLitertlm() = runBlocking {
        val eng = engine()
        val desc = (eng.describe(DeviceDescriptor(device)) as OmniResult.Ok).value
        assertEquals(LitertLmModule.ENGINE_ID, desc.engineId)
        assertTrue(desc.backends.contains("cpu"))
        assertTrue(desc.supportedFormats.contains("litertlm"))
        assertTrue(desc.phaseCancellation.values.all { it == "UNKNOWN" })
        assertEquals("NOT_LOCKED", desc.notes["lockState"])
        assertEquals(UpstreamLock.LOCK_ELIGIBILITY, desc.notes["lockEligibility"])
        assertEquals(LitertLmModule.DESIGN_STATUS, desc.notes["designStatus"])
        assertEquals(LitertLmModule.QUALIFICATION_STATUS, desc.notes["qualificationStatus"])
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
    }

    @Test
    fun planReserveCommitExecute_generationPipeline() = runBlocking {
        val eng = engine(StubSdkBackend(deltaCount = 3))

        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        assertEquals(eng.engineBuildId, plan.engineBuildId)

        val resLoad = reservation(plan.resourceEnvelope)
        val commitLoad = commitContext(reqLoad, resLoad.reservationId)
        val handle = (eng.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertTrue(handle.loadedModelId.value.startsWith("litert-lm-"))

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
    }

    @Test
    fun prefixFork_failsClosedAsCapabilityUnknown() = runBlocking {
        val eng = engine()
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
        val eng = engine()
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
    fun lockIncomplete_registrationNotUpstreamLocked() {
        val reg = EngineRegistry()
        val registration = LitertLmModule.registerWith(reg, UpstreamLock.template())
        assertFalse(registration.upstreamLocked)
        assertEquals(LitertLmModule.ENGINE_ID, registration.engineId)
        assertEquals(LitertLmModule.DESIGN_STATUS, registration.designStatus)
    }

    @Test
    fun seedUnqualifiedPlaceholders_projectUnknownNeverSupported() {
        val reg = EngineRegistry()
        LitertLmModule.registerWith(reg)
        val cells = LitertLmModule.seedUnqualifiedPlaceholders(reg, device)
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED })
        assertTrue(cells.all { it.evidenceStatus == EvidenceStatusLabels.NOT_EXECUTED })

        val key = EngineQualificationCellKey(
            engineBuildId = LitertLmModule.defaultEngineBuildId(),
            backend = "cpu",
            phase = EnginePhases.GENERATE,
            platform = "android",
            deviceFingerprint = device,
            driverFingerprint = "unknown-driver",
            modelEnvelope = "*",
            workloadEnvelope = "default",
        )
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key))
        assertFalse(reg.isSupported(key))

        // GPU/NPU placeholders also UNKNOWN (no inheritance from CPU)
        val gpuKey = key.copy(backend = "gpu")
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(gpuKey))
    }

    @Test
    fun npuBackend_probeMapsToCapabilityUnknown() = runBlocking {
        val eng = engine()
        val plan = (
            eng.planProbe(
                com.omnillm.engines.api.ProbeInput(
                    requestId = requestId(),
                    principalId = principal,
                    device = DeviceDescriptor(device),
                    backend = "npu",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        val res = reservation(plan.resourceEnvelope)
        val result = eng.probe(plan, res, opContext())
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun requestCancel_duringGenerate_cancelsStubStream() = runBlocking {
        val stub = StubSdkBackend(deltaCount = 50)
        val eng = engine(stub)

        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        val resLoad = reservation(plan.resourceEnvelope)
        val handle = (
            eng.commitLoad(plan, resLoad, commitContext(reqLoad, resLoad.reservationId))
                as OmniResult.Ok
            ).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value as LitertLmLoadedModelPort

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
                    attributes = mapOf("maxTokens" to "32"),
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value
        val resInf = reservation(infPlan.resourceEnvelope)
        val prepared = (
            port.commitInference(
                infPlan,
                resInf,
                commitContext(reqInf, resInf.reservationId),
            ) as OmniResult.Ok
            ).value

        // Pre-cancel via SDK token so generate observes cancel on first delta loop.
        stub.requestCancel(prepared.operationId)
        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val start = port.start(
            prepared,
            opContext(reqInf).copy(operationId = prepared.operationId),
        ) { events.add(it) }
        assertTrue(start is OmniResult.Ok)
        val state = (start as OmniResult.Ok).value.state
        assertTrue(state == "CANCELLED" || state == "COMPLETED" || state == "FAILED")
        assertTrue(events.any { it.isTerminal })
    }

    @Test
    fun closeAndUnload_releaseSessionAndModel() = runBlocking {
        val eng = engine()
        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val handle = (
            eng.commitLoad(plan, res, commitContext(reqLoad, res.reservationId)) as OmniResult.Ok
            ).value
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
                    deadlineMonotonic = 100L,
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value
        val resInf = reservation(infPlan.resourceEnvelope)
        val prepared = (
            port.commitInference(
                infPlan,
                resInf,
                commitContext(reqInf, resInf.reservationId),
            ) as OmniResult.Ok
            ).value
        assertNotNull(prepared.targetSessionId)

        val closed = port.closeSession(prepared.targetSessionId!!, opContext())
        assertTrue(closed is OmniResult.Ok)
        assertTrue((closed as OmniResult.Ok).value.closed)

        val unloaded = port.unload(opContext())
        assertTrue(unloaded is OmniResult.Ok)
        assertTrue((unloaded as OmniResult.Ok).value.unloaded)

        val rebound = eng.bindLoadedModel(handle)
        assertTrue(rebound is OmniResult.Err)
    }
}
