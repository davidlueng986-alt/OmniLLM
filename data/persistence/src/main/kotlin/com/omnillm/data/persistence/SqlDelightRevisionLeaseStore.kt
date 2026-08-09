package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction

/**
 * SQLDelight-backed [RevisionLeaseLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Table: `revision_leases` — REVISION_LEASE projection (CORE-MODEL §9).
 * Domain mapping lives in `:runtime:model-manager`.
 */
class SqlDelightRevisionLeaseStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : RevisionLeaseLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val leases: RevisionLeaseRecordDao = object : RevisionLeaseRecordDao {
        override fun findByLeaseId(leaseId: String): RevisionLeaseRecordRow? =
            database.revisionLeasesQueries
                .selectByLeaseId(leaseId)
                .executeAsOneOrNull()
                ?.toLeaseRow()

        override fun listActiveByRevisionId(revisionId: String): List<RevisionLeaseRecordRow> =
            database.revisionLeasesQueries
                .listActiveByRevisionId(revisionId)
                .executeAsList()
                .map { it.toLeaseRow() }

        override fun listAll(): List<RevisionLeaseRecordRow> =
            database.revisionLeasesQueries
                .listAll()
                .executeAsList()
                .map { it.toLeaseRow() }

        override fun upsert(row: RevisionLeaseRecordRow) {
            require(row.state in RevisionLeaseLedgerStates.ALL) {
                "unknown lease state: ${row.state}"
            }
            database.revisionLeasesQueries.upsertLease(
                lease_id = row.leaseId,
                revision_id = row.revisionId,
                request_id = row.requestId,
                principal_id = row.principalId,
                runtime_epoch = row.runtimeEpoch,
                state = row.state,
                installation_id = row.installationId,
                reference_count = row.referenceCount.toLong(),
                expires_at = row.expiresAt,
                expires_at_monotonic = row.expiresAtMonotonic,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun delete(leaseId: String): Boolean {
            if (findByLeaseId(leaseId) == null) return false
            database.revisionLeasesQueries.deleteByLeaseId(leaseId)
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Revision_leases.toLeaseRow(): RevisionLeaseRecordRow =
    RevisionLeaseRecordRow(
        leaseId = lease_id,
        revisionId = revision_id,
        requestId = request_id,
        principalId = principal_id,
        runtimeEpoch = runtime_epoch,
        state = state,
        installationId = installation_id,
        referenceCount = reference_count.toInt(),
        expiresAt = expires_at,
        expiresAtMonotonic = expires_at_monotonic,
        createdAt = created_at,
        updatedAt = updated_at,
    )

