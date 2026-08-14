package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.litertlm.LitertLmModule
import com.omnillm.engines.litertlm.sdk.RealSdkBackend
import com.omnillm.engines.mlcllm.MlcLlmModule
import com.omnillm.engines.mllm.MllmModule
import com.omnillm.engines.ortgenai.OrtGenaiModule
import com.omnillm.engines.ortgenai.session.GenAiBackendFactory
import com.omnillm.engines.ortgenai.session.RealGenAiBackend
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference
import com.omnillm.engines.api.PlacementClassLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * C-07 peer-engine attach contract (RED-first boundary tests).
 *
 * - LiteRT-LM and ONNX-Runtime-GenAI ATTACH as REAL backends when the official
 *   SDK/API is on the classpath AND policy allows (dev ship mode) — they become
 *   routable through [EngineExecuteBinding]. This makes the engines ATTACHABLE,
 *   never qualified: cells stay UNQUALIFIED/UNKNOWN (honest).
 * - MLC-LLM and mllm stay METADATA-ONLY in every mode (boundary test): the
 *   require-guards remain and no live engine is ever created.
 * - Compliance mode never attaches live peers, even with the SDK present.
 */
class PeerEngineAttachTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val device = DeviceExecutionFingerprint.parse("device-fp-peer-attach")

    private val devMode = ProductBuildMode(developmentShipMode = true)
    private val failClosed = ProductBuildMode.FAIL_CLOSED

    private fun candidate(build: EngineBuildId): RoutingCandidate =
        RoutingCandidate(
            candidateId = "peer-primary",
            modelRevisionId = revision,
            installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
            engineBuildId = build,
            backend = "cpu",
            placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            loadKeyDigest = digest,
            isPrimary = true,
            deviceExecutionFingerprint = device,
        )

    private fun request(build: EngineBuildId): OrchestrationRequest =
        OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-peer"),
            idempotencyKey = IdempotencyKey.parse("idem-${UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(build)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )

    @Test
    fun litert_attachesWhenSdkPresent() {
        // Test seam: litertlm-jvm on the host test classpath ⇒ official SDK present.
        assertTrue(
            "test seam must put the official LiteRT-LM SDK on the classpath",
            LitertLmModule.isOfficialSdkOnClasspath(),
        )
        val pack = EnginePackAttachment.attachForTest(
            buildMode = devMode,
            deviceFingerprint = device,
        )
        val litert = pack.litertLmEngine
        assertNotNull(
            "dev-mode attach with the SDK present must instantiate the REAL LiteRT-LM engine",
            litert,
        )
        assertNotNull("liveEngine(LiteRT-LM) must resolve", pack.liveEngine(LitertLmModule.ENGINE_ID))
        assertTrue("must be the REAL backend, not a stub", litert!!.sdk is RealSdkBackend)
        assertTrue(
            "real backend must report the SDK present",
            (litert.sdk as RealSdkBackend).isSdkPresent(),
        )
        assertTrue(
            "live peer build id must be part of the attach",
            litert.engineBuildId.value in pack.livePeerEngineBuildIds,
        )
        assertEquals(LitertLmModule.ENGINE_ID, pack.livePeerEngineIds.firstOrNull())

        // Honesty: live attach never elevates cells (UNQUALIFIED/UNKNOWN only).
        val cells = pack.registry.listCells(litert.engineBuildId)
        assertTrue("expected placeholder cells for LiteRT-LM", cells.isNotEmpty())
        assertTrue(
            cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED },
        )
        assertTrue(
            cells.none {
                pack.registry.projectRuntimeCapability(
                    it.qualificationStatus,
                    it.evidenceStatus,
                ) == CapabilityState.SUPPORTED
            },
        )

        // Binding: EngineExecuteBinding must route a generation-capability request.
        val binding = EngineExecuteBinding(buildMode = devMode, exploratoryEnabled = { true })
        val applied = binding.applyAttachment(pack)
        assertTrue("attach must bind at least one engine", applied.bound)
        val plan = runBlocking {
            binding.inferenceEngine.planInference(
                request(litert.engineBuildId),
                candidate(litert.engineBuildId),
            )
        }
        assertTrue(
            "generation plan must route to the attached LiteRT-LM adapter: $plan",
            plan is OmniResult.Ok,
        )
        assertEquals(
            CapabilityState.CONDITIONAL,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate(litert.engineBuildId)),
        )
    }

    @Test
    fun ort_attachesWhenSdkPresent() {
        // Test seam: genai classes.jar on the host test classpath ⇒ API present.
        assertTrue(
            "test seam must put the official GenAI API on the classpath",
            GenAiBackendFactory.isOfficialApiOnClasspath(),
        )
        val pack = EnginePackAttachment.attachForTest(
            buildMode = devMode,
            deviceFingerprint = device,
        )
        val ort = pack.ortGenaiEngine
        assertNotNull(
            "dev-mode attach with the API present must instantiate the REAL ONNX-Runtime-GenAI engine",
            ort,
        )
        assertNotNull("liveEngine(ONNX-Runtime-GenAI) must resolve", pack.liveEngine(OrtGenaiModule.ENGINE_ID))
        assertTrue("must be the REAL backend, not a stub", ort!!.backend is RealGenAiBackend)
        assertTrue(
            "live peer build id must be part of the attach",
            ort.engineBuildId.value in pack.livePeerEngineBuildIds,
        )

        // Honesty: live attach never elevates cells (UNQUALIFIED/UNKNOWN only).
        val cells = pack.registry.listCells(ort.engineBuildId)
        assertTrue("expected placeholder cells for ONNX-Runtime-GenAI", cells.isNotEmpty())
        assertTrue(
            cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED },
        )
        assertTrue(
            cells.none {
                pack.registry.projectRuntimeCapability(
                    it.qualificationStatus,
                    it.evidenceStatus,
                ) == CapabilityState.SUPPORTED
            },
        )

        // Binding: EngineExecuteBinding must route a generation-capability request.
        val binding = EngineExecuteBinding(buildMode = devMode, exploratoryEnabled = { true })
        val applied = binding.applyAttachment(pack)
        assertTrue("attach must bind at least one engine", applied.bound)
        val plan = runBlocking {
            binding.inferenceEngine.planInference(
                request(ort.engineBuildId),
                candidate(ort.engineBuildId),
            )
        }
        assertTrue(
            "generation plan must route to the attached ONNX-Runtime-GenAI adapter: $plan",
            plan is OmniResult.Ok,
        )
        assertEquals(
            CapabilityState.CONDITIONAL,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate(ort.engineBuildId)),
        )
    }

    @Test
    fun mlc_mllm_stayMetadataOnly() {
        // Boundary: even in dev mode with policy allowing real backends, the
        // MLC-LLM and mllm peers never attach live — metadata + UNKNOWN only.
        val pack = EnginePackAttachment.attachForTest(
            buildMode = devMode,
            deviceFingerprint = device,
        )
        assertNull(
            "MLC-LLM must stay metadata-only (no live engine)",
            pack.liveEngine(MlcLlmModule.ENGINE_ID),
        )
        assertNull(
            "mllm must stay metadata-only (no live engine)",
            pack.liveEngine(MllmModule.ENGINE_ID),
        )
        assertTrue(MlcLlmModule.ENGINE_ID !in pack.livePeerEngineIds)
        assertTrue(MllmModule.ENGINE_ID !in pack.livePeerEngineIds)

        for (engineId in listOf(MlcLlmModule.ENGINE_ID, MllmModule.ENGINE_ID)) {
            val reg = pack.registrationsByEngineId[engineId]
            assertNotNull("missing registration for $engineId", reg)
            val cells = pack.registry.listCells(reg!!.engineBuildId)
            assertTrue("expected UNKNOWN cells for $engineId", cells.isNotEmpty())
            assertTrue(
                cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED },
            )
            assertTrue(
                cells.none {
                    pack.registry.projectRuntimeCapability(
                        it.qualificationStatus,
                        it.evidenceStatus,
                    ) == CapabilityState.SUPPORTED
                },
            )
        }

        // Policy honestly allows these engines in dev mode — the ATTACH boundary
        // (not the policy) is what keeps them metadata-only until wired.
        assertTrue(
            EngineSelectionPolicy.mayUseRealNativeBackend(MlcLlmModule.ENGINE_ID, devMode),
        )
        assertTrue(
            EngineSelectionPolicy.mayUseRealNativeBackend(MllmModule.ENGINE_ID, devMode),
        )

        // Compliance mode: no live peers at all, even with the SDK present.
        val compliance = EnginePackAttachment.attachForTest(
            buildMode = failClosed,
            deviceFingerprint = device,
        )
        assertNull("compliance must not attach LiteRT-LM live", compliance.litertLmEngine)
        assertNull("compliance must not attach ONNX-Runtime-GenAI live", compliance.ortGenaiEngine)
        assertNull(compliance.liveEngine(MlcLlmModule.ENGINE_ID))
        assertNull(compliance.liveEngine(MllmModule.ENGINE_ID))
        assertTrue(compliance.livePeerEngineIds.isEmpty())
    }
}
