package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine Registry (ENGINE-QUALIFICATION-STATUS §2, ENGINE-STANDARD §5).
 *
 * Rules:
 * - Only cells with status [EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE]
 *   **and** evidence [EvidenceStatusLabels.PASS] project as [CapabilityState.SUPPORTED].
 * - Default / missing / non-envelope / expired / invalidated → [CapabilityState.UNKNOWN].
 * - Evidence FAIL → [CapabilityState.UNSUPPORTED].
 * - Cells never inherit across backend/device/model/workload envelopes.
 * - Registry is an in-process projection; adapters must not write domain DB here.
 *
 * `specs/engine-qualification-schema.yaml`: defaultRuntimeCapability = UNKNOWN.
 */
class EngineRegistry {

    private val engines = ConcurrentHashMap<String, EngineRegistration>()
    private val cells = ConcurrentHashMap<String, EngineQualificationCell>()

    fun register(registration: EngineRegistration) {
        engines[registration.engineBuildId.value] = registration
    }

    fun unregister(engineBuildId: EngineBuildId) {
        engines.remove(engineBuildId.value)
        val prefix = engineBuildId.value + "|"
        cells.keys.filter { it.startsWith(prefix) }.forEach { cells.remove(it) }
    }

    fun getRegistration(engineBuildId: EngineBuildId): EngineRegistration? =
        engines[engineBuildId.value]

    fun listRegistrations(): List<EngineRegistration> =
        engines.values.sortedBy { it.engineBuildId.value }

    /**
     * Put or replace a qualification cell. Status must be a known schema value.
     * Unsupported / unknown status fails closed (not stored as SUPPORTED).
     */
    fun putCell(cell: EngineQualificationCell) {
        require(EngineQualificationCellStatus.isKnown(cell.qualificationStatus)) {
            "unknown qualification status (fail closed): ${cell.qualificationStatus}"
        }
        require(EvidenceStatusLabels.isKnown(cell.evidenceStatus)) {
            "unknown evidence status (fail closed): ${cell.evidenceStatus}"
        }
        require(EnginePhases.isKnown(cell.phase)) {
            "unknown phase (fail closed): ${cell.phase}"
        }
        require(CancellationModes.isKnown(cell.cancellationMode)) {
            "unknown cancellation mode (fail closed): ${cell.cancellationMode}"
        }
        cells[cellKey(cell)] = cell
    }

    fun getCell(key: EngineQualificationCellKey): EngineQualificationCell? =
        cells[key.asMapKey()]

    fun listCells(engineBuildId: EngineBuildId? = null): List<EngineQualificationCell> {
        val all = cells.values.toList()
        return if (engineBuildId == null) {
            all.sortedBy { cellKey(it) }
        } else {
            all.filter { it.engineBuildId == engineBuildId }.sortedBy { cellKey(it) }
        }
    }

    /**
     * Project runtime capability for a cell key.
     * Missing cell ⇒ [CapabilityState.UNKNOWN] (never invent SUPPORTED).
     */
    fun resolveCapability(key: EngineQualificationCellKey): CapabilityState {
        val cell = cells[key.asMapKey()] ?: return CapabilityState.UNKNOWN
        return projectRuntimeCapability(cell.qualificationStatus, cell.evidenceStatus)
    }

    /** True only when projected capability is SUPPORTED. */
    fun isSupported(key: EngineQualificationCellKey): Boolean =
        resolveCapability(key) == CapabilityState.SUPPORTED

    /**
     * Pure projection function (ENGINE-QUALIFICATION-STATUS §2).
     * Only QUALIFIED_WITH_ENVELOPE + PASS evidence → SUPPORTED.
     */
    fun projectRuntimeCapability(
        qualificationStatus: String,
        evidenceStatus: String = EvidenceStatusLabels.DEFAULT,
    ): CapabilityState {
        if (qualificationStatus != EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE) {
            return CapabilityState.UNKNOWN
        }
        return when (evidenceStatus) {
            EvidenceStatusLabels.PASS -> CapabilityState.SUPPORTED
            EvidenceStatusLabels.FAIL -> CapabilityState.UNSUPPORTED
            // NOT_EXECUTED, EXPIRED, INVALIDATED, or anything else ⇒ fail closed to UNKNOWN
            else -> CapabilityState.UNKNOWN
        }
    }

    private fun cellKey(cell: EngineQualificationCell): String =
        EngineQualificationCellKey(
            engineBuildId = cell.engineBuildId,
            backend = cell.backend,
            phase = cell.phase,
            platform = cell.platform,
            deviceFingerprint = cell.deviceFingerprint,
            driverFingerprint = cell.driverFingerprint,
            modelEnvelope = cell.modelEnvelope,
            workloadEnvelope = cell.workloadEnvelope,
        ).asMapKey()

    companion object {
        /** Default when no cell is registered. */
        val DEFAULT_RUNTIME_CAPABILITY: CapabilityState = CapabilityState.UNKNOWN
    }
}

/**
 * Build-level registration metadata. Upstream lock fields are opaque strings;
 * empty/unknown lock does **not** allow SUPPORTED projection by itself.
 */
data class EngineRegistration(
    val engineBuildId: EngineBuildId,
    val engineId: String,
    val upstreamCommitOrTag: String = "",
    val patchSetDigest: String = "",
    val toolchainDigest: String = "",
    val artifactDigest: String = "",
    val designStatus: String = "BASELINE",
) {
    init {
        require(engineId.isNotEmpty()) { "engineId must be non-empty" }
        require(designStatus.isNotEmpty()) { "designStatus must be non-empty" }
    }

    val upstreamLocked: Boolean
        get() = upstreamCommitOrTag.isNotEmpty() &&
            artifactDigest.isNotEmpty() &&
            toolchainDigest.isNotEmpty()
}

/**
 * Phase-capability cell key dimensions
 * (`specs/engine-qualification-schema.yaml#phaseCapabilityKey`).
 */
data class EngineQualificationCellKey(
    val engineBuildId: EngineBuildId,
    val backend: String,
    val phase: String,
    val platform: String,
    val deviceFingerprint: DeviceExecutionFingerprint,
    val driverFingerprint: String,
    val modelEnvelope: String,
    val workloadEnvelope: String,
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(phase.isNotEmpty()) { "phase must be non-empty" }
        require(platform.isNotEmpty()) { "platform must be non-empty" }
        require(driverFingerprint.isNotEmpty()) { "driverFingerprint must be non-empty" }
        require(modelEnvelope.isNotEmpty()) { "modelEnvelope must be non-empty" }
        require(workloadEnvelope.isNotEmpty()) { "workloadEnvelope must be non-empty" }
    }

    fun asMapKey(): String =
        listOf(
            engineBuildId.value,
            backend,
            phase,
            platform,
            deviceFingerprint.value,
            driverFingerprint,
            modelEnvelope,
            workloadEnvelope,
        ).joinToString("|")
}

/**
 * Full qualification cell with evidence and cancellation profile.
 * Runtime capability is **projected**, not stored as caller-supplied authority.
 */
data class EngineQualificationCell(
    val engineBuildId: EngineBuildId,
    val backend: String,
    val phase: String,
    val platform: String,
    val deviceFingerprint: DeviceExecutionFingerprint,
    val driverFingerprint: String,
    val modelEnvelope: String,
    val workloadEnvelope: String,
    val qualificationStatus: String = EngineQualificationCellStatus.UNQUALIFIED,
    val evidenceStatus: String = EvidenceStatusLabels.DEFAULT,
    val cancellationMode: String = CancellationModes.UNKNOWN,
    val notes: Map<String, String> = emptyMap(),
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(phase.isNotEmpty()) { "phase must be non-empty" }
        require(platform.isNotEmpty()) { "platform must be non-empty" }
        require(driverFingerprint.isNotEmpty()) { "driverFingerprint must be non-empty" }
        require(modelEnvelope.isNotEmpty()) { "modelEnvelope must be non-empty" }
        require(workloadEnvelope.isNotEmpty()) { "workloadEnvelope must be non-empty" }
    }

    fun toKey(): EngineQualificationCellKey =
        EngineQualificationCellKey(
            engineBuildId = engineBuildId,
            backend = backend,
            phase = phase,
            platform = platform,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
            modelEnvelope = modelEnvelope,
            workloadEnvelope = workloadEnvelope,
        )
}
