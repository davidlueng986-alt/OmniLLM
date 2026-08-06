package com.omnillm.data.persistence

/**
 * SQLDelight-backed [JobLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Tables: `jobs`, `job_attempts`, `job_events`.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 *
 * Domain mapping (JobRecord / JobStore) lives in `:runtime:job-manager`
 * [com.omnillm.runtime.job.SqlDelightJobStore].
 */
class SqlDelightJobLedgerStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : JobLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val jobs: JobRecordDao = object : JobRecordDao {
        override fun findByJobId(jobId: String): JobLedgerRow? =
            database.jobsQueries
                .selectByJobId(jobId)
                .executeAsOneOrNull()
                ?.toJobRow()

        override fun findByClaimKey(
            principalId: String,
            jobKind: String,
            idempotencyKey: String,
        ): JobLedgerRow? =
            database.jobsQueries
                .selectByClaimKey(principalId, jobKind, idempotencyKey)
                .executeAsOneOrNull()
                ?.toJobRow()

        override fun listByPrincipal(principalId: String): List<JobLedgerRow> =
            database.jobsQueries
                .listByPrincipal(principalId)
                .executeAsList()
                .map { it.toJobRow() }

        override fun listActive(): List<JobLedgerRow> =
            database.jobsQueries
                .listActive()
                .executeAsList()
                .map { it.toJobRow() }

        override fun listAll(): List<JobLedgerRow> =
            database.jobsQueries
                .listAll()
                .executeAsList()
                .map { it.toJobRow() }

        override fun insert(row: JobLedgerRow) {
            database.jobsQueries.insertJob(
                job_id = row.jobId,
                principal_id = row.principalId,
                job_kind = row.jobKind,
                idempotency_key = row.idempotencyKey,
                canonical_spec_json = row.canonicalSpecJson,
                state = row.state,
                resource_version = row.resourceVersion,
                checkpoint_json = row.checkpointJson,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun update(row: JobLedgerRow): Boolean {
            if (findByJobId(row.jobId) == null) return false
            database.jobsQueries.updateJob(
                canonical_spec_json = row.canonicalSpecJson,
                state = row.state,
                resource_version = row.resourceVersion,
                checkpoint_json = row.checkpointJson,
                updated_at = row.updatedAt,
                job_id = row.jobId,
            )
            return true
        }

        override fun delete(jobId: String): Boolean {
            if (findByJobId(jobId) == null) return false
            database.jobsQueries.deleteByJobId(jobId)
            return true
        }
    }

    override val attempts: JobAttemptDao = object : JobAttemptDao {
        override fun listByJobId(jobId: String): List<JobAttemptLedgerRow> =
            database.jobAttemptsQueries
                .selectByJobId(jobId)
                .executeAsList()
                .map { it.toAttemptRow() }

        override fun insert(row: JobAttemptLedgerRow) {
            database.jobAttemptsQueries.insertAttempt(
                job_id = row.jobId,
                attempt_no = row.attemptNo.toLong(),
                state = row.state,
                started_at = row.startedAt,
                ended_at = row.endedAt,
            )
        }

        override fun upsert(row: JobAttemptLedgerRow) {
            database.jobAttemptsQueries.upsertAttempt(
                job_id = row.jobId,
                attempt_no = row.attemptNo.toLong(),
                state = row.state,
                started_at = row.startedAt,
                ended_at = row.endedAt,
            )
        }

        override fun deleteByJobId(jobId: String) {
            database.jobAttemptsQueries.deleteByJobId(jobId)
        }
    }

    override val events: JobEventDao = object : JobEventDao {
        override fun listByJobId(jobId: String): List<JobEventLedgerRow> =
            database.jobEventsQueries
                .selectByJobId(jobId)
                .executeAsList()
                .map { it.toEventRow() }

        override fun insert(row: JobEventLedgerRow) {
            database.jobEventsQueries.insertEvent(
                event_id = row.eventId,
                job_id = row.jobId,
                attempt_no = row.attemptNo?.toLong(),
                event_kind = row.eventKind,
                payload_json = row.payloadJson,
                occurred_at = row.occurredAt,
            )
        }

        override fun deleteByJobId(jobId: String) {
            database.jobEventsQueries.deleteByJobId(jobId)
        }
    }

    override val tx: JobLedgerTransaction = object : JobLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Jobs.toJobRow(): JobLedgerRow =
    JobLedgerRow(
        jobId = job_id,
        principalId = principal_id,
        jobKind = job_kind,
        idempotencyKey = idempotency_key,
        canonicalSpecJson = canonical_spec_json,
        state = state,
        resourceVersion = resource_version,
        checkpointJson = checkpoint_json,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun Job_attempts.toAttemptRow(): JobAttemptLedgerRow =
    JobAttemptLedgerRow(
        jobId = job_id,
        attemptNo = attempt_no.toInt(),
        state = state,
        startedAt = started_at,
        endedAt = ended_at,
    )

private fun Job_events.toEventRow(): JobEventLedgerRow =
    JobEventLedgerRow(
        eventId = event_id,
        jobId = job_id,
        attemptNo = attempt_no?.toInt(),
        eventKind = event_kind,
        payloadJson = payload_json,
        occurredAt = occurred_at,
    )
