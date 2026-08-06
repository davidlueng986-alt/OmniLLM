package com.omnillm.data.persistence

/**
 * Row models for commit recovery ledgers
 * (`commit_records`, `prepared_operations`, `commit_resource_bindings`
 * in omnillm-schema.sql / REL-RECOVERY).
 *
 * Authority: runtime-recovery-fixtures RR-001..RR-007, ADR-004/005, DATA-STATES COMMIT.
 * INTENT_RECORDED must be durable **before** a worker receives commitLoad/commitInference.
 */

/** States from specs/state-machines.yaml#COMMIT / schema CHECK on commit_records. */
object CommitLedgerStates {
    val ALL: Set<String> = setOf(
        "INTENT_RECORDED",
        "EXECUTING",
        "RESULT_RECORDED",
        "RECONCILING",
        "COMMITTED",
        "ABORTED",
        "UNCERTAIN_QUARANTINED",
    )

    val TERMINAL: Set<String> = setOf(
        "COMMITTED",
        "ABORTED",
        "UNCERTAIN_QUARANTINED",
    )

    /** Open commits that recovery must reconcile (non-terminal). */
    val OPEN: Set<String> = ALL - TERMINAL
}

/** Reconciliation dispositions from schema CHECK on commit_records. */
object CommitReconciliationDispositions {
    val ALL: Set<String> = setOf(
        "RESULT_FOUND",
        "ROLLED_BACK",
        "POISONED",
        "QUARANTINED",
        "UNPROVABLE",
    )
}

/** States from specs/state-machines.yaml#OPERATION / prepared_operations. */
object PreparedOperationLedgerStates {
    val ALL: Set<String> = setOf(
        "PREPARED",
        "STARTING",
        "RUNNING",
        "CANCELLING",
        "RECONCILING",
        "COMPLETED",
        "FAILED",
        "CANCELLED",
        "ABORTED_UNCERTAIN",
    )

    val TERMINAL: Set<String> = setOf(
        "COMPLETED",
        "FAILED",
        "CANCELLED",
        "ABORTED_UNCERTAIN",
    )
}

/** Binding-role / disposition on commit_resource_bindings. */
object CommitResourceBindingRoles {
    val ALL: Set<String> = setOf("SOURCE", "TARGET", "TRANSFERRED", "REMAINDER")
}

object CommitResourceBindingDispositions {
    val ALL: Set<String> = setOf("PREPARED", "TRANSFERRED", "RELEASED", "QUARANTINED")
}

/**
 * Durable commit intent/result row (`commit_records`).
 *
 * Binding dimensions under the same [commitId] must not diverge (RR-007):
 * principal, reservation, revision lease, runtime/revocation epoch, digest, nonce, boot.
 */
data class CommitRecordRow(
    val commitId: String,
    val requestId: String,
    val principalId: String,
    val planId: String,
    val reservationId: String,
    val revisionLeaseId: String,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val sourceSessionEpoch: Long? = null,
    val targetPolicyDigest: String,
    val engineBuildId: String,
    val canonicalInputDigest: String,
    /** SHA-256 hex of one-shot nonce (schema: commit_nonce_digest UNIQUE). */
    val commitNonceDigest: String,
    val state: String,
    val resultJson: String? = null,
    val errorCode: String? = null,
    val reconciliationDisposition: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be >= 0" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be >= 0" }
        require(state in CommitLedgerStates.ALL) { "unknown commit state: $state" }
        require(targetPolicyDigest.length == 64) { "targetPolicyDigest must be 64 hex chars" }
        require(canonicalInputDigest.length == 64) { "canonicalInputDigest must be 64 hex chars" }
        require(commitNonceDigest.length == 64) { "commitNonceDigest must be 64 hex chars" }
        reconciliationDisposition?.let {
            require(it in CommitReconciliationDispositions.ALL) {
                "unknown reconciliation disposition: $it"
            }
        }
    }

    /** Binding equality for RR-007 / IDEMPOTENCY_CONFLICT. */
    fun bindingKey(): CommitBindingKey =
        CommitBindingKey(
            commitId = commitId,
            principalId = principalId,
            reservationId = reservationId,
            revisionLeaseId = revisionLeaseId,
            runtimeEpoch = runtimeEpoch,
            revocationEpoch = revocationEpoch,
            canonicalInputDigest = canonicalInputDigest,
            commitNonceDigest = commitNonceDigest,
            issuerBootId = issuerBootId,
            planId = planId,
            requestId = requestId,
            engineBuildId = engineBuildId,
        )
}

data class CommitBindingKey(
    val commitId: String,
    val principalId: String,
    val reservationId: String,
    val revisionLeaseId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val canonicalInputDigest: String,
    val commitNonceDigest: String,
    val issuerBootId: String,
    val planId: String,
    val requestId: String,
    val engineBuildId: String,
)

data class PreparedOperationRow(
    val preparedOperationId: String,
    val operationId: String,
    val requestId: String,
    val commitId: String,
    val principalId: String,
    val reservationId: String,
    val revisionLeaseId: String,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val sourceSessionId: String? = null,
    val sourceSessionEpoch: Long? = null,
    val targetSessionId: String? = null,
    val canonicalInputDigest: String,
    val state: String,
    val startClaimedAt: String? = null,
    val resultJson: String? = null,
    val errorCode: String? = null,
    val reconciliationDisposition: String? = null,
    val resourceVersion: Long = 0,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(runtimeEpoch >= 0L)
        require(revocationEpoch >= 0L)
        require(state in PreparedOperationLedgerStates.ALL) {
            "unknown prepared operation state: $state"
        }
        require(canonicalInputDigest.length == 64)
        require(resourceVersion >= 0L)
    }
}

data class CommitResourceBindingRow(
    val commitId: String,
    val allocationId: String,
    val bindingRole: String,
    val resourceVectorDigest: String,
    val disposition: String,
    val updatedAt: String,
) {
    init {
        require(bindingRole in CommitResourceBindingRoles.ALL)
        require(disposition in CommitResourceBindingDispositions.ALL)
        require(resourceVectorDigest.length == 64)
    }
}
