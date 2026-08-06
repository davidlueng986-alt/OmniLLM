package com.omnillm.engines.mlcllm

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels

/**
 * Seeds UNQUALIFIED / NOT_EXECUTED qualification cell placeholders
 * (ENGINE-MLC, ENGINE-QUALIFICATION-STATUS, capability-matrix.yaml).
 *
 * These cells **never** project as SUPPORTED. Control plane may replace a cell
 * only when real evidence packs exist (QUALIFIED_WITH_ENVELOPE + PASS).
 */
object QualificationCells {

    /** Placeholder fingerprint for design-seeded rows (not a real device claim). */
    const val PLACEHOLDER_DEVICE_FP: String = "design-placeholder-device"
    const val PLACEHOLDER_DRIVER_FP: String = "design-placeholder-driver"
    const val DEFAULT_PLATFORM: String = "android"
    const val DEFAULT_MODEL_ENVELOPE: String = "*"
    const val DEFAULT_WORKLOAD: String = "default"

    /**
     * Backend × phase rows matching engines/mlc-llm/capability-matrix.yaml `cells:`.
     */
    val PLACEHOLDER_ROWS: List<Pair<String, String>> = listOf(
        "cpu" to EnginePhases.PROBE,
        "cpu" to EnginePhases.LOAD,
        "cpu" to EnginePhases.CREATE_SESSION,
        "cpu" to EnginePhases.PLAN,
        "cpu" to EnginePhases.COMMIT,
        "cpu" to EnginePhases.START,
        "cpu" to EnginePhases.GENERATE,
        "cpu" to EnginePhases.CLOSE,
        "cpu" to EnginePhases.UNLOAD,
        "opencl" to EnginePhases.PROBE,
        "opencl" to EnginePhases.GENERATE,
        "vulkan" to EnginePhases.PROBE,
        "vulkan" to EnginePhases.GENERATE,
    )

    fun buildPlaceholders(
        engineBuildId: EngineBuildId,
        deviceFingerprint: DeviceExecutionFingerprint =
            DeviceExecutionFingerprint.parse(PLACEHOLDER_DEVICE_FP),
        driverFingerprint: String = PLACEHOLDER_DRIVER_FP,
        platform: String = DEFAULT_PLATFORM,
        modelEnvelope: String = DEFAULT_MODEL_ENVELOPE,
        workloadEnvelope: String = DEFAULT_WORKLOAD,
    ): List<EngineQualificationCell> =
        PLACEHOLDER_ROWS.map { (backend, phase) ->
            EngineQualificationCell(
                engineBuildId = engineBuildId,
                backend = backend,
                phase = phase,
                platform = platform,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = driverFingerprint,
                modelEnvelope = modelEnvelope,
                workloadEnvelope = workloadEnvelope,
                qualificationStatus = EngineQualificationCellStatus.UNQUALIFIED,
                evidenceStatus = EvidenceStatusLabels.NOT_EXECUTED,
                cancellationMode = CancellationModes.UNKNOWN,
                notes = mapOf(
                    "source" to "capability-matrix.yaml",
                    "engineId" to MlcLlmModule.ENGINE_ID,
                    "designStatus" to "BASELINE",
                    "claim" to "UNQUALIFIED_PLACEHOLDER",
                ),
            )
        }

    /**
     * Put all placeholder cells into [registry]. Does not mark any cell SUPPORTED.
     * @return number of cells written
     */
    fun seedUnqualified(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId,
        deviceFingerprint: DeviceExecutionFingerprint =
            DeviceExecutionFingerprint.parse(PLACEHOLDER_DEVICE_FP),
        driverFingerprint: String = PLACEHOLDER_DRIVER_FP,
    ): Int {
        val cells = buildPlaceholders(
            engineBuildId = engineBuildId,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
        )
        cells.forEach { registry.putCell(it) }
        return cells.size
    }
}
