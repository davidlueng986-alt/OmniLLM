package com.omnillm.core.state

import com.omnillm.core.state.domain.CommitAggregate
import com.omnillm.core.state.domain.CommitId
import com.omnillm.core.state.domain.OperationAggregate
import com.omnillm.core.state.domain.OperationId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract fixtures from `specs/runtime-recovery-fixtures.yaml` (RR-001..RR-007)
 * projected onto the pure COMMIT / OPERATION FSMs.
 *
 * Authority: REL-RECOVERY, ADR-004/005, DATA-STATES (COMMIT machine).
 * Full durable SQL reconcile remains a control-plane concern; this suite
 * proves illegal edges fail closed and recovery edges converge to one terminal.
 */
class CommitReconcileFixtureTest {

    private val requestId = RequestId("req-rr-1")
    private val commitId = CommitId("commit-rr-1")

    private fun authoritative(): GuardEvaluator =
        GuardEvaluator.of(
            "bindingValid" to true,
            "oneShot" to true,
            "resultAuthoritative" to true,
        )

    private fun <A> mustSucceed(result: AggregateTransitionResult<A>): A {
        assertTrue("expected success, got $result", result is AggregateTransitionResult.Success)
        return (result as AggregateTransitionResult.Success).aggregate
    }

    private fun mustReject(result: AggregateTransitionResult<*>) {
        assertTrue("expected rejection, got $result", result is AggregateTransitionResult.Rejected)
    }

    /** Happy path to EXECUTING (INTENT_RECORDED is durable before worker side effect). */
    private fun executing(): CommitAggregate {
        var c = CommitAggregate.initial(commitId, requestId)
        assertEquals("INTENT_RECORDED", c.state)
        c = mustSucceed(c.apply("EXECUTE", authoritative()))
        assertEquals("EXECUTING", c.state)
        return c
    }

    // ------------------------------------------------------------------
    // RR-001 — worker dies before commit side effect → ABORTED / ROLLED_BACK
    // ------------------------------------------------------------------

    @Test
    fun rr001_workerDiesBeforeSideEffect_reconcilesToAborted() {
        var c = executing()
        // Worker lost with no proof of side effect → RECONCILING.
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        assertEquals("RECONCILING", c.state)
        // Journal shows no effect → RESULT_FOUND_ABORT.
        c = mustSucceed(c.apply("RESULT_FOUND_ABORT", authoritative()))
        assertEquals("ABORTED", c.state)
        assertTrue(c.isTerminal())
        // Terminal has no outbound (fail closed).
        mustReject(c.apply("EXECUTE", authoritative()))
    }

    // ------------------------------------------------------------------
    // RR-002 — worker dies after side effect before reply → RECONCILING
    //          terminal is COMMITTED or UNCERTAIN_QUARANTINED
    // ------------------------------------------------------------------

    @Test
    fun rr002_workerDiesAfterSideEffect_reconcilesToCommittedWhenJournalProvesSuccess() {
        var c = executing()
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        assertEquals("RECONCILING", c.state)
        c = mustSucceed(c.apply("RESULT_FOUND_SUCCESS", authoritative()))
        assertEquals("COMMITTED", c.state)
        assertTrue(c.isTerminal())
    }

    @Test
    fun rr002_workerDiesAfterSideEffect_unprovable_quarantines() {
        var c = executing()
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        c = mustSucceed(c.apply("RESULT_UNPROVABLE"))
        assertEquals("UNCERTAIN_QUARANTINED", c.state)
        assertTrue(c.isTerminal())
        mustReject(c.apply("RESULT_FOUND_SUCCESS", authoritative()))
    }

    // ------------------------------------------------------------------
    // RR-003 — runtime restarts after worker reply before result transaction
    //          → query epoch-bound journal and reconcile existing CommitId
    // ------------------------------------------------------------------

    @Test
    fun rr003_restartReconcilesExistingCommitId_noBlindReplayOfExecute() {
        var c = executing()
        // Crash mid-flight: after restart the durable state is still EXECUTING
        // (or RECONCILING if fence marked loss). Reconcile, never re-EXECUTE.
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        assertEquals("RECONCILING", c.state)
        // Blind EXECUTE from RECONCILING is illegal (catalog edge only REPLY path).
        mustReject(c.apply("EXECUTE", authoritative()))
        // Journal recovers success for the same CommitId.
        c = mustSucceed(c.apply("RESULT_FOUND_SUCCESS", authoritative()))
        assertEquals("COMMITTED", c.state)
    }

    // ------------------------------------------------------------------
    // RR-004 — late reply from prior runtimeEpoch → reject / quarantine
    // ------------------------------------------------------------------

    @Test
    fun rr004_nonAuthoritativeResult_failsGuard_thenUnprovableQuarantines() {
        var c = executing()
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        // Stale epoch reply: resultAuthoritative = false → guard fails.
        mustReject(
            c.apply(
                "RESULT_FOUND_SUCCESS",
                GuardEvaluator.of("resultAuthoritative" to false),
            ),
        )
        // Still RECONCILING; unprovable path quarantines bindings.
        c = mustSucceed(c.apply("RESULT_UNPROVABLE"))
        assertEquals("UNCERTAIN_QUARANTINED", c.state)
    }

    // ------------------------------------------------------------------
    // RR-005 — second start for the same PreparedOperationId → STATE_CONFLICT
    // ------------------------------------------------------------------

    @Test
    fun rr005_secondStartOnPreparedOperation_rejectedAsIllegalEdge() {
        val opId = OperationId("op-rr-5")
        var op = OperationAggregate.initial(opId, requestId)
        assertEquals("PREPARED", op.state)
        op = mustSucceed(
            op.apply(
                "START_ACCEPTED",
                GuardEvaluator.of("claimCurrent" to true),
            ),
        )
        assertEquals("STARTING", op.state)
        // Second start from STARTING is not a catalog edge → fail closed.
        mustReject(
            op.apply(
                "START_ACCEPTED",
                GuardEvaluator.of("claimCurrent" to true),
            ),
        )
        // From PREPARED a second concurrent claim with claimCurrent=false fails guard.
        val prepared = OperationAggregate.initial(OperationId("op-rr-5b"), requestId)
        mustReject(
            prepared.apply(
                "START_ACCEPTED",
                GuardEvaluator.of("claimCurrent" to false),
            ),
        )
    }

    // ------------------------------------------------------------------
    // RR-006 — crash during reservation-to-allocation transfer → RECONCILING
    //          disposition converges to one terminal via journal
    // ------------------------------------------------------------------

    @Test
    fun rr006_transferCrash_reconcilesToExactlyOneTerminal() {
        var c = executing()
        c = mustSucceed(c.apply("REPLY_OR_WORKER_LOST"))
        assertEquals("RECONCILING", c.state)
        // Exactly one of success / abort / unprovable — not multiple.
        c = mustSucceed(c.apply("RESULT_FOUND_ABORT", authoritative()))
        assertEquals("ABORTED", c.state)
        mustReject(c.apply("RESULT_FOUND_SUCCESS", authoritative()))
        mustReject(c.apply("RESULT_UNPROVABLE"))
    }

    // ------------------------------------------------------------------
    // Happy RESULT_RECORDED path (non-crash) + illegal edges
    // ------------------------------------------------------------------

    @Test
    fun resultRecorded_publishSuccess_committed() {
        var c = executing()
        c = mustSucceed(c.apply("RESULT_AVAILABLE", authoritative()))
        assertEquals("RESULT_RECORDED", c.state)
        c = mustSucceed(c.apply("PUBLISH_SUCCESS", authoritative()))
        assertEquals("COMMITTED", c.state)
    }

    @Test
    fun commit_illegalEdgesFailClosed() {
        val intent = CommitAggregate.initial(commitId, requestId)
        // Cannot skip to RESULT_RECORDED / COMMITTED from INTENT_RECORDED.
        mustReject(intent.apply("RESULT_AVAILABLE", authoritative()))
        mustReject(intent.apply("PUBLISH_SUCCESS", authoritative()))
        mustReject(intent.apply("RESULT_FOUND_SUCCESS", authoritative()))

        val step = StateMachines.COMMIT.step("INTENT_RECORDED", "RESULT_UNPROVABLE")
        assertTrue(step is com.omnillm.core.state.generated.FsmStepResult.Illegal)
    }

    @Test
    fun commit_catalogTerminalsMatchRecoveryFixture() {
        val terminals = StateMachines.COMMIT.terminal
        assertEquals(
            setOf("COMMITTED", "ABORTED", "UNCERTAIN_QUARANTINED"),
            terminals,
        )
        assertTrue(StateMachines.COMMIT.isKnownState("RECONCILING"))
        assertTrue(StateMachines.COMMIT.isKnownState("INTENT_RECORDED"))
    }
}
