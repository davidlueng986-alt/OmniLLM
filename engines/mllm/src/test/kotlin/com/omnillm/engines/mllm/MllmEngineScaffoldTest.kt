package com.omnillm.engines.mllm

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
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.EmbeddingInput
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.EventSink
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.api.ProbeInput
import com.omnillm.engines.mllm.lock.UpstreamLock
import com.omnillm.engines.mllm.mapping.PhaseCancellationMap
import com.omnillm.engines.mllm.server.StubServerBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Scaffold + pipeline tests: design-complete registration, UNQUALIFIED cells,
 * unproven ops → UNKNOWN, private-channel fail-closed without binary.
 */
class MllmEngineScaffoldTest {

    private val digest = Sha256Digest.parse("aa".repeat(32))
    private val revision = ModelRevisionId.parse("bb".repeat(32))
    private val installation = InstallationId.parse("550e8400-e29b-41d4-a716-446655440001")
    private val principal = PrincipalId.parse("principal-mllm-test")
    private val deviceFp = DeviceExecutionFingerprint.parse("device-fp-mllm")

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun requestId(): RequestId = RequestId.parse(uuid())

    private fun engine(): MllmEngine =
        MllmModule.createEngine(
            lock = UpstreamLock.template(),
            server = StubServerBackend(lockComplete = false, exploratoryDryRun = false),
            allowUnprovenExecution = false,
        )

    private fun completeLock(): UpstreamLock =
        UpstreamLock(
            schemaVersion = 1,
            engineId = "mllm",
            lockState = UpstreamLock.LOCK_STATE_LOCKED,
            repository = UpstreamLock.DEFAULT_REPOSITORY,
            commit = "abc123def456",
            sourceDigest = "11".repeat(32),
            patchDigest = "",
            patchSetPresent = true,
            observedAt = "2026-08-06T00:00:00Z",
            licenseDigest = "22".repeat(32),
            goVersion = "1.22.5",
            toolchainDigest = "33".repeat(32),
            abis = listOf("arm64-v8a"),
            artifactDigest = "44".repeat(32),
            serverProtocolDigest = "55".repeat(32),
            engineBuildIdRaw = "mllm-build-test-1",
            testedPlatform = "android",
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
            revisionLeaseId = RevisionLeaseId.parse("lease-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            oneShotNonce = "nonce-${uuid()}",
            privilegedLoadTicketId = "ticket-1",
        )

    private fun loadInput(requestId: RequestId, eng: MllmEngine): LoadInput {
        val loadKey = LoadKey(
            modelRevisionId = revision,
            engineBuildId = eng.engineBuildId,
            backend = "cpu",
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
            storageRootKey = "storage-root-mllm",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
        )
    }

    private fun op(id: String = "op-1"): OperationContext =
        OperationContext(
            operationId = id,
            principalId = principal.value,
            deadline = 9_999L,
            cancelHandle = "cancel-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
        )

    @Test
    fun describe_reportsBaselineUnqualifiedUnknown() = runBlocking {
        val desc = (engine().describe(DeviceDescriptor(deviceFp)) as OmniResult.Ok).value
        assertEquals(MllmModule.ENGINE_ID, desc.engineId)
        assertEquals("BASELINE", desc.notes["designStatus"])
        assertEquals("UNQUALIFIED", desc.notes["qualificationStatus"])
        assertEquals("UNKNOWN", desc.notes["registryExposure"])
        assertEquals("UNKNOWN", desc.notes["runtimeCapabilityDefault"])
        assertEquals(UpstreamLock.LOCK_STATE_NOT_LOCKED, desc.notes["lockState"])
        assertEquals("omnillm.mllm.private-channel", desc.notes["protocolId"])
        assertTrue(desc.phaseCancellation.values.all { it == CancellationModes.UNKNOWN })
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
    }

    @Test
    fun planProbe_isPureAndOk() = runBlocking {
        val eng = engine()
        val plan = eng.planProbe(
            ProbeInput(
                requestId = requestId(),
                principalId = principal,
                device = DeviceDescriptor(deviceFp),
                backend = "cpu",
                runtimeEpoch = 1L,
            ),
        )
        assertTrue(plan is OmniResult.Ok)
    }

    @Test
    fun probe_failsClosedAsCapabilityUnknown() = runBlocking {
        val eng = engine()
        val plan = (
            eng.planProbe(
                ProbeInput(
                    requestId = requestId(),
                    principalId = principal,
                    device = DeviceDescriptor(deviceFp),
                    backend = "cpu",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        val res = reservation(plan.resourceEnvelope)
        val result = eng.probe(plan, res, op("op-probe-1"))
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun commitLoad_failsClosedAndIsQueryable() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val commit = commitContext(req, res.reservationId)
        val result = eng.commitLoad(plan, res, commit)
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

        val query = (eng.queryCommit(commit.commitId) as OmniResult.Ok).value
        assertEquals("ABORTED", query.state)
    }

    @Test
    fun bindLoadedModel_failsClosedWhenNotLoaded() {
        val eng = engine()
        val handle = LoadedModelHandle(
            loadedModelId = LoadedModelId("mllm-lm-test"),
            installationId = installation,
            engineBuildId = eng.engineBuildId,
            loadKey = LoadKey(
                modelRevisionId = revision,
                engineBuildId = eng.engineBuildId,
                backend = "cpu",
                deviceExecutionFingerprint = deviceFp,
                templateEpoch = 1L,
                tokenizerEpoch = 1L,
                loadConfigurationDigest = digest,
            ),
            allocationHandleId = AllocationHandleId.parse("alloc-mllm-1"),
            placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            runtimeEpoch = 1L,
        )
        val bound = eng.bindLoadedModel(handle)
        assertTrue(bound is OmniResult.Err)
        assertTrue((bound as OmniResult.Err).error is OmniError.NOT_FOUND)
    }

    @Test
    fun planInference_rejectsUnqualifiedPrefix() = runBlocking {
        val eng = engine()
        // Port constructed for plan-only tests without successful load.
        val port = MllmLoadedModelPort(
            engine = eng,
            bound = MllmEngine.BoundLoadedModel(
                handle = LoadedModelHandle(
                    loadedModelId = LoadedModelId("mllm-lm-plan"),
                    installationId = installation,
                    engineBuildId = eng.engineBuildId,
                    loadKey = LoadKey(
                        modelRevisionId = revision,
                        engineBuildId = eng.engineBuildId,
                        backend = "cpu",
                        deviceExecutionFingerprint = deviceFp,
                        templateEpoch = 1L,
                        tokenizerEpoch = 1L,
                        loadConfigurationDigest = digest,
                    ),
                    allocationHandleId = AllocationHandleId.parse("alloc-plan"),
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    runtimeEpoch = 1L,
                ),
                modelToken = com.omnillm.engines.mllm.server.ServerModelToken("stub-token"),
                backend = "cpu",
            ),
        )
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
    fun planInference_rejectsUnknownParameter() = runBlocking {
        val eng = engine()
        val port = MllmLoadedModelPort(
            engine = eng,
            bound = MllmEngine.BoundLoadedModel(
                handle = LoadedModelHandle(
                    loadedModelId = LoadedModelId("mllm-lm-plan2"),
                    installationId = installation,
                    engineBuildId = eng.engineBuildId,
                    loadKey = LoadKey(
                        modelRevisionId = revision,
                        engineBuildId = eng.engineBuildId,
                        backend = "cpu",
                        deviceExecutionFingerprint = deviceFp,
                        templateEpoch = 1L,
                        tokenizerEpoch = 1L,
                        loadConfigurationDigest = digest,
                    ),
                    allocationHandleId = AllocationHandleId.parse("alloc-plan2"),
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    runtimeEpoch = 1L,
                ),
                modelToken = com.omnillm.engines.mllm.server.ServerModelToken("stub-token-2"),
                backend = "cpu",
            ),
        )
        val result = port.planInference(
            InferenceInput(
                requestId = requestId(),
                principalId = principal,
                canonicalInputDigest = digest,
                runtimeEpoch = 1L,
                revocationEpoch = 0L,
                deadlineMonotonic = 100L,
                attributes = mapOf("totallyUnknownParam" to "x"),
            ),
            EngineSourceSessionRef.None,
        )
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.INVALID_REQUEST)
    }

    @Test
    fun commitInferenceAndEmbed_failClosed() = runBlocking {
        val eng = engine()
        val port = MllmLoadedModelPort(
            engine = eng,
            bound = MllmEngine.BoundLoadedModel(
                handle = LoadedModelHandle(
                    loadedModelId = LoadedModelId("mllm-lm-x"),
                    installationId = installation,
                    engineBuildId = eng.engineBuildId,
                    loadKey = LoadKey(
                        modelRevisionId = revision,
                        engineBuildId = eng.engineBuildId,
                        backend = "cpu",
                        deviceExecutionFingerprint = deviceFp,
                        templateEpoch = 1L,
                        tokenizerEpoch = 1L,
                        loadConfigurationDigest = digest,
                    ),
                    allocationHandleId = AllocationHandleId.parse("alloc-x"),
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    runtimeEpoch = 1L,
                ),
                modelToken = com.omnillm.engines.mllm.server.ServerModelToken("stub-x"),
                backend = "cpu",
            ),
        )
        val infPlan = (
            port.planInference(
                InferenceInput(
                    requestId = requestId(),
                    principalId = principal,
                    canonicalInputDigest = digest,
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    deadlineMonotonic = 100L,
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value
        val res = reservation(infPlan.resourceEnvelope)
        val commit = commitContext(infPlan.requestId, res.reservationId)
        val inf = port.commitInference(infPlan, res, commit)
        assertTrue(inf is OmniResult.Err)
        assertTrue((inf as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)

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
        val embCommit = commitContext(embPlan.requestId, res.reservationId)
        val emb = port.commitEmbedding(embPlan, res, embCommit)
        assertTrue(emb is OmniResult.Err)
        assertTrue((emb as OmniResult.Err).error is OmniError.CAPABILITY_UNKNOWN)
    }

    @Test
    fun exploratoryPipeline_planCommitStartClose_doesNotElevateCapability() = runBlocking {
        val lock = completeLock()
        val stub = StubServerBackend(lockComplete = true, exploratoryDryRun = true)
        val eng = MllmModule.createEngine(
            lock = lock,
            server = stub,
            allowUnprovenExecution = true,
        )
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val commit = commitContext(req, res.reservationId)
        val handle = (eng.commitLoad(plan, res, commit) as OmniResult.Ok).value
        val port = (eng.bindLoadedModel(handle) as OmniResult.Ok).value

        val infPlan = (
            port.planInference(
                InferenceInput(
                    requestId = requestId(),
                    principalId = principal,
                    canonicalInputDigest = digest,
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    deadlineMonotonic = 100L,
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value
        val infRes = reservation(infPlan.resourceEnvelope)
        val infCommit = commitContext(infPlan.requestId, infRes.reservationId)
        val prepared = (port.commitInference(infPlan, infRes, infCommit) as OmniResult.Ok).value

        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val sink = EventSink { e -> events.add(e) }
        val started = port.start(prepared, op(prepared.operationId), sink)
        assertTrue(started is OmniResult.Ok)
        assertTrue(events.any { it.kind == EngineEventKinds.METADATA })
        assertTrue(events.any { it.kind == EngineEventKinds.DELTA })
        assertTrue(events.any { it.kind == EngineEventKinds.TERMINAL })

        val closed = port.closeSession(prepared.targetSessionId!!, op("op-close"))
        assertTrue(closed is OmniResult.Ok)
        val unloaded = port.unload(op("op-unload"))
        assertTrue(unloaded is OmniResult.Ok)

        // Exploratory plumbing must not invent SUPPORTED cells.
        val reg = EngineRegistry()
        MllmModule.registerWith(reg, lock, eng.engineBuildId, seedCells = true)
        reg.listCells(eng.engineBuildId).forEach { cell ->
            assertEquals(EngineQualificationCellStatus.UNQUALIFIED, cell.qualificationStatus)
            assertFalse(reg.isSupported(cell.toKey()))
            assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(cell.toKey()))
        }
    }

    @Test
    fun registerWith_seedsUnqualifiedCellsProjectingUnknown() {
        val reg = EngineRegistry()
        val registration = MllmModule.registerWith(reg, UpstreamLock.template())
        assertEquals(MllmModule.ENGINE_ID, registration.engineId)
        assertEquals(MllmModule.DESIGN_STATUS, registration.designStatus)
        assertFalse(registration.upstreamLocked)

        val cells = reg.listCells(registration.engineBuildId)
        assertTrue(cells.isNotEmpty())
        cells.forEach { cell ->
            assertEquals(EngineQualificationCellStatus.UNQUALIFIED, cell.qualificationStatus)
            assertEquals(EvidenceStatusLabels.NOT_EXECUTED, cell.evidenceStatus)
            assertEquals(CancellationModes.UNKNOWN, cell.cancellationMode)
            assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(cell.toKey()))
            assertFalse(reg.isSupported(cell.toKey()))
        }
    }

    @Test
    fun phaseCancellation_defaultsUnknownAndRequiresWorker() {
        val measured = PhaseCancellationMap.measuredModes()
        assertTrue(PhaseCancellationMap.anyPhaseRequiresKillableWorker(measured))
        EnginePhases.REQUIRED.forEach { phase ->
            assertEquals(CancellationModes.UNKNOWN, measured[phase])
        }
    }

    @Test
    fun lockTemplate_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals("mllm", lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
    }

    @Test
    fun completeLock_requiresServerProtocolAndGo() {
        val base = completeLock()
        assertTrue(base.isComplete())
        assertFalse(base.copy(serverProtocolDigest = null).isComplete())
        assertFalse(base.copy(goVersion = null).isComplete())
        assertFalse(base.copy(engineBuildIdRaw = null).isComplete())
    }

    @Test
    fun missingBinary_neverSucceedsProbe() = runBlocking {
        // Default stub isAvailable=false and exploratory off — fail closed.
        val eng = engine()
        assertFalse(eng.server.isAvailable())
        val plan = (
            eng.planProbe(
                ProbeInput(
                    requestId = requestId(),
                    principalId = principal,
                    device = DeviceDescriptor(deviceFp),
                    backend = "cpu",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        val result = eng.probe(plan, reservation(plan.resourceEnvelope), op())
        assertTrue(result is OmniResult.Err)
    }
}
