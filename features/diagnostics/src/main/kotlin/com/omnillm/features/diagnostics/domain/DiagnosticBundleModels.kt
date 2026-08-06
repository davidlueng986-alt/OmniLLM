package com.omnillm.features.diagnostics.domain

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.observability.DiagnosticAllowlist

/**
 * Category sensitivity for export preview (FEAT-DIAGNOSTICS §1/§5).
 * Allowlist categories only — unknown categories fail closed at API boundary.
 */
enum class CategorySensitivity {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        fun forCategory(category: DiagnosticAllowlist.Category): CategorySensitivity =
            when (category) {
                DiagnosticAllowlist.Category.MANIFEST,
                DiagnosticAllowlist.Category.RUNTIME_VERSIONS,
                DiagnosticAllowlist.Category.FILE_DIGESTS,
                DiagnosticAllowlist.Category.EVIDENCE_LABELS,
                -> LOW

                DiagnosticAllowlist.Category.CONFIGURATION,
                DiagnosticAllowlist.Category.CAPABILITY_SNAPSHOT,
                DiagnosticAllowlist.Category.MODEL_ENGINE_IDS,
                DiagnosticAllowlist.Category.RESOURCE_SNAPSHOT,
                DiagnosticAllowlist.Category.REPRODUCTION_HINTS,
                DiagnosticAllowlist.Category.INTEGRITY_RESULTS,
                -> MEDIUM

                DiagnosticAllowlist.Category.REQUEST_JOB_STATE,
                DiagnosticAllowlist.Category.EVENTS_TRACES,
                DiagnosticAllowlist.Category.ERROR_CHAIN,
                DiagnosticAllowlist.Category.CRASH_SUMMARY,
                -> HIGH
            }
    }
}

/**
 * One export category offered in the pre-start preview.
 */
data class ExportCategoryPreview(
    val category: DiagnosticAllowlist.Category,
    val sensitivity: CategorySensitivity,
    val estimatedBytes: Long,
    val includedByDefault: Boolean,
    val descriptionKey: String,
) {
    init {
        require(estimatedBytes >= 0L) { "estimatedBytes must be non-negative" }
        require(descriptionKey.isNotBlank()) { "descriptionKey must be non-blank" }
    }
}

/**
 * Pre-start plan shown to the user (FEAT-DIAGNOSTICS §1).
 * Plan has no domain mutation (ADR-002) — this is pure projection.
 */
data class DiagnosticExportPlan(
    val categories: List<ExportCategoryPreview>,
    val estimatedTotalBytes: Long,
    val defaultTtlSeconds: Int,
    val encryptionOptional: Boolean,
    val includeDetail: Boolean,
    val shareIrreversibleNoticeKey: String = "diagnostics.share.irreversible",
) {
    init {
        require(estimatedTotalBytes >= 0L) { "estimatedTotalBytes must be non-negative" }
        require(defaultTtlSeconds in 60..604_800) { "defaultTtlSeconds must be 60..604800" }
    }
}

/**
 * Content-addressed file entry inside a sealed bundle (FEAT-DIAGNOSTICS §4).
 * Paths are role labels only — never private filesystem paths.
 */
data class BundleFileEntry(
    val pathRole: String,
    val sha256: String,
    val byteLength: Long,
    val redactedUtf8: String? = null,
) {
    init {
        require(pathRole.isNotBlank()) { "pathRole must be non-blank" }
        require(sha256.matches(HEX64)) { "sha256 must be 64-char lower-case hex" }
        require(byteLength >= 0L) { "byteLength must be non-negative" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Sealed diagnostic bundle snapshot (FEAT-DIAGNOSTICS §2/§4).
 * [bundleId] is random client-or-runtime generated UUID string.
 */
data class DiagnosticBundleSnapshot(
    val bundleId: String,
    val jobId: String?,
    val ownerPrincipalClass: String,
    val state: String,
    val schemaVersion: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val categoriesIncluded: List<String>,
    val files: List<BundleFileEntry>,
    /** Manifest digest over sorted pathRole + file digests (integrity seal). */
    val manifestDigest: String?,
    val reason: DiagnosticReasonModel?,
    val estimatedBytes: Long,
    val shareIrreversibleDisclosed: Boolean,
    val error: OmniError? = null,
) {
    init {
        require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
        require(DiagnosticBundleStates.isKnown(state)) {
            "unknown bundle state (fail closed): $state"
        }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs must be non-negative" }
        require(expiresAtEpochMs >= createdAtEpochMs) {
            "expiresAtEpochMs must be >= createdAtEpochMs"
        }
        require(estimatedBytes >= 0L) { "estimatedBytes must be non-negative" }
        manifestDigest?.let {
            require(it.matches(HEX64)) { "manifestDigest must be 64-char hex" }
        }
        // Partial bundles must not claim READY.
        if (state == DiagnosticBundleStates.READY) {
            require(manifestDigest != null && files.isNotEmpty()) {
                "READY bundle requires sealed manifest and at least one file"
            }
        }
    }

    val isShareable: Boolean get() = DiagnosticBundleStates.isShareable(state)

    fun isExpired(nowEpochMs: Long): Boolean =
        state == DiagnosticBundleStates.EXPIRED ||
            (state == DiagnosticBundleStates.READY && nowEpochMs >= expiresAtEpochMs)

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
