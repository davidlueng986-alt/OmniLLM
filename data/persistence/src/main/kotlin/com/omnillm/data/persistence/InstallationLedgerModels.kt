package com.omnillm.data.persistence

/**
 * Control-plane MODEL_INSTALLATION durable rows (CORE-MODEL / DATA-OWNERSHIP).
 *
 * Authority base: `specs/database/omnillm-schema.sql#installations`.
 * Extended for quarantine keys, evaluation dimensions, pin, and catalog
 * resource_version (ModelHub projection).
 *
 * LoadedModel native handles are **not** stored here (separate aggregate).
 */

/** MODEL_INSTALLATION FSM states from specs/state-machines.yaml#MODEL_INSTALLATION. */
object InstallationLedgerStates {
    val ALL: Set<String> = setOf(
        "DISCOVERED",
        "ACQUIRING",
        "QUARANTINED",
        "VERIFYING",
        "COMPATIBILITY_CHECK",
        "READY",
        "DRAINING",
        "REVOKED",
        "CORRUPT",
        "REJECTED",
        "DELETING",
        "DELETED",
    )

    val TERMINAL: Set<String> = setOf("DELETED")

    /** States eligible for load planning (CORE-MODEL §1.1). */
    val LOAD_PLANNING: Set<String> = setOf("READY")
}

/**
 * Durable installation row.
 *
 * [storageRootKey] is always non-empty: `pending/<installationId>` before promote,
 * then content-addressed layout key after atomic promote.
 */
data class InstallationRecordRow(
    val installationId: String,
    val revisionId: String,
    val artifactPackageId: String,
    val state: String,
    val storageRootKey: String,
    val trustEpoch: Long = 0L,
    val templateEpoch: Long = 0L,
    val tokenizerEpoch: Long = 0L,
    val quarantineJobId: String? = null,
    val quarantineAttemptId: String? = null,
    val authenticityOk: Boolean? = null,
    val licenseOk: Boolean? = null,
    val compatibilityOk: Boolean? = null,
    val performanceRecorded: Boolean? = null,
    val placementClass: String? = null,
    val pinned: Boolean = false,
    val rejectReason: String? = null,
    val resourceVersion: Long = 0L,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(installationId.isNotEmpty()) { "installationId must be non-empty" }
        require(revisionId.isNotEmpty()) { "revisionId must be non-empty" }
        require(artifactPackageId.isNotEmpty()) { "artifactPackageId must be non-empty" }
        require(state in InstallationLedgerStates.ALL) { "unknown installation state: $state" }
        require(storageRootKey.isNotEmpty()) { "storageRootKey must be non-empty" }
        require(trustEpoch >= 0L && templateEpoch >= 0L && tokenizerEpoch >= 0L) {
            "epochs must be non-negative"
        }
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
    }

    companion object {
        fun pendingStorageRootKey(installationId: String): String = "pending/$installationId"
    }
}
