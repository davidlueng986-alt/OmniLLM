package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * COR-12 regression: the cancel phase ladder must only advance on an
 * actually-applied orchestrator cancel. When the orchestrator rejects cancel
 * (STATE_CONFLICT, e.g. STREAMING without an in-stream cancel channel), the
 * port reports the conflict honestly and NEVER fabricates CANCELLED by
 * advancing the ladder toward TERMINAL.
 */
class ControlPlaneCancelLadderTest {

    private val principal = PrincipalId.parse("cancel-ladder-principal")
    private val revision = ModelRevisionId.parse("cd".repeat(32))

    private fun buildRequest(
        requestId: String,
        idempotencyKey: String,
        engineBuildId: EngineBuildId,
    ): OrchestrationRequest =
        OrchestrationRequest(
            requestId = RequestId.parse(requestId),
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse(idempotencyKey),
            operationKind = "CHAT",
            canonicalRequestDigest = Sha256Digest.parse("a".repeat(64)),
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(
                RoutingCandidate(
                    candidateId = "cancel-ladder-primary",
                    modelRevisionId = revision,
                    installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
                    engineBuildId = engineBuildId,
                    backend = "cpu",
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    loadKeyDigest = Sha256Digest.parse("b".repeat(64)),
                    isPrimary = true,
                    deviceExecutionFingerprint =
                        DeviceExecutionFingerprint.parse("device-fp-cancel-ladder"),
                ),
            ),
            routing = ControlPlaneFeaturePorts.exploratoryRouting(),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 8,
        )

    private fun spec(requestId: String): CancelInferenceSpec =
        CancelInferenceSpec(
            requestId = requestId,
            commandId = UUID.randomUUID().toString(),
            idempotencyKey = "cancel-$requestId",
            canonicalInputDigest = "a".repeat(64),
        )

    @Test
    fun queuedCancel_appliesOnce_thenConflictIsReportedHonestly() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(includeStubEngine = true)
        val binding = EngineExecuteBinding(exploratoryEnabled = { true })
        binding.applyAttachment(pack)
        val harness = OrchestratorModule.createInMemoryHarness(
            engine = binding.inferenceEngine,
            capabilities = binding.capabilityLookup,
        )
        val port = ControlPlaneFeaturePorts.playgroundInference(
            orchestrator = harness.orchestrator,
            binding = binding,
            modelManager = ModelManagerModule.createInMemoryControlPlane(),
            clockMs = { 1_700_000_000_000L },
            runtimeEpoch = { 1L },
        )
        val requestId = UUID.randomUUID().toString()
        val submitted = harness.orchestrator.submit(
            buildRequest(requestId, "queued-cancel-1", pack.llamaCppEngine!!.engineBuildId),
        )
        assertTrue("submit must enqueue: $submitted", submitted is OmniResult.Ok)

        // First cancel applies (QUEUED → CANCELLED terminal) → ladder starts.
        val first = port.cancel(principal, spec(requestId))
        assertTrue("first cancel must apply: $first", first is OmniResult.Ok)
        assertEquals("CANCEL_REQUESTED", (first as OmniResult.Ok).value.requestState)

        // Second cancel: orchestrator rejects (request is already terminal) —
        // COR-12: honest STATE_CONFLICT, NOT a fabricated TERMINAL/CANCELLED.
        val second = port.cancel(principal, spec(requestId))
        assertTrue("second cancel must fail honestly: $second", second is OmniResult.Err)
        assertEquals(
            OmniErrorCode.STATE_CONFLICT,
            (second as OmniResult.Err).error.code,
        )

        // Third cancel must NOT advance to TERMINAL either — same honest conflict.
        val third = port.cancel(principal, spec(requestId))
        assertTrue(third is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (third as OmniResult.Err).error.code)
    }

    @Test
    fun cancelOnTerminalRequest_stateConflict_isReportedAndLadderDoesNotAdvance() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(includeStubEngine = true)
        val binding = EngineExecuteBinding(
            buildMode = com.omnillm.core.contracts.ProductBuildMode(developmentShipMode = true),
            exploratoryEnabled = { true },
        )
        binding.applyAttachment(pack)
        // Governor capacity must fit the llama-cpp resource envelope (~MBs), the
        // harness default (1MB) rejects the reservation (ADMISSION_REJECTED).
        val harness = OrchestratorModule.createInMemoryHarness(
            capacity = com.omnillm.core.canonical.generated.ResourceVector(
                cpuAnonBytes = 512L * 1024L * 1024L,
                nativeThreads = 64L,
                fileDescriptors = 256L,
            ),
            safetyMargin = com.omnillm.core.canonical.generated.ResourceVector(
                cpuAnonBytes = 1_000L,
                nativeThreads = 1L,
                fileDescriptors = 4L,
            ),
            engine = binding.inferenceEngine,
            capabilities = binding.capabilityLookup,
        )
        val port = ControlPlaneFeaturePorts.playgroundInference(
            orchestrator = harness.orchestrator,
            binding = binding,
            modelManager = ModelManagerModule.createInMemoryControlPlane(),
            clockMs = { 1_700_000_000_000L },
            runtimeEpoch = { 1L },
        )
        val requestId = UUID.randomUUID().toString()

        // Execute to a terminal (the stub engine completes synchronously), then
        // cancel — the orchestrator rejects with STATE_CONFLICT, the same branch
        // the STREAMING path hits (no in-stream cancel channel).
        val submitted = harness.orchestrator.submit(
            buildRequest(requestId, "terminal-cancel-1", pack.llamaCppEngine!!.engineBuildId),
        )
        assertTrue(submitted is OmniResult.Ok)
        harness.orchestrator.pumpAll(16)

        val cancel = port.cancel(principal, spec(requestId))
        assertTrue("cancel on terminal must fail honestly: $cancel", cancel is OmniResult.Err)
        assertEquals(
            OmniErrorCode.STATE_CONFLICT,
            (cancel as OmniResult.Err).error.code,
        )
        // Ladder never reached TERMINAL: query still reports the real state.
        val query = port.query(principal, requestId)
        assertTrue(query is OmniResult.Ok)
        assertEquals("COMPLETED", (query as OmniResult.Ok).value.state)
    }
}
