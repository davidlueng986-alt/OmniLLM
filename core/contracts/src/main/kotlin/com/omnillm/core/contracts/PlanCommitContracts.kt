package com.omnillm.core.contracts

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.OperatingConstraint
import com.omnillm.core.resource.ReservationId

/**
 * Re-export of catalog [OmniResult] for contract-layer call sites.
 * Cross-module suspend APIs return OmniResult rather than throwing for
 * expected semantic failures.
 */
typealias ContractResult<T> = OmniResult<T>

/**
 * Pure planning result (ADR-002 / INV-002–003).
 *
 * Plan has **no** domain mutation. It is a bounded analysis bound to request,
 * principal, load key inputs, resource envelope, and expiry.
 */
data class Plan(
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val modelRevisionId: ModelRevisionId,
    val engineBuildId: EngineBuildId,
    val deviceExecutionFingerprint: DeviceExecutionFingerprint,
    val canonicalInputDigest: Sha256Digest,
    val resourceEnvelope: ResourceEnvelope,
    val operatingConstraint: OperatingConstraint = OperatingConstraint.NONE,
    /** Monotonic expiry for this one-shot plan. */
    val expiryMonotonic: Long,
    val runtimeEpoch: Long,
    val sourceSessionEpoch: Long? = null,
) {
    init {
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        sourceSessionEpoch?.let {
            require(it >= 0L) { "sourceSessionEpoch must be non-negative" }
        }
    }
}

/**
 * Commit intent after a successful reservation (ADR-002).
 * Queryable by [commitId] after reply loss (ADR-004/005).
 */
data class Commit(
    val commitId: CommitId,
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val reservationId: ReservationId,
    val revisionLeaseId: RevisionLeaseId,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val sourceSessionEpoch: Long?,
    val engineBuildId: EngineBuildId,
    val canonicalInputDigest: Sha256Digest,
    val oneShotNonce: String,
) {
    init {
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(oneShotNonce.isNotEmpty()) { "oneShotNonce must be non-empty" }
        sourceSessionEpoch?.let {
            require(it >= 0L) { "sourceSessionEpoch must be non-negative" }
        }
    }
}

/**
 * Durable prepared operation after domain commit, before or during execute
 * (catalog `PreparedOperationId` invariants).
 *
 * Start claim is persisted before any worker start side effect.
 * Terminal result includes allocation/session reconciliation disposition.
 */
data class PreparedOperation(
    val preparedOperationId: PreparedOperationId,
    val operationId: String,
    val requestId: RequestId,
    val commitId: CommitId,
    val principalId: PrincipalId,
    val reservationId: ReservationId,
    val revisionLeaseId: RevisionLeaseId,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val sourceSessionEpoch: Long?,
    val targetSessionId: SessionHandleId?,
    val canonicalInputDigest: Sha256Digest,
    /** Resident allocation created at commit conversion, if any. */
    val allocationHandleId: AllocationHandleId? = null,
) {
    init {
        require(operationId.isNotEmpty()) { "operationId must be non-empty" }
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        sourceSessionEpoch?.let {
            require(it >= 0L) { "sourceSessionEpoch must be non-negative" }
        }
    }
}

/**
 * Client claim envelope for async / command operations
 * (catalog CommandRequest / request identity rules).
 *
 * Client generates [requestId] and [idempotencyKey] before send;
 * server claim-or-return; on reply loss query, do not blindly replay (ADR-004/005).
 */
data class RequestIdentity(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val idempotencyKey: IdempotencyKey,
    val operationKind: String,
    val canonicalInputDigest: Sha256Digest,
) {
    init {
        require(operationKind.isNotEmpty()) { "operationKind must be non-empty" }
    }
}

/**
 * Load key dimensions that determine LoadedModel shareability
 * (catalog `LoadKey` required fields — pure data, no engine types).
 */
data class LoadKey(
    val modelRevisionId: ModelRevisionId,
    val engineBuildId: EngineBuildId,
    val backend: String,
    val deviceExecutionFingerprint: DeviceExecutionFingerprint,
    val templateEpoch: Long,
    val tokenizerEpoch: Long,
    val loadConfigurationDigest: Sha256Digest,
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(templateEpoch >= 0L) { "templateEpoch must be non-negative" }
        require(tokenizerEpoch >= 0L) { "tokenizerEpoch must be non-negative" }
    }
}
