package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot
import com.omnillm.runtime.modelmanager.guards.RevisionLeaseGuardAtoms
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository

/**
 * RevisionLease lifecycle (CORE-MODEL §9 / DATA-STATES REVISION_LEASE).
 *
 * Accept request → grant lease (ACTIVE). Release/expiry → DRAINING →
 * REFERENCES_ZERO → RELEASED. Delete cannot pass while ACTIVE or DRAINING.
 */
class RevisionLeaseService(
    private val repository: RevisionLeaseRepository,
    private val references: ReferenceSnapshotPort,
) {

    suspend fun grant(
        leaseId: RevisionLeaseId,
        requestId: RequestId,
        modelRevisionId: ModelRevisionId,
        principalId: String,
        runtimeEpoch: Long,
        installationId: InstallationId? = null,
        expiresAtMonotonic: Long? = null,
    ): OmniResult<RevisionLeaseSnapshot> {
        repository.get(leaseId)?.let {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "revision lease already exists",
                    details = mapOf("leaseId" to leaseId.value),
                ),
            )
        }
        val snap = RevisionLeaseSnapshot.active(
            leaseId = leaseId,
            requestId = requestId,
            modelRevisionId = modelRevisionId,
            principalId = principalId,
            runtimeEpoch = runtimeEpoch,
            installationId = installationId,
            expiresAtMonotonic = expiresAtMonotonic,
        )
        return when (val s = repository.save(snap)) {
            is OmniResult.Ok -> OmniResult.ok(snap)
            is OmniResult.Err -> s
        }
    }

    /** ACTIVE → DRAINING (beginDrain). Does not erase live references. */
    suspend fun beginReleaseOrExpiry(leaseId: RevisionLeaseId): OmniResult<RevisionLeaseSnapshot> {
        val current = repository.get(leaseId) ?: return notFound(leaseId)
        val applied = when (val r = current.aggregate.apply("RELEASE_OR_EXPIRY")) {
            is AggregateTransitionResult.Success -> current.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "illegal lease transition",
                        details = mapOf(
                            "leaseId" to leaseId.value,
                            "state" to current.state,
                            "event" to "RELEASE_OR_EXPIRY",
                        ),
                    ),
                )
        }
        return persist(applied)
    }

    /** DRAINING → RELEASED when reference count is zero. */
    suspend fun completeRelease(leaseId: RevisionLeaseId): OmniResult<RevisionLeaseSnapshot> {
        val current = repository.get(leaseId) ?: return notFound(leaseId)
        val refs = references.leaseReferences(leaseId)
        val count = if (refs.isZero) 0 else maxOf(current.referenceCount, refs.total)
        val guards = RevisionLeaseGuardAtoms.evaluator(referenceCount = if (refs.isZero) 0 else count)
        val applied = when (val r = current.aggregate.apply("REFERENCES_ZERO", guards)) {
            is AggregateTransitionResult.Success ->
                current.copy(aggregate = r.aggregate, referenceCount = 0)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "lease still referenced",
                        details = mapOf(
                            "leaseId" to leaseId.value,
                            "state" to current.state,
                            "references" to refs.total.toString(),
                        ),
                    ),
                )
        }
        return persist(applied)
    }

    suspend fun adjustReferences(leaseId: RevisionLeaseId, delta: Int): OmniResult<RevisionLeaseSnapshot> {
        val current = repository.get(leaseId) ?: return notFound(leaseId)
        val next = (current.referenceCount + delta).coerceAtLeast(0)
        return persist(current.copy(referenceCount = next))
    }

    /** True when any ACTIVE/DRAINING lease still pins the revision. */
    suspend fun isRevisionPinned(modelRevisionId: ModelRevisionId): Boolean {
        return repository.findActiveByRevision(modelRevisionId).any { it.blocksDelete() }
    }

    private suspend fun persist(snap: RevisionLeaseSnapshot): OmniResult<RevisionLeaseSnapshot> =
        when (val s = repository.save(snap)) {
            is OmniResult.Ok -> OmniResult.ok(snap)
            is OmniResult.Err -> s
        }

    private fun notFound(id: RevisionLeaseId): OmniResult<Nothing> =
        OmniResult.err(
            OmniError.NOT_FOUND(
                message = "revision lease not found",
                details = mapOf("leaseId" to id.value),
            ),
        )
}
