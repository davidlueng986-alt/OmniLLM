package com.omnillm.engines.llamacpp

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
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.llamacpp.lock.UpstreamLock
import com.omnillm.engines.llamacpp.native.JniNativeMapping
import com.omnillm.engines.llamacpp.native.StubNativeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan → Reserve → Commit → Execute on [LlamaCppEngine] + [StubNativeBackend].
 */
class LlamaCppEnginePipelineTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val installation = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
    private val principal = PrincipalId.parse("principal-llama-test")
    private val device = DeviceExecutionFingerprint.parse("device-fp-llama")

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

    private fun loadInput(
        requestId: RequestId,
        engine: LlamaCppEngine,
        storageRootKey: String = "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}",
        resolvedModelPath: String? = "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}",
        modelFd: Int = -1,
    ): LoadInput {
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
            storageRootKey = storageRootKey,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            resolvedModelPath = resolvedModelPath,
            modelFd = modelFd,
        )
    }

    private fun engine(stub: StubNativeBackend = StubNativeBackend()): LlamaCppEngine =
        LlamaCppModule.createEngine(
            lock = UpstreamLock.template(),
            backend = stub,
        )

    @Test
    fun describe_exposesUnknownCancellationAndGguf() = runBlocking {
        val eng = engine()
        val desc = (eng.describe(DeviceDescriptor(device)) as OmniResult.Ok).value
        assertEquals(LlamaCppModule.ENGINE_ID, desc.engineId)
        assertTrue(desc.backends.contains("cpu"))
        assertTrue(desc.supportedFormats.contains("GGUF"))
        assertTrue(desc.phaseCancellation.values.all { it == "UNKNOWN" })
        assertEquals("NOT_LOCKED", desc.notes["lockState"])
        // UNKNOWN cancellation ⇒ not privileged-only proposal
        assertTrue(desc.placementClasses.contains(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
    }

    @Test
    fun planReserveCommitExecute_generationPipeline() = runBlocking {
        val eng = engine(StubNativeBackend(deltaCount = 3))

        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        assertEquals(eng.engineBuildId, plan.engineBuildId)

        val resLoad = reservation(plan.resourceEnvelope)
        val commitLoad = commitContext(reqLoad, resLoad.reservationId)
        val handle = (eng.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertTrue(handle.loadedModelId.value.startsWith("llama-lm-"))

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
                    deadlineMonotonic = 9_999L,
                ),
                EngineSourceSessionRef.None,
            ) as OmniResult.Ok
            ).value

        val resInf = reservation(infPlan.resourceEnvelope)
        val commitInf = commitContext(reqInf, resInf.reservationId)
        val prepared = (port.commitInference(infPlan, resInf, commitInf) as OmniResult.Ok).value

        val events = mutableListOf<EngineEvent>()
        val op = OperationContext(
            operationId = prepared.operationId,
            principalId = principal.value,
            deadline = 9_999L,
            cancelHandle = "cancel-handle-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
        )
        val opHandle = (
            port.start(prepared, op) { events.add(it) } as OmniResult.Ok
            ).value
        assertEquals("COMPLETED", opHandle.state)
        assertTrue(events.any { it.kind == EngineEventKinds.METADATA })
        assertEquals(3, events.count { it.kind == EngineEventKinds.DELTA })
        assertEquals(1, events.count { it.kind == EngineEventKinds.TERMINAL })
        assertTrue(events.last().isTerminal)

        // Idempotent start
        val startAgain = (port.start(prepared, op) { } as OmniResult.Ok).value
        assertEquals(opHandle.operationId, startAgain.operationId)

        val unload = (
            port.unload(
                OperationContext(
                    operationId = "op-unload",
                    principalId = principal.value,
                    deadline = 9_999L,
                    cancelHandle = "ch",
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                ),
            ) as OmniResult.Ok
            ).value
        assertTrue(unload.unloaded)
    }

    @Test
    fun unsupportedParameter_failsClosed() = runBlocking {
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
                attributes = mapOf("gpuLayers" to "99"),
            ),
            EngineSourceSessionRef.None,
        )
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun unknownParameter_failsClosed() = runBlocking {
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
                attributes = mapOf("weirdParam" to "x"),
            ),
            EngineSourceSessionRef.None,
        )
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.INVALID_REQUEST)
    }

    @Test
    fun embedding_unsupportedByDefault() = runBlocking {
        val eng = engine(StubNativeBackend(embeddingsEnabled = false))
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

        val embRes = reservation(embPlan.resourceEnvelope)
        val embCommit = commitContext(embPlan.requestId, embRes.reservationId)
        val embResult = port.commitEmbedding(embPlan, embRes, embCommit)
        assertTrue(embResult is OmniResult.Err)
        assertTrue((embResult as OmniResult.Err).error is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun commitLoad_payloadMismatch_conflicts() = runBlocking {
        val eng = engine()
        val req = requestId()
        val plan = (eng.planLoad(loadInput(req, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val ctx = commitContext(req, res.reservationId)
        assertTrue(eng.commitLoad(plan, res, ctx) is OmniResult.Ok)

        val otherPlan = (eng.planLoad(loadInput(requestId(), eng)) as OmniResult.Ok).value
        val conflict = eng.commitLoad(otherPlan, res, ctx)
        assertTrue(conflict is OmniResult.Err)
        assertTrue((conflict as OmniResult.Err).error is OmniError.IDEMPOTENCY_CONFLICT)
    }

    @Test
    fun unqualifiedPrefixMode_failsClosed() = runBlocking {
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
    fun lockIncomplete_registrationNotUpstreamLocked() {
        val reg = com.omnillm.engines.api.EngineRegistry()
        val registration = LlamaCppModule.registerWith(reg, UpstreamLock.template())
        assertFalse(registration.upstreamLocked)
        assertEquals(LlamaCppModule.ENGINE_ID, registration.engineId)
    }

    @Test
    fun realInstallWithoutPathOrFd_failsClosed_noSilentFixture() = runBlocking {
        val eng = engine()
        val reqLoad = requestId()
        // Real install intent: broker key + UUID install, no path/FD, no fixture marker.
        val plan = (
            eng.planLoad(
                loadInput(
                    requestId = reqLoad,
                    engine = eng,
                    storageRootKey = "broker:${installation.value}",
                    resolvedModelPath = null,
                    modelFd = -1,
                ),
            ) as OmniResult.Ok
            ).value
        val res = reservation(plan.resourceEnvelope)
        val result = eng.commitLoad(plan, res, commitContext(reqLoad, res.reservationId))
        assertTrue(result is OmniResult.Err)
        assertTrue((result as OmniResult.Err).error is OmniError.CAPABILITY_UNSUPPORTED)
        assertTrue(
            result.error.message?.contains("no silent fixture") == true ||
                result.error.message?.contains("EXPERIMENTAL_FIXTURE") == true,
        )
    }

    @Test
    fun explicitFixtureMarkers_commitLoadSucceeds() = runBlocking {
        val eng = engine()
        val reqLoad = requestId()
        val plan = (eng.planLoad(loadInput(reqLoad, eng)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val handle = eng.commitLoad(plan, res, commitContext(reqLoad, res.reservationId))
        assertTrue(handle is OmniResult.Ok)
    }
}
