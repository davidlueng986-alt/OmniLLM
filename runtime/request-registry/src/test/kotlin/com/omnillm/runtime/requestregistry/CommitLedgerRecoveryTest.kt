package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.ReservationId
import com.omnillm.data.persistence.CommitResourceBindingRow
import com.omnillm.data.persistence.InMemoryCommitLedgerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REL-RECOVERY / runtime-recovery-fixtures RR-001..RR-007 projected onto [CommitLedger].
 */
class CommitLedgerRecoveryTest {

    private val clock = { "2026-08-04T12:00:00Z" }
    private val digestA = Sha256Digest.parse("a".repeat(64))
    private val digestB = Sha256Digest.parse("b".repeat(64))

    private fun newLedger(): CommitLedger =
        CommitLedger(InMemoryCommitLedgerStore(), clock)

    private fun baseCommit(
        commitId: String = "22222222-2222-2222-2222-222222222222",
        digest: Sha256Digest = digestA,
        reservation: String = "rsv-1",
        nonce: String = "nonce-1",
        revocationEpoch: Long = 0L,
    ): Commit =
        Commit(
            commitId = CommitId.parse(commitId),
            planId = PlanId.parse("plan-1"),
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse("principal-1"),
            reservationId = ReservationId.parse(reservation),
            revisionLeaseId = RevisionLeaseId.parse("lease-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = revocationEpoch,
            sourceSessionEpoch = null,
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            canonicalInputDigest = digest,
            oneShotNonce = nonce,
        )

    @Test
    fun intentRecorded_beforeWorker_isQueryable() {
        val ledger = newLedger()
        val commit = baseCommit()
        val first = ledger.recordIntent(commit)
        assertTrue(first is ClaimOutcome.New)
        assertEquals("INTENT_RECORDED", first.getOrNull()!!.state)

        val again = ledger.recordIntent(commit)
        assertTrue(again is ClaimOutcome.Existing)
        assertEquals("INTENT_RECORDED", ledger.queryCommit(commit.commitId)!!.state)
    }

    @Test
    fun rr001_workerDiesBeforeSideEffect_aborts() {
        val ledger = newLedger()
        val commit = baseCommit()
        ledger.recordIntent(commit)
        ledger.markExecuting(commit.commitId)
        ledger.markReconciling(commit.commitId)
        val outcome = ledger.recordOutcome(
            commitId = commit.commitId,
            state = "ABORTED",
            reconciliationDisposition = "ROLLED_BACK",
        )
        assertTrue(outcome is OmniResult.Ok)
        assertEquals("ABORTED", (outcome as OmniResult.Ok).value.state)
        assertTrue(ledger.listOpenCommits().isEmpty())
    }

    @Test
    fun rr002_unprovable_quarantines() {
        val ledger = newLedger()
        val commit = baseCommit()
        ledger.recordIntent(commit)
        ledger.markExecuting(commit.commitId)
        ledger.markReconciling(commit.commitId)
        val outcome = ledger.recordOutcome(
            commitId = commit.commitId,
            state = "UNCERTAIN_QUARANTINED",
            reconciliationDisposition = "UNPROVABLE",
        )
        assertTrue(outcome is OmniResult.Ok)
        assertEquals("UNCERTAIN_QUARANTINED", (outcome as OmniResult.Ok).value.state)
        // Terminal: no overwrite.
        val second = ledger.recordOutcome(commit.commitId, "COMMITTED")
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (second as OmniResult.Err).error.code)
    }

    @Test
    fun rr003_restart_listsOpenCommits_noBlindReplay() {
        val store = InMemoryCommitLedgerStore()
        val ledger = CommitLedger(store, clock)
        val commit = baseCommit()
        ledger.recordIntent(commit)
        ledger.markExecuting(commit.commitId)

        // "Restart": new facade on same store.
        val after = CommitLedger(store, clock)
        val open = after.listOpenCommits()
        assertEquals(1, open.size)
        assertEquals("EXECUTING", open[0].state)
        // Re-intent identical binding returns Existing (not a new EXECUTE).
        assertTrue(after.recordIntent(commit) is ClaimOutcome.Existing)
    }

    @Test
    fun rr005_secondStartClaim_stateConflict() {
        val ledger = newLedger()
        val commit = baseCommit()
        ledger.recordIntent(commit)
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
        assertTrue(ledger.recordPrepared(prepared) is ClaimOutcome.New)
        val first = ledger.claimStart(prepared.preparedOperationId.value)
        assertTrue(first is OmniResult.Ok)
        assertEquals("STARTING", (first as OmniResult.Ok).value.state)
        val second = ledger.claimStart(prepared.preparedOperationId.value)
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (second as OmniResult.Err).error.code)
    }

    @Test
    fun rr006_resourceBinding_convergesDisposition() {
        val ledger = newLedger()
        val commit = baseCommit()
        ledger.recordIntent(commit)
        ledger.upsertResourceBinding(
            CommitResourceBindingRow(
                commitId = commit.commitId.value,
                allocationId = "alloc-1",
                bindingRole = "TARGET",
                resourceVectorDigest = "c".repeat(64),
                disposition = "PREPARED",
                updatedAt = clock(),
            ),
        )
        val rows = ledger.listBindings(commit.commitId)
        assertEquals(1, rows.size)
        assertEquals("PREPARED", rows[0].disposition)
        ledger.upsertResourceBinding(
            rows[0].copy(disposition = "TRANSFERRED", updatedAt = clock()),
        )
        assertEquals("TRANSFERRED", ledger.listBindings(commit.commitId).single().disposition)
    }

    @Test
    fun rr007_changedBinding_sameCommitId_idempotencyConflict() {
        val ledger = newLedger()
        val a = baseCommit(digest = digestA, reservation = "rsv-1")
        assertTrue(ledger.recordIntent(a) is ClaimOutcome.New)
        val b = baseCommit(digest = digestB, reservation = "rsv-2")
        val conflict = ledger.recordIntent(b)
        assertTrue(conflict is ClaimOutcome.Conflict)
        assertEquals(
            OmniErrorCode.IDEMPOTENCY_CONFLICT,
            (conflict as ClaimOutcome.Conflict).error.code,
        )
        // Durable original preserved.
        assertEquals(digestA.hex, ledger.queryCommit(a.commitId)!!.canonicalInputDigest)
    }

    @Test
    fun unknownCommit_queryReturnsNull_failClosed() {
        val ledger = newLedger()
        assertEquals(
            null,
            ledger.queryCommit(CommitId.parse("99999999-9999-9999-9999-999999999999")),
        )
        assertNotNull(ledger.listOpenCommits())
    }
}
