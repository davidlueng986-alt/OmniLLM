package com.omnillm.features.modelhub.api

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.job.JobProgress

/**
 * UX-facing projections for FEAT-MODELHUB / UX-IA ModelHub tree.
 *
 * Dimensions stay separate (ADR-009 / UX-SAFETY-COPY): source, compatibility,
 * placement, license, installation FSM state, loaded-model FSM state, pin, refs.
 * Do **not** collapse into a single "verified / runnable" badge.
 */

/**
 * Acquisition channel labels from CORE-MODEL §2.
 * Not a catalog enum type — string labels only; unknown values fail closed at boundaries.
 */
object AcquisitionChannel {
    /** Signed catalog entry (APK-embedded / rotated root metadata). */
    const val SIGNED_CATALOG: String = "SIGNED_CATALOG"

    /** Pinned HTTPS download with explicit host/port/revision/digests. */
    const val PINNED_DOWNLOAD: String = "PINNED_DOWNLOAD"

    /** Local SAF/PFD import — no authenticity guarantee. */
    const val LOCAL_IMPORT: String = "LOCAL_IMPORT"

    /** Unknown / missing source assertions. */
    const val UNKNOWN: String = "UNKNOWN"

    val ALL: Set<String> = setOf(SIGNED_CATALOG, PINNED_DOWNLOAD, LOCAL_IMPORT, UNKNOWN)

    fun requireKnown(channel: String): String {
        require(channel in ALL) { "Unknown acquisition channel (fail closed): $channel" }
        return channel
    }
}

/**
 * License acceptance projection (CORE-MODEL §8) — not a new FSM.
 * Wire values only; acceptance events live in control-plane policy/ledger.
 */
object LicenseStatus {
    const val ACCEPTANCE_REQUIRED: String = "ACCEPTANCE_REQUIRED"
    const val ACCEPTED: String = "ACCEPTED"
    const val REVOKED: String = "REVOKED"
    const val TERMS_UPDATED: String = "TERMS_UPDATED"
    const val UNKNOWN: String = "UNKNOWN"

    val ALL: Set<String> = setOf(
        ACCEPTANCE_REQUIRED,
        ACCEPTED,
        REVOKED,
        TERMS_UPDATED,
        UNKNOWN,
    )
}

/**
 * Compatibility evidence projection (ADR-009) — never promotes authenticity.
 */
object CompatibilityStatus {
    const val NOT_CHECKED: String = "NOT_CHECKED"
    const val STATIC_OK: String = "STATIC_OK"
    const val PROBE_OK: String = "PROBE_OK"
    const val RUNTIME_OK: String = "RUNTIME_OK"
    const val EXPIRED: String = "EXPIRED"
    const val UNSUPPORTED: String = "UNSUPPORTED"

    val ALL: Set<String> = setOf(
        NOT_CHECKED,
        STATIC_OK,
        PROBE_OK,
        RUNTIME_OK,
        EXPIRED,
        UNSUPPORTED,
    )
}

/**
 * Suggested catalog entry (Suggested section) before any installation exists.
 * Identity is always [modelRevisionId] / [artifactPackageId] (ADR-008).
 */
data class CatalogModelEntry(
    val modelRevisionId: String,
    val artifactPackageId: String,
    val displayName: String,
    val acquisitionChannel: String,
    val byteLength: Long? = null,
    val quantizationDescriptorJson: String? = null,
    val licenseDigest: String? = null,
    val sourceAssertionsSummary: String? = null,
    val supportedEngineBuildIds: List<String> = emptyList(),
    val suggested: Boolean = true,
) {
    init {
        AcquisitionChannel.requireKnown(acquisitionChannel)
        require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
        require(artifactPackageId.matches(HEX64)) { "artifactPackageId must be 64-char hex" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        byteLength?.let { require(it >= 0L) { "byteLength must be non-negative" } }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Allowed ModelHub actions derived from MODEL_INSTALLATION + JOB FSM states.
 * Action IDs are stable strings for UI guards — not new domain events.
 */
object ModelHubAction {
    const val DOWNLOAD: String = "DOWNLOAD"
    const val IMPORT: String = "IMPORT"
    const val CANCEL: String = "CANCEL"
    const val RETRY: String = "RETRY"
    const val VIEW_LICENSE: String = "VIEW_LICENSE"
    const val VIEW_EVIDENCE: String = "VIEW_EVIDENCE"
    const val DELETE: String = "DELETE"
    const val PIN: String = "PIN"
    const val UNPIN: String = "UNPIN"
    const val BENCHMARK: String = "BENCHMARK"
    const val CHANGE_ALIAS: String = "CHANGE_ALIAS"
    const val LOAD: String = "LOAD"
    const val UNLOAD: String = "UNLOAD"
    const val ACCEPT_LICENSE: String = "ACCEPT_LICENSE"
}

/**
 * Model card (FEAT-MODELHUB §1).
 * Canonical IDs always present; friendly labels are projections only.
 */
data class ModelCard(
    val modelRevisionId: String,
    val artifactPackageId: String,
    val installationId: String?,
    val displayName: String,
    val alias: String? = null,
    /** MODEL_INSTALLATION canonical state ID (or null when catalog-only). */
    val installationState: String?,
    /** LOADED_MODEL canonical state ID when a load exists; null otherwise. */
    val loadedModelState: String?,
    val acquisitionChannel: String,
    val byteLength: Long? = null,
    val quantizationDescriptorJson: String? = null,
    val licenseStatus: String,
    val licenseDigest: String? = null,
    val authenticityOk: Boolean?,
    val compatibilityStatus: String,
    val placementClass: String?,
    val performanceRecorded: Boolean,
    val pinned: Boolean,
    val liveReferenceCount: Int,
    /**
     * Durable installation resource version — the delete CAS target (COR-18).
     * Null when catalog-only (no installation row) or unknown (fail closed).
     * Must equal the version the delete CAS compares against (single source).
     */
    val resourceVersion: Long? = null,
    val riskFlags: List<String> = emptyList(),
    val rejectReason: String? = null,
    val activeJobId: String? = null,
    val activeJobKind: String? = null,
    val activeJobState: String? = null,
    val jobProgress: JobProgress? = null,
    val allowedActions: List<String> = emptyList(),
) {
    init {
        AcquisitionChannel.requireKnown(acquisitionChannel)
        require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
        require(artifactPackageId.matches(HEX64)) { "artifactPackageId must be 64-char hex" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        require(licenseStatus in LicenseStatus.ALL) { "unknown licenseStatus: $licenseStatus" }
        require(compatibilityStatus in CompatibilityStatus.ALL) {
            "unknown compatibilityStatus: $compatibilityStatus"
        }
        require(liveReferenceCount >= 0) { "liveReferenceCount must be non-negative" }
        resourceVersion?.let {
            require(it >= 0L) { "resourceVersion must be non-negative" }
        }
    }

    val isInstalledReady: Boolean get() = installationState == "READY"
    val isCatalogOnly: Boolean get() = installationId == null

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/** Job row for Downloads & Imports section (JOB FSM projection). */
data class AcquisitionJobView(
    val jobId: String,
    val kind: String,
    val state: String,
    val resourceVersion: Long,
    val modelRevisionId: String? = null,
    val installationId: String? = null,
    val progress: JobProgress,
    val cancelRequested: Boolean,
    val error: OmniError? = null,
    val allowedActions: List<String> = emptyList(),
)

/**
 * Full ModelHub snapshot (UX-IA: Suggested / Installed / Downloads & Imports).
 */
data class ModelHubSnapshot(
    val snapshotVersion: Long,
    val suggested: List<ModelCard>,
    val installed: List<ModelCard>,
    val downloadsAndImports: List<AcquisitionJobView>,
    val blockingIssues: List<String> = emptyList(),
)

/** Result of a ModelHub mutation that created or claimed a Job. */
data class ModelHubJobHandle(
    val jobId: String,
    val kind: String,
    val state: String,
    val resourceVersion: Long,
    val createdNew: Boolean,
    val installationId: String? = null,
    val modelRevisionId: String? = null,
)
