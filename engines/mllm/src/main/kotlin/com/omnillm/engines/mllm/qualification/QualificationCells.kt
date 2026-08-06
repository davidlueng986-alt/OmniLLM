package com.omnillm.engines.mllm.qualification

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels

/**
 * Seed UNQUALIFIED / NOT_EXECUTED qualification cell placeholders for mllm
 * (ENGINE-MLLM §10, ENGINE-STANDARD §5, specs/engine-qualification-status.yaml).
 *
 * Design may be BASELINE while every runtime cell remains UNQUALIFIED.
 * Only QUALIFIED_WITH_ENVELOPE + non-expired PASS evidence may project SUPPORTED.
 * This seeder never marks SUPPORTED.
 */
object QualificationCells {

    const val PLATFORM_ANDROID: String = "android"
    const val BACKEND_CPU: String = "cpu"
    const val BACKEND_NPU: String = "npu"
    const val BACKEND_GPU: String = "gpu"
    const val MODEL_ENVELOPE_WILDCARD: String = "*"
    const val WORKLOAD_DEFAULT: String = "default"
    const val DRIVER_PLACEHOLDER: String = "unmeasured"
    const val DEVICE_PLACEHOLDER: String = "unmeasured-device"

    /**
     * Placeholder cells aligned with capability-matrix.yaml.
     * Uses wildcard model envelope and unmeasured device/driver fingerprints
     * so control plane can list design intent without claiming evidence.
     */
    fun placeholderCells(
        engineBuildId: EngineBuildId,
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = DRIVER_PLACEHOLDER,
    ): List<EngineQualificationCell> {
        val device = deviceFingerprint
            ?: DeviceExecutionFingerprint.parse(DEVICE_PLACEHOLDER)
        val cpuPhases = EnginePhases.REQUIRED.map { phase ->
            cell(
                engineBuildId = engineBuildId,
                backend = BACKEND_CPU,
                phase = phase,
                device = device,
                driverFingerprint = driverFingerprint,
            )
        }
        val accelerator = listOf(BACKEND_NPU, BACKEND_GPU).map { backend ->
            cell(
                engineBuildId = engineBuildId,
                backend = backend,
                phase = EnginePhases.GENERATE,
                device = device,
                driverFingerprint = driverFingerprint,
            )
        }
        return cpuPhases + accelerator
    }

    /**
     * Register build metadata cells into [registry]. Idempotent put semantics.
     * Returns number of cells written.
     */
    fun seedUnqualified(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId,
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = DRIVER_PLACEHOLDER,
    ): Int {
        val cells = placeholderCells(
            engineBuildId = engineBuildId,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
        )
        cells.forEach { registry.putCell(it) }
        return cells.size
    }

    private fun cell(
        engineBuildId: EngineBuildId,
        backend: String,
        phase: String,
        device: DeviceExecutionFingerprint,
        driverFingerprint: String,
    ): EngineQualificationCell =
        EngineQualificationCell(
            engineBuildId = engineBuildId,
            backend = backend,
            phase = phase,
            platform = PLATFORM_ANDROID,
            deviceFingerprint = device,
            driverFingerprint = driverFingerprint,
            modelEnvelope = MODEL_ENVELOPE_WILDCARD,
            workloadEnvelope = WORKLOAD_DEFAULT,
            qualificationStatus = EngineQualificationCellStatus.UNQUALIFIED,
            evidenceStatus = EvidenceStatusLabels.NOT_EXECUTED,
            cancellationMode = CancellationModes.UNKNOWN,
            notes = mapOf(
                "designStatus" to "BASELINE",
                "registryExposure" to "UNKNOWN",
                "engineId" to "mllm",
                "source" to "engines/mllm/capability-matrix.yaml",
            ),
        )
}
