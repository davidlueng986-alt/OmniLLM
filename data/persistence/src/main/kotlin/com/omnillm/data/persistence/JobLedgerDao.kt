package com.omnillm.data.persistence

/**
 * Control-plane DAOs for durable JOB ledger (FEAT-ADMIN / ADR-010).
 *
 * Mutations that touch job + attempts + events must run inside [JobLedgerTransaction]
 * so state, attempt, event, and checkpoint stay aligned (DATA-OWNERSHIP §3).
 */

/** Atomic job ledger transaction boundary. */
interface JobLedgerTransaction {
    fun <T> inTransaction(block: () -> T): T
}

interface JobRecordDao {
    fun findByJobId(jobId: String): JobLedgerRow?

    fun findByClaimKey(
        principalId: String,
        jobKind: String,
        idempotencyKey: String,
    ): JobLedgerRow?

    fun listByPrincipal(principalId: String): List<JobLedgerRow>

    fun listActive(): List<JobLedgerRow>

    fun listAll(): List<JobLedgerRow>

    fun insert(row: JobLedgerRow)

    fun update(row: JobLedgerRow): Boolean

    fun delete(jobId: String): Boolean
}

interface JobAttemptDao {
    fun listByJobId(jobId: String): List<JobAttemptLedgerRow>

    fun insert(row: JobAttemptLedgerRow)

    fun upsert(row: JobAttemptLedgerRow)

    fun deleteByJobId(jobId: String)
}

interface JobEventDao {
    fun listByJobId(jobId: String): List<JobEventLedgerRow>

    fun insert(row: JobEventLedgerRow)

    fun deleteByJobId(jobId: String)
}

interface CatalogTrustStateDao {
    fun get(): CatalogTrustStateRow?

    fun upsert(row: CatalogTrustStateRow)
}

/**
 * Bundled job-ledger ports for control-plane sole writer (ADR-010).
 */
interface JobLedgerPorts : ControlPlaneWriter {
    val jobs: JobRecordDao
    val attempts: JobAttemptDao
    val events: JobEventDao
    val tx: JobLedgerTransaction
}
