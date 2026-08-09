package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.RequestRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TST-03: REQUEST FSM production driver ([RequestLifecycle]) test suite.
 *
 * Mirrors the CommitLedgerRecoveryTest pattern and covers the REQUEST machine
 * transition table from specs/state-machines.yaml (REQ-001..REQ-034), including
 * REQ-018~022 reconciliation semantics, cancel intent, reply-loss convergence
 * and COR-19 terminal-aggregate eviction.
 *
 * Each scenario claims a durable registry row first (production submit() does
 * registry.claim then bindFromClaim), then drives the driver.
 */
class RequestLifecycleDriverTest {

    private val clock = { "2026-08-08T12:00:00Z" }

    private fun newSystem(): Pair<RequestLifecycle, RequestRegistry> {
        val (registry, _, _) = RequestRegistryModule.createInMemory(clock)
        return RequestLifecycle(registry) to registry
    }

    private fun requestId(seed: Int = 1): RequestId =
        RequestId.parse("00000000-0000-0000-0000-%012d".format(seed))

    private fun guards(vararg facts: Pair<String, Boolean>): GuardEvaluator =
        GuardEvaluator.of(*facts)

    private fun ok(value: OmniResult<*>): Any? {
        assertTrue("expected Ok, got ${(value as? OmniResult.Err)?.error}", value is OmniResult.Ok)
        return (value as OmniResult.Ok).value
    }

    private fun errCode(value: OmniResult<*>): OmniErrorCode {
        assertTrue("expected Err, got $value", value is OmniResult.Err)
        return (value as OmniResult.Err).error.code
    }

    /** Claim a durable row (RECEIVED) and bind the aggregate — mirrors submit(). */
    private fun claimAndBind(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        val outcome = registry.claim(
            principal = PrincipalId.parse("principal-a"),
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-${id.value}"),
            canonicalHash = Sha256Digest.parse("a".repeat(64)),
            requestId = id,
        )
        assertTrue(outcome !is ClaimOutcome.Conflict)
        val row = outcome.getOrNull()!!
        lc.bindFromClaim(id, "principal-a", state = row.state)
    }

    // ------------------------------------------------------------------
    // claim → plan → commit happy path (REQ-001/004/007/010/013/016/023/026/029/032)
    // ------------------------------------------------------------------

    @Test
    fun happyPath_receivedThroughCompleted_persistsEveryState() {
        val (lc, registry) = newSystem()
        val id = requestId(1)
        claimAndBind(lc, registry, id)

        ok(lc.apply(id, "CLAIM_SUCCEEDED", guards("claimMatchesCanonicalHash" to true)))
        ok(lc.apply(id, "BEGIN_PLANNING"))
        ok(lc.apply(id, "PLAN_READY"))
        assertEquals("QUEUED", lc.currentState(id))

        ok(lc.apply(id, "ADMISSION_GRANTED", guards("leaseValid" to true)))
        ok(lc.apply(id, "BEGIN_COMMIT", guards("leaseValid" to true)))
        ok(lc.apply(id, "COMMIT_CONFIRMED", guards("commitKnown" to true)))
        ok(lc.apply(id, "START_ACCEPTED", guards("streamMayStart" to true)))
        ok(lc.apply(id, "FIRST_EVENT_COMMITTED"))
        assertEquals("STREAMING", lc.currentState(id))

        ok(lc.apply(id, "ENGINE_TERMINAL"))
        val terminal = lc.terminal(id, "TERMINAL_SUCCESS", terminalSeq = 1L)
        assertEquals("COMPLETED", terminal.getOrNull()!!.state)
        // Durable state + exactly one durable terminal.
        assertEquals("COMPLETED", lc.currentState(id))
        assertEquals("COMPLETED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun terminalFailure_reachesFailed_withErrorCode() {
        val (lc, registry) = newSystem()
        val id = requestId(2)
        reachTerminating(lc, registry, id)
        val terminal = lc.terminal(id, "TERMINAL_FAILURE", terminalSeq = 1L, errorCode = "INTERNAL")
        assertEquals("FAILED", terminal.getOrNull()!!.state)
        assertEquals("INTERNAL", registry.queryRequestTerminal(id)!!.errorCode)
    }

    // ------------------------------------------------------------------
    // REQ-018..022 reconciliation semantics
    // ------------------------------------------------------------------

    @Test
    fun req018_commitResultUnknown_movesToReconciling_nonTerminal() {
        val (lc, registry) = newSystem()
        val id = requestId(3)
        reachCommitting(lc, registry, id)

        val out = lc.apply(id, "COMMIT_RESULT_UNKNOWN")
        assertTrue(out is OmniResult.Ok)
        assertEquals("RECONCILING", out.getOrNull()!!.state)
        assertEquals("RECONCILING", lc.currentState(id))
        assertNull(registry.queryRequestTerminal(id))
    }

    @Test
    fun req020_foundPrepared_resumesPrepared() {
        val (lc, registry) = newSystem()
        val id = requestId(4)
        reachReconciling(lc, registry, id)
        val out = lc.apply(
            id,
            "COMMIT_FOUND_PREPARED",
            guards("commitKnown" to true, "cancelPending" to false),
        )
        assertTrue(out is OmniResult.Ok)
        assertEquals("PREPARED", out.getOrNull()!!.state)
    }

    @Test
    fun req020c_foundPreparedWithCancel_terminatesCancelled() {
        val (lc, registry) = newSystem()
        val id = requestId(5)
        reachReconciling(lc, registry, id)
        val out = lc.apply(
            id,
            "COMMIT_FOUND_PREPARED_WITH_CANCEL",
            guards("commitKnown" to true, "cancelPending" to true),
        )
        assertTrue(out is OmniResult.Ok)
        assertEquals("TERMINATING", out.getOrNull()!!.state)
        val terminal = lc.terminal(id, "TERMINAL_CANCELLED", terminalSeq = 1L)
        assertEquals("CANCELLED", terminal.getOrNull()!!.state)
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun req021_foundAborted_reachesFailed() {
        val (lc, registry) = newSystem()
        val id = requestId(6)
        reachReconciling(lc, registry, id)
        val terminal = lc.terminal(
            id,
            "COMMIT_FOUND_ABORTED",
            terminalSeq = 1L,
            errorCode = "INTERNAL",
            guards = guards("commitKnown" to true, "cancelPending" to false),
        )
        assertTrue(terminal is OmniResult.Ok)
        assertEquals("FAILED", terminal.getOrNull()!!.state)
        assertEquals("FAILED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun req021c_foundAbortedWithCancel_reachesCancelled() {
        val (lc, registry) = newSystem()
        val id = requestId(7)
        reachReconciling(lc, registry, id)
        val terminal = lc.terminal(
            id,
            "COMMIT_FOUND_ABORTED_WITH_CANCEL",
            terminalSeq = 1L,
            guards = guards("commitKnown" to true, "cancelPending" to true),
        )
        assertTrue(terminal is OmniResult.Ok)
        assertEquals("CANCELLED", terminal.getOrNull()!!.state)
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun req022_reconciliationExhausted_poisonToAbortedUncertain() {
        val (lc, registry) = newSystem()
        val id = requestId(8)
        reachReconciling(lc, registry, id)
        val terminal = lc.terminal(
            id,
            "RECONCILIATION_EXHAUSTED",
            terminalSeq = 1L,
            errorCode = OmniError.ABORTED_UNCERTAIN().code.code,
        )
        assertTrue(terminal is OmniResult.Ok)
        assertEquals("ABORTED_UNCERTAIN", terminal.getOrNull()!!.state)
        assertEquals("ABORTED_UNCERTAIN", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun guardFailure_foundPreparedWithoutCommitKnown_rejected() {
        val (lc, registry) = newSystem()
        val id = requestId(9)
        reachReconciling(lc, registry, id)
        val out = lc.apply(
            id,
            "COMMIT_FOUND_PREPARED",
            guards("commitKnown" to false, "cancelPending" to false),
        )
        assertEquals(OmniErrorCode.STATE_CONFLICT, errCode(out))
    }

    // ------------------------------------------------------------------
    // cancel intent (REQ-003/006/009/012/015/019/025/028/030)
    // ------------------------------------------------------------------

    @Test
    fun cancelFromQueued_cancelled() {
        val (lc, registry) = newSystem()
        val id = requestId(10)
        reachQueued(lc, registry, id)
        val terminal = lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        assertEquals("CANCELLED", terminal.getOrNull()!!.state)
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun cancelFromCommitting_reconcilesWithCancelIntent() {
        val (lc, registry) = newSystem()
        val id = requestId(11)
        reachCommitting(lc, registry, id)
        // REQ-019: CLIENT_CANCEL from COMMITTING records intent + reconciles.
        val out = lc.apply(id, "CLIENT_CANCEL")
        assertTrue(out is OmniResult.Ok)
        assertEquals("RECONCILING", out.getOrNull()!!.state)
        // Resolution with cancel intent converges on CANCELLED (REQ-020C/021C).
        val terminal = lc.terminal(
            id,
            "COMMIT_FOUND_ABORTED_WITH_CANCEL",
            terminalSeq = 1L,
            guards = guards("commitKnown" to true, "cancelPending" to true),
        )
        assertEquals("CANCELLED", terminal.getOrNull()!!.state)
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    @Test
    fun cancelFromPrepared_poisonOrRollbackToCancelled() {
        val (lc, registry) = newSystem()
        val id = requestId(12)
        reachPrepared(lc, registry, id)
        // REQ-025: PREPARED + CLIENT_CANCEL -> CANCELLED (poisonOrRollback action).
        val terminal = lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        assertEquals("CANCELLED", terminal.getOrNull()!!.state)
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    // ------------------------------------------------------------------
    // reply-loss convergence (exactly-one-terminal invariant)
    // ------------------------------------------------------------------

    @Test
    fun replyLoss_identicalTerminalReRecord_durableTerminalUnchanged() {
        val (lc, registry) = newSystem()
        val id = requestId(13)
        reachQueued(lc, registry, id)
        val first = lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        assertTrue(first is OmniResult.Ok)
        // COR-19 eviction: the driver aggregate is gone after the terminal; a
        // retry must be answered from the durable registry (query, never replay).
        val second = lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        assertEquals(OmniErrorCode.NOT_FOUND, errCode(second))
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
        assertEquals("CANCELLED", registry.queryRequest(id)!!.state)
    }

    @Test
    fun replyLoss_differentTerminalPayload_durableTerminalUnchanged() {
        val (lc, registry) = newSystem()
        val id = requestId(14)
        reachQueued(lc, registry, id)
        lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        // Aggregate evicted (COR-19); the conflicting re-terminal cannot reach
        // the FSM. Exactly-one-terminal is enforced durably by the registry
        // (STATE_CONFLICT on conflicting payload is covered in RequestRegistryTest).
        val second = lc.terminal(id, "TERMINAL_SUCCESS", terminalSeq = 2L)
        assertEquals(OmniErrorCode.NOT_FOUND, errCode(second))
        assertEquals("CANCELLED", registry.queryRequestTerminal(id)!!.terminalState)
    }

    // ------------------------------------------------------------------
    // fail-closed edges (INV-018)
    // ------------------------------------------------------------------

    @Test
    fun terminalStateViaApply_rejected() {
        val (lc, registry) = newSystem()
        val id = requestId(15)
        claimAndBind(lc, registry, id)
        // REQ-002 VALIDATION_FAILED -> FAILED is terminal; apply() must refuse.
        assertEquals(OmniErrorCode.INVALID_REQUEST, errCode(lc.apply(id, "VALIDATION_FAILED")))
    }

    @Test
    fun terminalEventThatDoesNotReachTerminal_rejected() {
        val (lc, registry) = newSystem()
        val id = requestId(16)
        reachQueued(lc, registry, id)
        val out = lc.terminal(
            id,
            "ADMISSION_GRANTED",
            terminalSeq = 1L,
            guards = guards("leaseValid" to true),
        )
        assertEquals(OmniErrorCode.STATE_CONFLICT, errCode(out))
    }

    @Test
    fun illegalEdge_rejected() {
        val (lc, registry) = newSystem()
        val id = requestId(17)
        claimAndBind(lc, registry, id)
        assertEquals(OmniErrorCode.STATE_CONFLICT, errCode(lc.apply(id, "BEGIN_COMMIT")))
    }

    @Test
    fun unboundRequest_applyAndTerminal_notFound() {
        val (lc, _) = newSystem()
        val id = requestId(18)
        assertEquals(OmniErrorCode.NOT_FOUND, errCode(lc.apply(id, "BEGIN_PLANNING")))
        assertEquals(OmniErrorCode.NOT_FOUND, errCode(lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)))
    }

    @Test
    fun terminalState_hasNoOutboundTransitions() {
        val (lc, _) = newSystem()
        val id = requestId(19)
        // Bind directly in a terminal state: the FSM rejects any outbound edge.
        lc.bindFromClaim(id, "principal-a", state = "COMPLETED")
        assertEquals(OmniErrorCode.STATE_CONFLICT, errCode(lc.apply(id, "ENGINE_TERMINAL")))
        assertEquals(OmniErrorCode.STATE_CONFLICT, errCode(lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)))
    }

    // ------------------------------------------------------------------
    // structural probes + COR-19 aggregate eviction
    // ------------------------------------------------------------------

    @Test
    fun canTransition_probesMatchCatalog() {
        val (lc, _) = newSystem()
        // Guard 'true' edges (ALWAYS_TRUE evaluator).
        assertTrue(lc.canTransition("COMMITTING", "COMMIT_RESULT_UNKNOWN"))
        assertTrue(lc.canTransition("RECONCILING", "RECONCILIATION_EXHAUSTED"))
        assertTrue(lc.canTransition("STREAMING", "ENGINE_TERMINAL"))
        assertTrue(lc.canTransition("RECEIVED", "CLIENT_CANCEL"))
        // Guard `commitKnown && cancelPending` is satisfied by ALWAYS_TRUE.
        assertTrue(lc.canTransition("RECONCILING", "COMMIT_FOUND_PREPARED_WITH_CANCEL"))
        assertFalse(lc.canTransition("RECONCILING", "COMMIT_FOUND_PREPARED"))
        assertFalse(lc.canTransition("RECONCILING", "COMMIT_FOUND_ABORTED"))
        assertFalse(lc.canTransition("QUEUED", "BEGIN_COMMIT"))
        assertFalse(lc.canTransition("COMPLETED", "ENGINE_TERMINAL"))
        assertFalse(lc.canTransition("UNKNOWN_STATE", "CLIENT_CANCEL"))
    }

    @Test
    fun terminalEvictsAggregate_cor19() {
        val (lc, registry) = newSystem()
        val id = requestId(20)
        claimAndBind(lc, registry, id)
        assertNotNull(lc.getAggregate(id))
        reachQueued(lc, registry, id)
        lc.terminal(id, "CLIENT_CANCEL", terminalSeq = 1L)
        // COR-19: terminal aggregates are evicted (durable in registry).
        assertNull(lc.getAggregate(id))
        // currentState still answers from the durable registry.
        assertEquals("CANCELLED", lc.currentState(id))
        assertEquals("CANCELLED", registry.queryRequest(id)!!.state)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun reachQueued(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        claimAndBind(lc, registry, id)
        ok(lc.apply(id, "CLAIM_SUCCEEDED", guards("claimMatchesCanonicalHash" to true)))
        ok(lc.apply(id, "BEGIN_PLANNING"))
        ok(lc.apply(id, "PLAN_READY"))
    }

    private fun reachCommitting(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        reachQueued(lc, registry, id)
        ok(lc.apply(id, "ADMISSION_GRANTED", guards("leaseValid" to true)))
        ok(lc.apply(id, "BEGIN_COMMIT", guards("leaseValid" to true)))
    }

    private fun reachReconciling(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        reachCommitting(lc, registry, id)
        ok(lc.apply(id, "COMMIT_RESULT_UNKNOWN"))
    }

    private fun reachPrepared(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        reachCommitting(lc, registry, id)
        ok(lc.apply(id, "COMMIT_CONFIRMED", guards("commitKnown" to true)))
    }

    private fun reachTerminating(lc: RequestLifecycle, registry: RequestRegistry, id: RequestId) {
        reachPrepared(lc, registry, id)
        ok(lc.apply(id, "START_ACCEPTED", guards("streamMayStart" to true)))
        ok(lc.apply(id, "FIRST_EVENT_COMMITTED"))
        ok(lc.apply(id, "ENGINE_TERMINAL"))
    }
}
