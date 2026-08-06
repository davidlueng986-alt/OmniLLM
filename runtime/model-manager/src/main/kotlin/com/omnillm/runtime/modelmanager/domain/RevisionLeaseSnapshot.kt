package com.omnillm.runtime.modelmanager.domain

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.RevisionLeaseAggregate
import com.omnillm.core.state.domain.RevisionLeaseId

/**
 * REVISION_LEASE projection (CORE-MODEL §9 / DATA-STATES).
 *
 * Created when a request is accepted; pins the revision so delete can only
 * DRAIN until request / Session / LoadedModel / Job references reach zero.
 */
data class RevisionLeaseSnapshot(
    val aggregate: RevisionLeaseAggregate,
    val modelRevisionId: ModelRevisionId,
    val principalId: String,
    val runtimeEpoch: Long,
    val installationId: com.omnillm.core.state.domain.InstallationId? = null,
    /** Live references covered by this lease (request/model/session/job). */
    val referenceCount: Int = 1,
    val expiresAtMonotonic: Long? = null,
) {
    val leaseId: RevisionLeaseId get() = aggregate.leaseId
    val requestId: RequestId get() = aggregate.requestId
    val state: String get() = aggregate.state

    fun isActive(): Boolean = state == "ACTIVE"
    fun blocksDelete(): Boolean = state == "ACTIVE" || state == "DRAINING"

    init {
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(referenceCount >= 0) { "referenceCount must be non-negative" }
    }

    companion object {
        fun active(
            leaseId: RevisionLeaseId,
            requestId: RequestId,
            modelRevisionId: ModelRevisionId,
            principalId: String,
            runtimeEpoch: Long,
            installationId: com.omnillm.core.state.domain.InstallationId? = null,
            expiresAtMonotonic: Long? = null,
        ): RevisionLeaseSnapshot =
            RevisionLeaseSnapshot(
                aggregate = RevisionLeaseAggregate.initial(leaseId, requestId),
                modelRevisionId = modelRevisionId,
                principalId = principalId,
                runtimeEpoch = runtimeEpoch,
                installationId = installationId,
                referenceCount = 1,
                expiresAtMonotonic = expiresAtMonotonic,
            )
    }
}
