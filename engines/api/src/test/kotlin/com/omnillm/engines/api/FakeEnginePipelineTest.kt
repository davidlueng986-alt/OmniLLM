package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.engines.api.fake.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * End-to-end Plan → Reserve → Commit → Execute on [FakeEngine] without native code.
 */
class FakeEnginePipelineTest {

    private val digest =
        Sha256Digest.parse("ab".repeat(32))
    private val revision =
        ModelRevisionId.parse("cd".repeat(32))
    private val installation =
        InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
    private val principal = PrincipalId.parse("principal-test")
    private val device = DeviceExecutionFingerprint.parse("device-fp-pipeline")

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

    private fun loadInput(requestId: RequestId, engine: FakeEngine): LoadInput {
        val loadKey = LoadKey(
            modelRevisionId = revision,
            engineBuildId = engine.engineBuildId,
            backend = engine.backend,
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

    @Test
    fun planReserveCommitExecute_generationPipeline() = runBlocking {
        val engine = FakeEngine(streamDeltaCount = 3)

        // Describe (no mutation)
        val desc = (engine.describe(DeviceDescriptor(device)) as OmniResult.Ok).value
        assertEquals("fake", desc.engineId)
        assertTrue(desc.backends.contains("cpu"))

        // Plan load (pure)
        val reqLoad = requestId()
        val plan = (engine.planLoad(loadInput(reqLoad, engine)) as OmniResult.Ok).value
        assertEquals(engine.engineBuildId, plan.engineBuildId)
        assertEquals(PlacementClassLabels.PRIVILEGED_TRUSTED, plan.proposedPlacementClass)

        // Reserve (governor-side; fake only holds the handle)
        val resLoad = reservation(plan.resourceEnvelope)

        // Commit load
        val commitLoad = commitContext(reqLoad, resLoad.reservationId)
        val handle = (engine.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertTrue(handle.loadedModelId.value.startsWith("fake-lm-"))

        // Idempotent re-commit
        val again = (engine.commitLoad(plan, resLoad, commitLoad) as OmniResult.Ok).value
        assertEquals(handle.loadedModelId, again.loadedModelId)

        // Query commit
        val q = (engine.queryCommit(commitLoad.commitId) as OmniResult.Ok).value
        assertEquals("COMMITTED", q.state)
        assertEquals(handle.loadedModelId, q.loadedModelId)

        // Bind loaded port
        val port = (engine.bindLoadedModel(handle) as OmniResult.Ok).value

        // Plan inference (pure, no source session)
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
        assertEquals(handle.loadedModelId, infPlan.loadedModelId)

        // Reserve + commit inference
        val resInf = reservation(infPlan.resourceEnvelope)
        val commitInf = commitContext(reqInf, resInf.reservationId)
        val prepared = (
            port.commitInference(infPlan, resInf, commitInf) as OmniResult.Ok
            ).value
        assertEquals(commitInf.commitId, prepared.commitId)

        // Execute / start with event stream
        val events = mutableListOf<EngineEvent>()
        val sink = EventSink { e -> events.add(e) }
        val op = OperationContext(
            operationId = prepared.operationId,
            principalId = principal.value,
            deadline = 9_999L,
            cancelHandle = "cancel-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
        )
        val opHandle = (port.start(prepared, op, sink) as OmniResult.Ok).value
        assertEquals("COMPLETED", opHandle.state)
        assertEquals(1, engine.startCount.get())

        assertTrue(events.any { it.kind == EngineEventKinds.METADATA })
        assertEquals(3, events.count { it.kind == EngineEventKinds.DELTA })
        assertTrue(events.any { it.kind == EngineEventKinds.USAGE })
        assertEquals(1, events.count { it.kind == EngineEventKinds.TERMINAL })
        assertTrue(events.last().isTerminal)

        // Idempotent start
        val op2 = (port.start(prepared, op, sink) as OmniResult.Ok).value
        assertEquals(opHandle.operationId, op2.operationId)
        assertEquals(1, engine.startCount.get())

        // Unload
        val unload = (
            port.unload(
                OperationContext(
                    operationId = "op-unload",
                    principalId = principal.value,
                    deadline = 9_999L,
                    cancelHandle = "cancel-u",
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                ),
            ) as OmniResult.Ok
            ).value
        assertTrue(unload.unloaded)
        assertTrue(
            engine.bindLoadedModel(handle) is OmniResult.Err,
        )
    }

    @Test
    fun commitConflict_sameIdDifferentPayload() = runBlocking {
        val engine = FakeEngine()
        val req = requestId()
        val plan = (engine.planLoad(loadInput(req, engine)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val c1 = commitContext(req, res.reservationId)
        assertTrue(engine.commitLoad(plan, res, c1) is OmniResult.Ok)

        val c2 = c1.copy(oneShotNonce = "different-nonce")
        val conflict = engine.commitLoad(plan, res, c2)
        assertTrue(conflict is OmniResult.Err)
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            (conflict as OmniResult.Err).error.code.code,
        )
    }

    @Test
    fun probePlanAndExecute() = runBlocking {
        val engine = FakeEngine()
        val req = requestId()
        val plan = (
            engine.planProbe(
                ProbeInput(
                    requestId = req,
                    principalId = principal,
                    device = DeviceDescriptor(device),
                    backend = "cpu",
                    runtimeEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value
        val res = reservation(plan.resourceEnvelope)
        val result = (
            engine.probe(
                plan,
                res,
                OperationContext(
                    operationId = "probe-op-1",
                    principalId = principal.value,
                    deadline = 1_000L,
                    cancelHandle = "c-probe",
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                ),
            ) as OmniResult.Ok
            ).value
        assertTrue(result.success)
        assertEquals(plan.planId, result.planId)
    }

    @Test
    fun embeddingPipeline_noSession() = runBlocking {
        val engine = FakeEngine()
        val req = requestId()
        val plan = (engine.planLoad(loadInput(req, engine)) as OmniResult.Ok).value
        val res = reservation(plan.resourceEnvelope)
        val commit = commitContext(req, res.reservationId)
        val handle = (engine.commitLoad(plan, res, commit) as OmniResult.Ok).value
        val port = (engine.bindLoadedModel(handle) as OmniResult.Ok).value

        val embReq = requestId()
        val embPlan = (
            port.planEmbedding(
                EmbeddingInput(
                    requestId = embReq,
                    principalId = principal,
                    canonicalInputDigest = digest,
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    deadlineMonotonic = 5_000L,
                ),
            ) as OmniResult.Ok
            ).value
        val embRes = reservation(embPlan.resourceEnvelope)
        val embCommit = commitContext(embReq, embRes.reservationId)
        val prepared = (
            port.commitEmbedding(embPlan, embRes, embCommit) as OmniResult.Ok
            ).value
        assertEquals(null, prepared.targetSessionId)
        assertFalse(prepared.operationId.isEmpty())
    }

    @Test
    fun engineLoadPort_isImplementedByOmniEngine() {
        val engine: EngineLoadPort = FakeEngine()
        assertEquals(EngineBuildId.parse("fake-engine-build-1"), engine.engineBuildId)
    }

    @Test
    fun reservationMath_peakDominatesSteady() {
        val env = ResourceEnvelope(
            steady = ResourceVector(cpuAnonBytes = 100),
            peak = ResourceVector(cpuAnonBytes = 200),
        )
        assertTrue(env.peak.dominates(env.steady))
    }
}
