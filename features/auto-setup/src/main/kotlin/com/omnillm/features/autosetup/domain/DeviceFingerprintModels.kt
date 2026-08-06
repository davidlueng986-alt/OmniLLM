package com.omnillm.features.autosetup.domain

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.contracts.DeviceExecutionFingerprint

/**
 * Device discovery snapshot for FEAT-AUTOSETUP §3 step 1 / ANDROID-DEVICE §1.
 *
 * [fingerprint] is the opaque [DeviceExecutionFingerprint] identity.
 * Structured fields are evidence for ranking / UI; they do not redefine
 * canonical fingerprint semantics.
 */
data class DeviceDiscoverySnapshot(
    val fingerprint: DeviceExecutionFingerprint,
    val schemaVersion: String,
    val platform: String,
    val osBuild: String,
    val abiList: List<String>,
    val cpuFeatures: List<String> = emptyList(),
    /** Total RAM class in bytes when known; null when unknown. */
    val ramClassBytes: Long? = null,
    /** Free storage estimate in bytes when known. */
    val freeStorageBytes: Long? = null,
    val batteryPercent: Int? = null,
    val thermalOk: Boolean = true,
    /** GPU vendor/driver raw + normalized when available. */
    val gpuVendor: String? = null,
    val gpuDriverRaw: String? = null,
    /** Accelerator evidence state: MEASURED / REPORTED / UNKNOWN (EvidenceLabel). */
    val gpuEvidenceLabel: EvidenceLabel = EvidenceLabel.UNKNOWN,
    val npuVendor: String? = null,
    val npuEvidenceLabel: EvidenceLabel = EvidenceLabel.UNKNOWN,
    val pageSizeBytes: Int? = null,
    val notes: Map<String, String> = emptyMap(),
    val discoveredAtEpochMs: Long,
) {
    init {
        require(schemaVersion.isNotEmpty()) { "schemaVersion must be non-empty" }
        require(platform.isNotEmpty()) { "platform must be non-empty" }
        require(osBuild.isNotEmpty()) { "osBuild must be non-empty" }
        require(discoveredAtEpochMs >= 0L) { "discoveredAtEpochMs must be non-negative" }
        ramClassBytes?.let { require(it >= 0L) { "ramClassBytes must be non-negative" } }
        freeStorageBytes?.let { require(it >= 0L) { "freeStorageBytes must be non-negative" } }
        batteryPercent?.let { require(it in 0..100) { "batteryPercent must be 0..100" } }
        pageSizeBytes?.let { require(it > 0) { "pageSizeBytes must be positive" } }
    }

    /**
     * Human-readable device summary for zero-technical-threshold UI
     * (FEAT-AUTOSETUP: do not require engine/driver names).
     */
    fun humanSummary(): String {
        val ram = ramClassBytes?.let { bytes ->
            val gb = bytes / (1024L * 1024L * 1024L)
            if (gb > 0) "${gb}GB RAM" else "${bytes / (1024L * 1024L)}MB RAM"
        } ?: "RAM unknown"
        val accel = when {
            gpuEvidenceLabel == EvidenceLabel.UNKNOWN &&
                npuEvidenceLabel == EvidenceLabel.UNKNOWN -> "accelerators unknown (CPU preferred)"
            gpuEvidenceLabel != EvidenceLabel.UNKNOWN -> "GPU evidence ${gpuEvidenceLabel.name}"
            else -> "NPU evidence ${npuEvidenceLabel.name}"
        }
        return "$platform · $osBuild · $ram · $accel"
    }
}
