package com.omnillm.runtime.job

import com.omnillm.core.state.domain.JobId

/**
 * Durable job ledger port (control-plane sole writer — ADR-010).
 *
 * Production binds [SqlDelightJobStore] over SQLDelight
 * (`jobs` / `job_attempts` / `job_events` via [com.omnillm.data.persistence.JobLedgerPorts]).
 * Tests use [InMemoryJobStore]. Mutations must keep job state, attempt, event, and
 * checkpoint aligned in one transaction (FEAT-ADMIN §3, JOB invariant).
 */
interface JobStore {
    fun findById(jobId: JobId): JobRecord?

    fun findByClaim(
        principalId: String,
        kind: JobKind,
        idempotencyKey: String,
    ): JobRecord?

    /**
     * Insert if claim key free; otherwise return existing with [createdNew]=false.
     * Caller must enforce digest identity (IDEMPOTENCY_CONFLICT on mismatch).
     */
    fun putNew(record: JobRecord): JobRecord

    /** Replace entire snapshot (single writer, CAS on resourceVersion optional). */
    fun replace(record: JobRecord): JobRecord

    fun listByPrincipal(principalId: String): List<JobRecord>

    fun listActive(): List<JobRecord>
}

/**
 * In-memory claim ledger for unit tests and control-plane bootstrap before
 * SQL writer wiring. Not process-crash durable.
 */
class InMemoryJobStore : JobStore {
    private val byId = linkedMapOf<String, JobRecord>()
    private val byClaim = linkedMapOf<String, JobRecord>()

    private fun claimKey(principalId: String, kind: JobKind, idempotencyKey: String): String =
        "$principalId\u0000${kind.name}\u0000$idempotencyKey"

    @Synchronized
    override fun findById(jobId: JobId): JobRecord? = byId[jobId.value]

    @Synchronized
    override fun findByClaim(
        principalId: String,
        kind: JobKind,
        idempotencyKey: String,
    ): JobRecord? = byClaim[claimKey(principalId, kind, idempotencyKey)]

    @Synchronized
    override fun putNew(record: JobRecord): JobRecord {
        val id = record.identity.jobId.value
        require(id !in byId) { "jobId already present: $id" }
        val key = claimKey(
            record.identity.principalId.value,
            record.identity.kind,
            record.identity.idempotencyKey.value,
        )
        require(key !in byClaim) { "claim key already present" }
        byId[id] = record
        byClaim[key] = record
        return record
    }

    @Synchronized
    override fun replace(record: JobRecord): JobRecord {
        val id = record.identity.jobId.value
        require(id in byId) { "unknown jobId: $id" }
        val key = claimKey(
            record.identity.principalId.value,
            record.identity.kind,
            record.identity.idempotencyKey.value,
        )
        byId[id] = record
        byClaim[key] = record
        return record
    }

    @Synchronized
    override fun listByPrincipal(principalId: String): List<JobRecord> =
        byId.values.filter { it.identity.principalId.value == principalId }

    @Synchronized
    override fun listActive(): List<JobRecord> =
        byId.values.filter { !it.isTerminal }
}
