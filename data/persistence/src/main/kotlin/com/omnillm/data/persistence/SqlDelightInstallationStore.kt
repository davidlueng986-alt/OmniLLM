package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction

/**
 * SQLDelight-backed [InstallationLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Table: `installations` — MODEL_INSTALLATION projection + catalog fields.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 *
 * Domain mapping ([InstallationSnapshot]) lives in `:runtime:model-manager`.
 */
class SqlDelightInstallationStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : InstallationLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val installations: InstallationRecordDao = object : InstallationRecordDao {
        override fun findByInstallationId(installationId: String): InstallationRecordRow? =
            database.installationsQueries
                .selectByInstallationId(installationId)
                .executeAsOneOrNull()
                ?.toInstallationRow()

        override fun listByRevisionId(revisionId: String): List<InstallationRecordRow> =
            database.installationsQueries
                .listByRevisionId(revisionId)
                .executeAsList()
                .map { it.toInstallationRow() }

        override fun listAll(): List<InstallationRecordRow> =
            database.installationsQueries
                .listAll()
                .executeAsList()
                .map { it.toInstallationRow() }

        override fun listByState(state: String): List<InstallationRecordRow> {
            require(state in InstallationLedgerStates.ALL) { "unknown installation state: $state" }
            return database.installationsQueries
                .listByState(state)
                .executeAsList()
                .map { it.toInstallationRow() }
        }

        override fun listCatalog(): List<InstallationRecordRow> =
            database.installationsQueries
                .listCatalog()
                .executeAsList()
                .map { it.toInstallationRow() }

        override fun upsert(row: InstallationRecordRow) {
            require(row.state in InstallationLedgerStates.ALL) {
                "unknown installation state: ${row.state}"
            }
            database.installationsQueries.upsertInstallation(
                installation_id = row.installationId,
                revision_id = row.revisionId,
                artifact_package_id = row.artifactPackageId,
                state = row.state,
                storage_root_key = row.storageRootKey,
                trust_epoch = row.trustEpoch,
                template_epoch = row.templateEpoch,
                tokenizer_epoch = row.tokenizerEpoch,
                quarantine_job_id = row.quarantineJobId,
                quarantine_attempt_id = row.quarantineAttemptId,
                authenticity_ok = row.authenticityOk?.let { if (it) 1L else 0L },
                license_ok = row.licenseOk?.let { if (it) 1L else 0L },
                compatibility_ok = row.compatibilityOk?.let { if (it) 1L else 0L },
                performance_recorded = row.performanceRecorded?.let { if (it) 1L else 0L },
                placement_class = row.placementClass,
                pinned = if (row.pinned) 1L else 0L,
                reject_reason = row.rejectReason,
                resource_version = row.resourceVersion,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun delete(installationId: String): Boolean {
            if (findByInstallationId(installationId) == null) return false
            database.installationsQueries.deleteByInstallationId(installationId)
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Installations.toInstallationRow(): InstallationRecordRow =
    InstallationRecordRow(
        installationId = installation_id,
        revisionId = revision_id,
        artifactPackageId = artifact_package_id,
        state = state,
        storageRootKey = storage_root_key,
        trustEpoch = trust_epoch,
        templateEpoch = template_epoch,
        tokenizerEpoch = tokenizer_epoch,
        quarantineJobId = quarantine_job_id,
        quarantineAttemptId = quarantine_attempt_id,
        authenticityOk = authenticity_ok?.let { it != 0L },
        licenseOk = license_ok?.let { it != 0L },
        compatibilityOk = compatibility_ok?.let { it != 0L },
        performanceRecorded = performance_recorded?.let { it != 0L },
        placementClass = placement_class,
        pinned = pinned != 0L,
        rejectReason = reject_reason,
        resourceVersion = resource_version,
        createdAt = created_at,
        updatedAt = updated_at,
    )

