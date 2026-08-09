package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.ControlPlaneWriter
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction

/**
 * Control-plane DAO for durable REVISION_LEASE rows (CORE-MODEL §9 / ADR-010).
 */

interface RevisionLeaseRecordDao {
    fun findByLeaseId(leaseId: String): RevisionLeaseRecordRow?

    fun listActiveByRevisionId(revisionId: String): List<RevisionLeaseRecordRow>

    fun listAll(): List<RevisionLeaseRecordRow>

    fun upsert(row: RevisionLeaseRecordRow)

    fun delete(leaseId: String): Boolean
}

interface RevisionLeaseLedgerPorts : ControlPlaneWriter {
    val leases: RevisionLeaseRecordDao
    val tx: ClaimLedgerTransaction
}

