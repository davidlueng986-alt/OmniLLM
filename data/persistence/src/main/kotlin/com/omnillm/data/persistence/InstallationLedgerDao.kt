package com.omnillm.data.persistence

/**
 * Control-plane DAO for durable MODEL_INSTALLATION rows (CORE-MODEL / ADR-010).
 *
 * Open only from the runtime control plane. UI / workers must not hold writers.
 */

interface InstallationRecordDao {
    fun findByInstallationId(installationId: String): InstallationRecordRow?

    fun listByRevisionId(revisionId: String): List<InstallationRecordRow>

    fun listAll(): List<InstallationRecordRow>

    fun listByState(state: String): List<InstallationRecordRow>

    /** Catalog projection: all non-DELETED installations. */
    fun listCatalog(): List<InstallationRecordRow>

    fun upsert(row: InstallationRecordRow)

    fun delete(installationId: String): Boolean
}

/**
 * Bundled installation-ledger ports for control-plane sole writer (ADR-010).
 */
interface InstallationLedgerPorts : ControlPlaneWriter {
    val installations: InstallationRecordDao
    val tx: ClaimLedgerTransaction
}
