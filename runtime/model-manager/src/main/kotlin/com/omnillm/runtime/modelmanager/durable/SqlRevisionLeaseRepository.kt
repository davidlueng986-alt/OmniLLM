package com.omnillm.runtime.modelmanager.durable

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.RevisionLeaseAggregate
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.data.persistence.RevisionLeaseLedgerPorts
import com.omnillm.data.persistence.RevisionLeaseRecordRow
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository
import java.time.Instant

/**
 * SQLDelight-backed [RevisionLeaseRepository] (CORE-MODEL §9 / ADR-010).
 *
 * Delete fencing survives process restart while ACTIVE/DRAINING leases exist.
 */
class SqlRevisionLeaseRepository(
    private val ports: RevisionLeaseLedgerPorts,
    private val clock: () -> String = { Instant.now().toString() },
    writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : RevisionLeaseRepository {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
        require(ports.writerRole == writerRole) {
            "RevisionLeaseLedgerPorts writerRole mismatch"
        }
    }

    override suspend fun get(leaseId: RevisionLeaseId): RevisionLeaseSnapshot? =
        ports.leases.findByLeaseId(leaseId.value)?.toSnapshot()

    override suspend fun findActiveByRevision(modelRevisionId: ModelRevisionId): List<RevisionLeaseSnapshot> =
        ports.leases.listActiveByRevisionId(modelRevisionId.hex).map { it.toSnapshot() }

    override suspend fun save(snapshot: RevisionLeaseSnapshot): OmniResult<Unit> {
        return try {
            val existing = ports.leases.findByLeaseId(snapshot.leaseId.value)
            val now = clock()
            val createdAt = existing?.createdAt ?: now
            ports.tx.inTransaction {
                ports.leases.upsert(snapshot.toRow(createdAt, now))
            }
            OmniResult.ok(Unit)
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "revision lease save failed: ${e.javaClass.simpleName}",
                    details = mapOf("leaseId" to snapshot.leaseId.value),
                ),
            )
        }
    }

    override suspend fun delete(leaseId: RevisionLeaseId): OmniResult<Unit> {
        return try {
            ports.tx.inTransaction {
                ports.leases.delete(leaseId.value)
            }
            OmniResult.ok(Unit)
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "revision lease delete failed: ${e.javaClass.simpleName}",
                    details = mapOf("leaseId" to leaseId.value),
                ),
            )
        }
    }
}

internal fun RevisionLeaseRecordRow.toSnapshot(): RevisionLeaseSnapshot =
    RevisionLeaseSnapshot(
        aggregate = RevisionLeaseAggregate(
            leaseId = RevisionLeaseId(leaseId),
            requestId = RequestId(requestId),
            state = state,
        ),
        modelRevisionId = ModelRevisionId.parse(revisionId),
        principalId = principalId,
        runtimeEpoch = runtimeEpoch,
        installationId = installationId?.let { InstallationId(it) },
        referenceCount = referenceCount,
        expiresAtMonotonic = expiresAtMonotonic,
    )

internal fun RevisionLeaseSnapshot.toRow(
    createdAt: String,
    updatedAt: String,
): RevisionLeaseRecordRow =
    RevisionLeaseRecordRow(
        leaseId = leaseId.value,
        revisionId = modelRevisionId.hex,
        requestId = requestId.value,
        principalId = principalId,
        runtimeEpoch = runtimeEpoch,
        state = state,
        installationId = installationId?.value,
        referenceCount = referenceCount,
        expiresAt = null,
        expiresAtMonotonic = expiresAtMonotonic,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
