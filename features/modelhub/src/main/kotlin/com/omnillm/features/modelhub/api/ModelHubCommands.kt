package com.omnillm.features.modelhub.api

/**
 * Client-generated command identity for ModelHub mutations (ADR-004/005).
 * Mirrors Admin durable-command fields; claim key remains principal + kind + key.
 */
data class ModelHubCommandIdentity(
    val commandId: String,
    val idempotencyKey: String,
    /** Canonical input digest (hex SHA-256) of the mutation parameters. */
    val canonicalInputDigest: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(canonicalInputDigest.matches(HEX64)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
        expectedVersion?.let {
            require(it >= 0L) { "expectedVersion must be >= 0 when present" }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Start a pinned download acquisition (CORE-MODEL §2 Pinned Download, JobKind.DOWNLOAD).
 * Client generates [jobId] and [installationId] before send.
 */
data class StartDownloadSpec(
    val jobId: String,
    val installationId: String,
    val modelRevisionId: String,
    val artifactPackageId: String,
    val sourceUrl: String,
    val expectedSha256: String? = null,
    val expectedBytes: Long? = null,
    val targetName: String? = null,
    val displayName: String,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
        require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
        require(artifactPackageId.matches(HEX64)) { "artifactPackageId must be 64-char hex" }
        require(sourceUrl.isNotBlank()) { "sourceUrl must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        expectedSha256?.let {
            require(it.matches(HEX64)) { "expectedSha256 must be 64-char hex" }
        }
        expectedBytes?.let { require(it >= 0L) { "expectedBytes must be non-negative" } }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Start a local import (CORE-MODEL §2 Local Import, JobKind.IMPORT).
 * [assetId] is an opaque Asset handle from the asset lifecycle — never a file path.
 */
data class StartImportSpec(
    val jobId: String,
    val installationId: String,
    val modelRevisionId: String,
    val artifactPackageId: String,
    val assetId: String,
    val expectedFormat: String? = null,
    val expectedSha256: String? = null,
    val displayName: String,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
        require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
        require(artifactPackageId.matches(HEX64)) { "artifactPackageId must be 64-char hex" }
        require(assetId.isNotBlank()) { "assetId must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        expectedSha256?.let {
            require(it.matches(HEX64)) { "expectedSha256 must be 64-char hex" }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Delete an installation (JobKind.DELETE + MODEL_INSTALLATION drain).
 * Requires [expectedResourceVersion] for CAS (OpenAPI DeleteJobParameters).
 */
data class StartDeleteSpec(
    val jobId: String,
    val installationId: String,
    val expectedResourceVersion: Long,
    val forceAfterDrain: Boolean = false,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
        require(expectedResourceVersion >= 0L) {
            "expectedResourceVersion must be non-negative"
        }
    }
}

/** Cancel an in-flight acquisition job (and linked installation when applicable). */
data class CancelAcquisitionSpec(
    val jobId: String,
    val command: ModelHubCommandIdentity,
    /** When true, mark cancel-requested without forcing JOB CANCEL edge yet. */
    val requestOnly: Boolean = false,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
    }
}

/** Pin / unpin installation (eviction fence only). */
data class SetPinSpec(
    val installationId: String,
    val pinned: Boolean,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
    }
}

/**
 * Explicit LOADED_MODEL load command (CORE-MODEL §1.2 / M4).
 * Plan → admit → commit via Model Manager; the engine resolves the installed
 * GGUF at inference time. [engineBuildId] is supplied by the runtime port.
 */
data class StartLoadSpec(
    val installationId: String,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
    }
}

/** Explicit LOADED_MODEL unload command (CORE-MODEL §1.2 / M4). */
data class StartUnloadSpec(
    val installationId: String,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
    }
}

/** Result of a load/unload command (LOADED_MODEL state projection). */
data class ModelLoadResult(
    val loadedModelId: String?,
    val installationId: String,
    val state: String,
    val engineBuildId: String? = null,
    val placementClass: String? = null,
)

/**
 * License acceptance command (CORE-MODEL §8 / SEC-SUPPLY §7 / M5).
 * Acceptance binds (principal, terms digest, source assertion) append-only;
 * identical bytes from a different source never share acceptance.
 */
data class AcceptLicenseSpec(
    val installationId: String,
    val licenseDigest: String,
    val sourceAssertion: String,
    val command: ModelHubCommandIdentity,
) {
    init {
        require(installationId.isNotBlank()) { "installationId must be non-blank" }
        require(licenseDigest.matches(HEX64)) { "licenseDigest must be 64-char hex" }
        require(sourceAssertion.isNotBlank()) { "sourceAssertion must be non-blank" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Worker / control-plane progress for acquisition pipeline (not a UI command).
 * Progress dimensions match FEAT-MODELHUB §4 / FEAT-ADMIN JobProgress.
 */
data class AcquisitionProgressUpdate(
    val jobId: String,
    val networkBytes: Long = 0L,
    val materializedBytes: Long = 0L,
    val verifiedBytes: Long = 0L,
    val totalBytesKnown: Long? = null,
    val currentPhase: String? = null,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(networkBytes >= 0L) { "networkBytes must be non-negative" }
        require(materializedBytes >= 0L) { "materializedBytes must be non-negative" }
        require(verifiedBytes >= 0L) { "verifiedBytes must be non-negative" }
    }
}
