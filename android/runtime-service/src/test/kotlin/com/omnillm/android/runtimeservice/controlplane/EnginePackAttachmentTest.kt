package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCellKey
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.litertlm.LitertLmModule
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.engines.mlcllm.MlcLlmModule
import com.omnillm.engines.mllm.MllmModule
import com.omnillm.engines.ortgenai.OrtGenaiModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for Engine Pack attach + selection policy (no Android process / no NDK).
 * Production attach uses [EnginePackAttachment.attachAfterReady] only in :runtime.
 *
 * Hard rule: registry must not advertise SUPPORTED without qualification evidence.
 */
class EnginePackAttachmentTest {

    private val device = DeviceExecutionFingerprint.parse("device-fp-engine-pack-test")

    /** Dev-mode posture (what a debug build wires — BLD-02). */
    private val devMode = ProductBuildMode(developmentShipMode = true)

    /** Fail-closed posture (what a release build wires). */
    private val failClosed = ProductBuildMode.FAIL_CLOSED

    @Test
    fun attachForTest_registersAllCatalogEngines() {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = false,
            deviceFingerprint = device,
        )
        assertEquals(
            EngineSelectionPolicy.CATALOG_ENGINE_IDS.toSet(),
            pack.registrationsByEngineId.keys,
        )
        assertEquals(
            EngineSelectionPolicy.CATALOG_ENGINE_IDS,
            pack.registeredEngineIds,
        )
        // One registration per catalog engineBuildId
        assertEquals(
            EngineSelectionPolicy.CATALOG_ENGINE_IDS.size,
            pack.registry.listRegistrations().size,
        )
        for (engineId in EngineSelectionPolicy.CATALOG_ENGINE_IDS) {
            assertNotNull(pack.registrationsByEngineId[engineId])
        }
    }

    @Test
    fun attachForTest_registersUnqualifiedCellsOnly_noSupported() {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = false,
            deviceFingerprint = device,
        )
        assertFalse(pack.nativeLibraryPresent)
        assertNull(pack.llamaCppEngine)
        assertNotNull(pack.llamaCppRegistration)
        assertEquals(LlamaCppModule.ENGINE_ID, pack.llamaCppRegistration!!.engineId)

        val cells = pack.registry.listCells()
        assertTrue("expected placeholder cells for all engines", cells.isNotEmpty())
        assertTrue(
            cells.all { it.qualificationStatus == EngineQualificationCellStatus.UNQUALIFIED },
        )
        assertTrue(
            cells.all { it.evidenceStatus == EvidenceStatusLabels.NOT_EXECUTED },
        )

        // Projection: no cell may advertise SUPPORTED without QUALIFIED_WITH_ENVELOPE+PASS.
        // (DEV-mode policy shortcut anyExecutableCell = registrations present; the
        // evidence-driven registry projection must still stay UNKNOWN — COR-10.)
        assertEquals(
            true,
            EngineSelectionPolicy.anyExecutableCell(pack.registry, devMode),
        )
        assertEquals(
            false,
            EngineSelectionPolicy.anyExecutableCell(pack.registry, failClosed),
        )
        assertTrue(cells.none { EngineSelectionPolicy.projectsSupported(it) })
        for (cell in cells) {
            assertEquals(
                CapabilityState.UNKNOWN,
                pack.registry.projectRuntimeCapability(
                    cell.qualificationStatus,
                    cell.evidenceStatus,
                ),
            )
            assertFalse(pack.registry.isSupported(cell.toKey()))
        }

        val key = EngineQualificationCellKey(
            engineBuildId = LlamaCppModule.defaultEngineBuildId(),
            backend = "cpu",
            phase = EnginePhases.GENERATE,
            platform = "android",
            deviceFingerprint = device,
            driverFingerprint = "unknown-driver",
            modelEnvelope = "*",
            workloadEnvelope = "default",
        )
        assertEquals(CapabilityState.UNKNOWN, pack.registry.resolveCapability(key))
        assertFalse(pack.registry.isSupported(key))
    }

    @Test
    fun attachForTest_withStub_doesNotElevateCapability() {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        assertNotNull(pack.llamaCppEngine)
        assertTrue(pack.llamaCppEngine!!.native.libraryLabel().contains("stub"))
        // Stub presence must never project SUPPORTED through the evidence-driven registry.
        // Executability is posture-dependent: dev registrations shortcut vs evidence.
        assertTrue(EngineSelectionPolicy.anyExecutableCell(pack.registry, devMode))
        assertFalse(EngineSelectionPolicy.anyExecutableCell(pack.registry, failClosed))
        val anySupported = pack.registry.listCells().any {
            pack.registry.projectRuntimeCapability(
                it.qualificationStatus,
                it.evidenceStatus,
            ) == CapabilityState.SUPPORTED
        }
        assertFalse(anySupported)
    }

    @Test
    fun selectionPolicy_allCatalogEnginesMayUseRealNativeInDevMode() {
        // DEVELOPMENT_SHIP_MODE: every catalog engine may use a real native/SDK backend.
        for (engineId in EngineSelectionPolicy.CATALOG_ENGINE_IDS) {
            assertTrue(
                "unexpected native eligibility for $engineId",
                EngineSelectionPolicy.mayUseRealNativeBackend(engineId, devMode),
            )
        }
        // Compliance (release) posture: only llama-cpp may use a real backend.
        for (engineId in EngineSelectionPolicy.CATALOG_ENGINE_IDS) {
            assertEquals(
                "unexpected native eligibility for $engineId in release posture",
                engineId == LlamaCppModule.ENGINE_ID,
                EngineSelectionPolicy.mayUseRealNativeBackend(engineId, failClosed),
            )
        }
        assertEquals(
            LlamaCppModule.ENGINE_ID,
            EngineSelectionPolicy.PRIMARY_ENGINE_ID,
        )
        assertEquals(
            "ALL_CATALOG",
            EngineSelectionPolicy.summaryNotes(devMode)["nativeEligible"],
        )
        assertEquals(
            LlamaCppModule.ENGINE_ID,
            EngineSelectionPolicy.summaryNotes(failClosed)["nativeEligible"],
        )
        assertEquals(
            "DEVELOPMENT_SHIP_MODE",
            EngineSelectionPolicy.summaryNotes(devMode)["productBuildMode"],
        )
        assertEquals(
            "COMPLIANCE_HONESTY_MODE",
            EngineSelectionPolicy.summaryNotes(failClosed)["productBuildMode"],
        )
    }

    @Test
    fun notes_markFailClosedAndUnknownExposure_forAllEngines() {
        val pack = EnginePackAttachment.attachForTest(includeStubEngine = false)
        assertEquals("UNKNOWN", pack.notes["registryExposure"])
        assertEquals("test", pack.notes["mode"])
        assertEquals("false", pack.notes["anyExecutable"])
        assertEquals(
            EngineSelectionPolicy.CATALOG_ENGINE_IDS.size.toString(),
            pack.notes["registry.registrations"],
        )
        assertEquals(
            LlamaCppModule.ENGINE_ID,
            pack.notes["nativeEligible"],
        )
        assertEquals(
            "stub_registry_only",
            pack.notes["peerEngines"],
        )
        // Dev-mode attach notes honestly label the exposure.
        val devPack = EnginePackAttachment.attachForTest(
            buildMode = devMode,
            includeStubEngine = false,
        )
        assertEquals("DEV_EXECUTABLE", devPack.notes["registryExposure"])
        assertEquals("true", devPack.notes["anyExecutable"])
        assertEquals("development_ship", devPack.notes["mode"])
        assertEquals("ALL_CATALOG", devPack.notes["nativeEligible"])
    }

    @Test
    fun peerEngines_registeredButNotSupported() {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = false,
            deviceFingerprint = device,
        )
        val peers = listOf(
            LitertLmModule.ENGINE_ID,
            MlcLlmModule.ENGINE_ID,
            MllmModule.ENGINE_ID,
            OrtGenaiModule.ENGINE_ID,
        )
        for (engineId in peers) {
            val reg = pack.registrationsByEngineId[engineId]
            assertNotNull("missing registration for $engineId", reg)
            val cells = pack.registry.listCells(reg!!.engineBuildId)
            assertTrue("expected cells for $engineId", cells.isNotEmpty())
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
    }

    @Test
    fun registry_doesNotAdvertiseSupportedWithoutQualificationEvidence() {
        // Attach path seeds only UNQUALIFIED/NOT_EXECUTED — fail closed in the registry
        // projection (DEV-mode policy shortcut reflects registrations presence only).
        val pack = EnginePackAttachment.attachForTest(deviceFingerprint = device)
        assertEquals(
            false,
            EngineSelectionPolicy.anyExecutableCell(pack.registry, failClosed),
        )
        assertEquals(
            true,
            EngineSelectionPolicy.anyExecutableCell(pack.registry, devMode),
        )

        // Even if a caller mistakenly put QUALIFIED without PASS, projection stays UNKNOWN.
        val sample = pack.registry.listCells().first()
        pack.registry.putCell(
            sample.copy(
                qualificationStatus = EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                evidenceStatus = EvidenceStatusLabels.NOT_EXECUTED,
            ),
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            pack.registry.resolveCapability(sample.toKey()),
        )
        assertFalse(pack.registry.isSupported(sample.toKey()))

        // Only QUALIFIED_WITH_ENVELOPE + PASS projects SUPPORTED (evidence-driven).
        pack.registry.putCell(
            sample.copy(
                qualificationStatus = EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                evidenceStatus = EvidenceStatusLabels.PASS,
            ),
        )
        assertEquals(
            CapabilityState.SUPPORTED,
            pack.registry.resolveCapability(sample.toKey()),
        )
        assertTrue(EngineSelectionPolicy.anyExecutableCell(pack.registry, failClosed))
    }
}
