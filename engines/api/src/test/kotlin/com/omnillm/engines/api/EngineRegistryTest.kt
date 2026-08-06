package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRegistryTest {

    private val build = EngineBuildId.parse("engine-build-reg-1")
    private val device = DeviceExecutionFingerprint.parse("device-fp-1")

    private fun key(
        backend: String = "cpu",
        phase: String = EnginePhases.GENERATE,
    ): EngineQualificationCellKey =
        EngineQualificationCellKey(
            engineBuildId = build,
            backend = backend,
            phase = phase,
            platform = "android",
            deviceFingerprint = device,
            driverFingerprint = "driver-1",
            modelEnvelope = "model-env-1",
            workloadEnvelope = "default",
        )

    private fun cell(
        status: String,
        evidence: String = EvidenceStatusLabels.DEFAULT,
        backend: String = "cpu",
        phase: String = EnginePhases.GENERATE,
    ): EngineQualificationCell =
        EngineQualificationCell(
            engineBuildId = build,
            backend = backend,
            phase = phase,
            platform = "android",
            deviceFingerprint = device,
            driverFingerprint = "driver-1",
            modelEnvelope = "model-env-1",
            workloadEnvelope = "default",
            qualificationStatus = status,
            evidenceStatus = evidence,
            cancellationMode = CancellationModes.COOPERATIVE,
        )

    @Test
    fun missingCell_defaultsUnknown() {
        val reg = EngineRegistry()
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key()))
        assertFalse(reg.isSupported(key()))
        assertEquals(CapabilityState.UNKNOWN, EngineRegistry.DEFAULT_RUNTIME_CAPABILITY)
    }

    @Test
    fun onlyQualifiedWithEnvelopeAndPass_isSupported() {
        val reg = EngineRegistry()
        reg.putCell(cell(EngineQualificationCellStatus.UNQUALIFIED, EvidenceStatusLabels.PASS))
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key()))

        reg.putCell(cell(EngineQualificationCellStatus.STATIC_REVIEWED, EvidenceStatusLabels.PASS))
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key()))

        reg.putCell(cell(EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE, EvidenceStatusLabels.PASS))
        assertEquals(CapabilityState.SUPPORTED, reg.resolveCapability(key()))
        assertTrue(reg.isSupported(key()))
    }

    @Test
    fun qualifiedButNotPassEvidence_notSupported() {
        val reg = EngineRegistry()
        reg.putCell(
            cell(
                EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                EvidenceStatusLabels.NOT_EXECUTED,
            ),
        )
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key()))

        reg.putCell(
            cell(
                EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                EvidenceStatusLabels.EXPIRED,
            ),
        )
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key()))

        reg.putCell(
            cell(
                EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                EvidenceStatusLabels.FAIL,
            ),
        )
        assertEquals(CapabilityState.UNSUPPORTED, reg.resolveCapability(key()))
    }

    @Test
    fun cellsDoNotInheritAcrossBackend() {
        val reg = EngineRegistry()
        reg.putCell(
            cell(
                EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                EvidenceStatusLabels.PASS,
                backend = "cpu",
            ),
        )
        assertTrue(reg.isSupported(key(backend = "cpu")))
        assertFalse(reg.isSupported(key(backend = "vulkan")))
        assertEquals(CapabilityState.UNKNOWN, reg.resolveCapability(key(backend = "vulkan")))
    }

    @Test
    fun unknownStatus_rejected() {
        val reg = EngineRegistry()
        assertThrows(IllegalArgumentException::class.java) {
            reg.putCell(cell("TOTALLY_MADE_UP", EvidenceStatusLabels.PASS))
        }
    }

    @Test
    fun registerAndUnregister() {
        val reg = EngineRegistry()
        reg.register(
            EngineRegistration(
                engineBuildId = build,
                engineId = "llama.cpp",
                upstreamCommitOrTag = "abc123",
                artifactDigest = "d".repeat(64),
                toolchainDigest = "e".repeat(64),
            ),
        )
        assertTrue(reg.getRegistration(build)!!.upstreamLocked)
        reg.putCell(
            cell(EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE, EvidenceStatusLabels.PASS),
        )
        assertEquals(1, reg.listCells(build).size)
        reg.unregister(build)
        assertEquals(null, reg.getRegistration(build))
        assertEquals(0, reg.listCells(build).size)
    }

    @Test
    fun projectRuntimeCapability_pure() {
        val reg = EngineRegistry()
        assertEquals(
            CapabilityState.SUPPORTED,
            reg.projectRuntimeCapability(
                EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE,
                EvidenceStatusLabels.PASS,
            ),
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            reg.projectRuntimeCapability(EngineQualificationCellStatus.UNQUALIFIED),
        )
    }
}
