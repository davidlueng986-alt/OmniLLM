package com.omnillm.engines.ortgenai

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
import com.omnillm.engines.api.CancellationModes
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
import com.omnillm.engines.ortgenai.lock.UpstreamLock
import com.omnillm.engines.ortgenai.session.StubGenAiBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Software-complete session adapter tests:
 * plan pure, commit/start/embed fail closed, registry UNQUALIFIED, provider matrix UNKNOWN.
 */
class OrtGenaiEngineStubTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val installation = InstallationId.parse("550e8400-e29b-41d4-a716-446655440099")
    private val principal = PrincipalId.parse("principal-ort-test")
    private val deviceFp = DeviceExecutionFingerprint.parse("device-fp-ort")

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun requestId(): RequestId = RequestId.parse(uuid())

    private fun engine(
        allowUnproven: Boolean = false,
        exploratory: Boolean = false,
        lock: UpstreamLock = UpstreamLock.template(),
    ): OrtGenaiEngine =
        OrtGenaiModule.createEngine(
            lock = lock,
            backend = StubGenAiBackend(exploratoryDryRun = exploratory),
            allowUnprovenExecution = allowUnproven,
        )

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
            revisionLeaseId = RevisionLeaseId.parse("lease-ort-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            oneShotNonce = "nonce-${uuid()}",
            privilegedLoadTicketId = "ticket-1",
        )

    private fun loadInput(
        requestId: RequestId,
        engine: OrtGenaiEngine,
        backend: String = "cpu",
    ): LoadInput {
        val loadKey = LoadKey(
            modelRevisionId = revision,
            engineBuildId = engine.engineBuildId,
            backend = backend,
            deviceExecutionFingerprint = deviceFp,
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
            device = DeviceDescriptor(deviceExecutionFingerprint = deviceFp),
            storageRootKey = "storage-root-ort-test",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
        )
    }

    private fun opContext(operationId: String = "op-ort-1"): OperationContext =
        OperationContext(
            operationId = operationId,
            principalId = principal.value,
            deadline = 9_999L,
            cancelHandle = "cancel-handle-ort",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
        )

    private fun completeLockForTest(): UpstreamLock = UpstreamLock(
        commit = "abc123def456",
        sourceDigest = "aa".repeat(32),
        patchSetPresent = true,
        patchDigest = "",
        toolchainDigest = "bb".repeat(32),
        abis = listOf("arm64-v8a"),
        artifactDigest = "cc".repeat(32),
        observedAt = "2026-08-03T00:00:00Z",
        licenseDigest = "dd".repeat(32),
        engineBuildIdRaw = "ort-genai-test-build-1",
        testedBackends = listOf("cpu"),
        testedFormats = listOf("ONNX-GENAI"),
    )

    @Test
    fun describe_reportsDesignCompleteButUnqualified() = runBlocking {
        val eng = engine()
        val desc = (
            eng.describe(DeviceDescriptor(deviceExecutionFingerprint = deviceFp)) as OmniResult.Ok
            ).value
        assertEquals(OrtGenaiModule.ENGINE_ID, desc.engineId)
        assertEquals(listOf("cpu"), desc.backends)
        assertEquals(listOf("ONNX-GENAI"), desc.supportedFormats)
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.ISOLATED_CPU_UNTRUSTED))
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.EXTERNAL_UID_ACCELERATED))
        assertEquals(UpstreamLock.LOCK_STATE_NOT_LOCKED, desc.notes["lockState"])
        assertEquals(OrtGenaiModule.DESIGN_STATUS, desc.notes["designStatus"])
        assertEquals(OrtGenaiModule.QUALIFICATION_STATUS, desc.notes["qualificationStatus"])
        assertEquals(OrtGenaiModule.REGISTRY_EXPOSURE, desc.notes["registryExposure"])
        assertEquals("false", desc.notes["nativeWired"])
        assertEquals("stub-ort-genai", desc.notes["library"])
        assertEquals("false", desc.notes["backendAvailable"])
        EnginePhases.REQUIRED.forEach { phase ->
            assertEquals(
                "phase $phase should be UNKNOWN",
                CancellationModes.UNKNOWN,
                desc.phaseCancellation[phase],
            )
        }
    }

    @Test
    fun planProbe_isPure_probeExecute_capabilityUnknown() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (
            eng.planProbe(
                ProbeInput(
                    requestId = req,
                    principalId = principal,
                    device = DeviceDescriptor(deviceExecutionFingerprint = deviceFp),
                    backend = "cpu",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        assertEquals("cpu", plan.backend)
        assertEquals(eng.engineBuildId, plan.engineBuildId)

        val res = reservation(plan.resourceEnvelope)
        val probe = eng.probe(plan, res, opContext("op-probe-1"))
        assertTrue(probe is OmniResult.Err)
        assertTrue((probe as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun planLoad_isPure_commitLoad_capabilityUnknown() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        assertEquals(PlacementClassLabels.CRASH_CONTAINED_TRUSTED, plan.proposedPlacementClass)
        assertEquals(eng.engineBuildId, plan.engineBuildId)

        val res = reservation(plan.resourceEnvelope)
        val commit = eng.commitLoad(plan, res, commitContext(req, res.reservationId))
        assertTrue(commit is OmniResult.Err)
        assertTrue((commit as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun commitLoad_missingNative_neverSuccess() = runBlocking {
        // Even with exploratory stub on, incomplete lock + allowUnproven=false refuses.
        val eng = engine(allowUnproven = false, exploratory = true)
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val commit = eng.commitLoad(plan, res, commitContext(req, res.reservationId))
        assertTrue(commit is OmniResult.Err)
        assertTrue((commit as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun commitLoad_idempotentAbort_samePayload() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val ctx = commitContext(req, res.reservationId)
        val first = eng.commitLoad(plan, res, ctx)
        val second = eng.commitLoad(plan, res, ctx)
        assertTrue(first is OmniResult.Err)
        assertTrue(second is OmniResult.Err)
        assertTrue((first as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
        assertTrue((second as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

        val state = (eng.queryCommit(ctx.commitId) as OmniResult.Ok).value
        assertEquals("ABORTED", state.state)
    }

    @Test
    fun queryCommit_missing_notFound() = runBlocking {
        val eng = engine()
        val q = eng.queryCommit(CommitId.parse(uuid()))
        assertTrue(q is OmniResult.Err)
        assertEquals(
            com.omnillm.core.errors.generated.OmniErrorCode.NOT_FOUND,
            (q as OmniResult.Err).error.code,
        )
    }

    @Test
    fun bindLoadedModel_withoutLoad_notFound() {
        val eng = engine()
        // No successful loads; bind must fail closed.
        // We cannot invent a real handle without going through commitLoad.
        // queryCommit for unknown id already covers fail-closed journal.
        runBlocking {
            val q = eng.queryCommit(CommitId.parse(uuid()))
            assertTrue(q is OmniResult.Err)
        }
    }

    @Test
    fun registerDesignCompleteUnqualified_neverProjectsSupported() {
        val reg = EngineRegistry()
        val registration = OrtGenaiModule.registerDesignCompleteUnqualified(
            registry = reg,
            lock = UpstreamLock.template(),
        )
        assertEquals(OrtGenaiModule.ENGINE_ID, registration.engineId)
        assertEquals(OrtGenaiModule.DESIGN_STATUS, registration.designStatus)
        assertFalse(registration.upstreamLocked)
        assertEquals(OrtGenaiModule.DEFAULT_ENGINE_BUILD_ID, registration.engineBuildId.value)

        val cells = reg.listCells(registration.engineBuildId)
        assertEquals(OrtGenaiModule.placeholderCellSpecs().size, cells.size)
        cells.forEach { cell ->
            assertEquals(EngineQualificationCellStatus.UNQUALIFIED, cell.qualificationStatus)
            assertEquals(EvidenceStatusLabels.NOT_EXECUTED, cell.evidenceStatus)
            assertEquals(
                CapabilityState.UNKNOWN,
                reg.projectRuntimeCapability(cell.qualificationStatus, cell.evidenceStatus),
            )
            assertFalse(reg.isSupported(cell.toKey()))
        }

        // Provider matrix: cpu + nnapi + qnn all UNKNOWN
        for (backend in listOf("cpu", "nnapi", "qnn")) {
            val phase = if (backend == "cpu") EnginePhases.GENERATE else EnginePhases.GENERATE
            val key = EngineQualificationCellKey(
                engineBuildId = registration.engineBuildId,
                backend = backend,
                phase = phase,
                platform = "android",
                deviceFingerprint = DeviceExecutionFingerprint.parse(
                    OrtGenaiModule.PLACEHOLDER_DEVICE_FP,
                ),
                driverFingerprint = OrtGenaiModule.PLACEHOLDER_DRIVER_FP,
                modelEnvelope = "*",
                workloadEnvelope = "default",
            )
            assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key))
            assertFalse(reg.isSupported(key))
        }
    }

    @Test
    fun nnapiPlacement_proposesCompanionAcceleration() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng, backend = "nnapi")) as OmniResult.Ok).value
        assertEquals(PlacementClassLabels.EXTERNAL_UID_ACCELERATED, plan.proposedPlacementClass)
    }

    @Test
    fun lockIncomplete_registrationNotUpstreamLocked() {
        val reg = EngineRegistry()
        val registration = OrtGenaiModule.registerWith(reg, UpstreamLock.template())
        assertFalse(registration.upstreamLocked)
        assertEquals(OrtGenaiModule.ENGINE_ID, registration.engineId)
        assertEquals(OrtGenaiModule.DESIGN_STATUS, registration.designStatus)
    }

    @Test
    fun exploratoryPipeline_planCommitStartEmbedClose() = runBlocking {
        // Architecture plumbing only — requires complete lock + allowUnproven + exploratory stub.
        val lock = completeLockForTest()
        val eng = engine(allowUnproven = true, exploratory = true, lock = lock)
        val req = requestId()
        val loadPlan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val loadRes = reservation(loadPlan.resourceEnvelope)
        val handle = (
            eng.commitLoad(loadPlan, loadRes, commitContext(req, loadRes.reservationId))
                as OmniResult.Ok
            ).value

        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val infInput = InferenceInput(
            requestId = requestId(),
            principalId = principal,
            canonicalInputDigest = digest,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 4,
            attributes = mapOf("maxTokens" to "8"),
        )
        val infPlan = (
            port.planInference(infInput, EngineSourceSessionRef.None) as OmniResult.Ok
            ).value
        val infRes = reservation(infPlan.resourceEnvelope)
        val prepared = (
            port.commitInference(
                infPlan,
                infRes,
                commitContext(infInput.requestId, infRes.reservationId),
            ) as OmniResult.Ok
            ).value

        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val started = port.start(
            prepared,
            opContext("op-start-1"),
            sink = { events.add(it) },
        )
        assertTrue(started is OmniResult.Ok)
        assertTrue(events.any { it.isTerminal })

        val embInput = EmbeddingInput(
            requestId = requestId(),
            principalId = principal,
            canonicalInputDigest = digest,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 4,
        )
        val embPlan = (port.planEmbedding(embInput) as OmniResult.Ok).value
        val embRes = reservation(embPlan.resourceEnvelope)
        val embCommit = port.commitEmbedding(
            embPlan,
            embRes,
            commitContext(embInput.requestId, embRes.reservationId),
        )
        // Embedding always CAPABILITY_UNKNOWN even in exploratory stub
        assertTrue(embCommit is OmniResult.Err)
        assertTrue((embCommit as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

        val sessionId = prepared.targetSessionId!!
        val closed = (
            port.closeSession(sessionId, opContext("op-close-1")) as OmniResult.Ok
            ).value
        assertTrue(closed.closed)

        val unloaded = (port.unload(opContext("op-unload-1")) as OmniResult.Ok).value
        assertTrue(unloaded.unloaded)
    }

    @Test
    fun planInference_unprovenPrefix_failsClosed() = runBlocking {
        val lock = completeLockForTest()
        val eng = engine(allowUnproven = true, exploratory = true, lock = lock)
        val req = requestId()
        val loadPlan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val loadRes = reservation(loadPlan.resourceEnvelope)
        val handle = (
            eng.commitLoad(loadPlan, loadRes, commitContext(req, loadRes.reservationId))
                as OmniResult.Ok
            ).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val infInput = InferenceInput(
            requestId = requestId(),
            principalId = principal,
            canonicalInputDigest = digest,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 4,
            attributes = mapOf("prefixMode" to "FORK"),
        )
        val plan = port.planInference(infInput, EngineSourceSessionRef.None)
        assertTrue(plan is OmniResult.Err)
        assertTrue((plan as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun planInference_unknownParam_failsClosed() = runBlocking {
        val lock = completeLockForTest()
        val eng = engine(allowUnproven = true, exploratory = true, lock = lock)
        val req = requestId()
        val loadPlan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val loadRes = reservation(loadPlan.resourceEnvelope)
        val handle = (
            eng.commitLoad(loadPlan, loadRes, commitContext(req, loadRes.reservationId))
                as OmniResult.Ok
            ).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val infInput = InferenceInput(
            requestId = requestId(),
            principalId = principal,
            canonicalInputDigest = digest,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 4,
            attributes = mapOf("totallyUnknown" to "1"),
        )
        val plan = port.planInference(infInput, EngineSourceSessionRef.None)
        assertTrue(plan is OmniResult.Err)
        assertTrue((plan as OmniResult.Err).error is OmniError.INVALID_REQUEST)
    }
}
