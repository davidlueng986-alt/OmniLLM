package com.omnillm.core.contracts

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.ReservationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RR-007: changed reservation, revision lease, principal, digest or revocation
 * epoch under the same CommitId → IDEMPOTENCY_CONFLICT (runtime-recovery-fixtures).
 *
 * Pure binding equality for [Commit] / [PreparedOperation] — no domain mutation.
 */
class CommitBindingReconcileTest {

    private val digestA =
        Sha256Digest.parse("a".repeat(64))
    private val digestB =
        Sha256Digest.parse("b".repeat(64))
    private val revision =
        ModelRevisionId.parse("d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a")

    private fun baseCommit(
        digest: Sha256Digest = digestA,
        principal: String = "principal-1",
        reservation: String = "rsv-1",
        lease: String = "lease-1",
        runtimeEpoch: Long = 1L,
        revocationEpoch: Long = 0L,
    ): Commit =
        Commit(
            commitId = CommitId.parse("22222222-2222-2222-2222-222222222222"),
            planId = PlanId.parse("plan-1"),
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse(principal),
            reservationId = ReservationId.parse(reservation),
            revisionLeaseId = RevisionLeaseId.parse(lease),
            issuerBootId = "boot-1",
            runtimeEpoch = runtimeEpoch,
            revocationEpoch = revocationEpoch,
            sourceSessionEpoch = null,
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            canonicalInputDigest = digest,
            oneShotNonce = "nonce-1",
        )

    /**
     * Binding dimensions that must not diverge under the same CommitId
     * (runtime-recovery-fixtures RR-007 + ADR-004/005).
     */
    private data class CommitBinding(
        val commitId: CommitId,
        val principalId: PrincipalId,
        val reservationId: ReservationId,
        val revisionLeaseId: RevisionLeaseId,
        val runtimeEpoch: Long,
        val revocationEpoch: Long,
        val canonicalInputDigest: Sha256Digest,
        val oneShotNonce: String,
        val issuerBootId: String,
    ) {
        companion object {
            fun of(c: Commit): CommitBinding =
                CommitBinding(
                    commitId = c.commitId,
                    principalId = c.principalId,
                    reservationId = c.reservationId,
                    revisionLeaseId = c.revisionLeaseId,
                    runtimeEpoch = c.runtimeEpoch,
                    revocationEpoch = c.revocationEpoch,
                    canonicalInputDigest = c.canonicalInputDigest,
                    oneShotNonce = c.oneShotNonce,
                    issuerBootId = c.issuerBootId,
                )
        }
    }

    private fun reconcileSameCommitId(
        durable: Commit,
        replay: Commit,
    ): OmniErrorCode? {
        require(durable.commitId == replay.commitId) {
            "test harness requires same CommitId"
        }
        return if (CommitBinding.of(durable) == CommitBinding.of(replay)) {
            null
        } else {
            OmniErrorCode.IDEMPOTENCY_CONFLICT
        }
    }

    @Test
    fun rr007_identicalBinding_reconcilesClean() {
        val a = baseCommit()
        val b = baseCommit()
        assertEquals(null, reconcileSameCommitId(a, b))
    }

    @Test
    fun rr007_changedDigest_isIdempotencyConflict() {
        val durable = baseCommit(digest = digestA)
        val replay = baseCommit(digest = digestB)
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun rr007_changedReservation_isIdempotencyConflict() {
        val durable = baseCommit(reservation = "rsv-1")
        val replay = baseCommit(reservation = "rsv-2")
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun rr007_changedRevisionLease_isIdempotencyConflict() {
        val durable = baseCommit(lease = "lease-1")
        val replay = baseCommit(lease = "lease-2")
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun rr007_changedPrincipal_isIdempotencyConflict() {
        val durable = baseCommit(principal = "principal-1")
        val replay = baseCommit(principal = "principal-2")
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun rr007_changedRevocationEpoch_isIdempotencyConflict() {
        val durable = baseCommit(revocationEpoch = 0L)
        val replay = baseCommit(revocationEpoch = 1L)
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun rr007_changedRuntimeEpoch_isIdempotencyConflict() {
        val durable = baseCommit(runtimeEpoch = 1L)
        val replay = baseCommit(runtimeEpoch = 2L)
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, reconcileSameCommitId(durable, replay))
    }

    @Test
    fun preparedOperation_bindsToSameCommitIds() {
        val commit = baseCommit()
        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse("prep-1"),
            operationId = "op-1",
            requestId = commit.requestId,
            commitId = commit.commitId,
            principalId = commit.principalId,
            reservationId = commit.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            sourceSessionEpoch = null,
            targetSessionId = null,
            canonicalInputDigest = commit.canonicalInputDigest,
        )
        assertEquals(commit.commitId, prepared.commitId)
        assertEquals(commit.reservationId, prepared.reservationId)
        assertEquals(commit.canonicalInputDigest, prepared.canonicalInputDigest)
    }

    @Test
    fun planIsPure_noAllocationOwnership() {
        val plan = Plan(
            planId = PlanId.parse("plan-1"),
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse("principal-1"),
            modelRevisionId = revision,
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("dev-fp-1"),
            canonicalInputDigest = digestA,
            resourceEnvelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 10L),
                peak = ResourceVector(cpuAnonBytes = 20L),
            ),
            expiryMonotonic = 99L,
            runtimeEpoch = 1L,
        )
        // ADR-002: Plan has no reservationId / allocationHandleId.
        assertTrue(plan.resourceEnvelope.peak.dominates(plan.resourceEnvelope.steady))
        assertFalse(plan.toString().contains("allocationHandleId"))
    }
}
