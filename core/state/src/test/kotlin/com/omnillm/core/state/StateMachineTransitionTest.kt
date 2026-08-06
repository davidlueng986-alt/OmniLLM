package com.omnillm.core.state

import com.omnillm.core.state.generated.FsmStepResult
import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural FSM tests: legal catalog transitions succeed; illegal ones fail closed.
 */
class StateMachineTransitionTest {

    @Test
    fun request_legalClaimFromReceived() {
        val result = StateMachines.REQUEST.step("RECEIVED", "CLAIM_SUCCEEDED")
        assertTrue(result is FsmStepResult.Taken)
        val taken = result as FsmStepResult.Taken
        assertEquals("CLAIMED", taken.transition.to)
        assertEquals("REQ-001", taken.transition.id)
    }

    @Test
    fun request_illegalTransitionRejected() {
        val result = StateMachines.REQUEST.step("RECEIVED", "ENGINE_TERMINAL")
        assertTrue(result is FsmStepResult.Illegal)
        val illegal = result as FsmStepResult.Illegal
        assertEquals("REQUEST", illegal.machineId)
        assertEquals("RECEIVED", illegal.from)
        assertEquals("ENGINE_TERMINAL", illegal.event)
    }

    @Test
    fun request_terminalHasNoOutbound() {
        val result = StateMachines.REQUEST.step("COMPLETED", "CLAIM_SUCCEEDED")
        assertTrue(result is FsmStepResult.Illegal)
        assertTrue((result as FsmStepResult.Illegal).reason.contains("terminal"))
    }

    @Test
    fun session_legalPublish() {
        val result = StateMachines.SESSION.step("NEW", "PUBLISH")
        assertTrue(result is FsmStepResult.Taken)
        assertEquals("ACTIVE", (result as FsmStepResult.Taken).transition.to)
    }

    @Test
    fun session_illegalFromPoisonedToActive() {
        val result = StateMachines.SESSION.step("POISONED", "PUBLISH")
        assertTrue(result is FsmStepResult.Illegal)
    }

    @Test
    fun reservation_legalCommit() {
        val result = StateMachines.RESERVATION.step("HELD", "COMMIT")
        assertTrue(result is FsmStepResult.Taken)
        assertEquals("CONVERTING", (result as FsmStepResult.Taken).transition.to)
    }

    @Test
    fun job_illegalSkipToSucceeded() {
        val result = StateMachines.JOB.step("QUEUED", "SUCCESS")
        assertTrue(result is FsmStepResult.Illegal)
    }

    @Test
    fun commit_legalExecute() {
        val result = StateMachines.COMMIT.step("INTENT_RECORDED", "EXECUTE")
        assertTrue(result is FsmStepResult.Taken)
        assertEquals("EXECUTING", (result as FsmStepResult.Taken).transition.to)
    }

    @Test
    fun token_drainCompleteIsAmbiguousWithoutGuards() {
        // TOKEN-006 and TOKEN-007 share (DRAINING, DRAIN_COMPLETE).
        val result = StateMachines.TOKEN.step("DRAINING", "DRAIN_COMPLETE")
        assertTrue(result is FsmStepResult.Ambiguous)
        val ambiguous = result as FsmStepResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        val targets = ambiguous.candidates.map { it.to }.toSet()
        assertEquals(setOf("REVOKED", "EXPIRED"), targets)
    }

    @Test
    fun lan_disableExpandsMultiFrom() {
        // LAN-006: from is a list of states.
        for (from in listOf("STARTING", "ADVERTISING", "ACTIVE", "ROTATING", "ERROR")) {
            val result = StateMachines.LAN_SERVICE.step(from, "DISABLE")
            assertTrue("expected Taken from $from", result is FsmStepResult.Taken)
            assertEquals("DRAINING", (result as FsmStepResult.Taken).transition.to)
        }
    }

    @Test
    fun allMachinesHaveInitialInStates() {
        for (m in StateMachines.ALL) {
            assertTrue(
                "machine ${m.id} initial ${m.initial} not in states",
                m.isKnownState(m.initial),
            )
            for (t in m.terminal) {
                assertTrue(
                    "machine ${m.id} terminal $t not in states",
                    m.isKnownState(t),
                )
            }
            for (tr in m.transitions) {
                assertTrue(m.isKnownState(tr.from))
                assertTrue(m.isKnownState(tr.to))
            }
        }
    }

    @Test
    fun unknownMachineFailsClosed() {
        var threw = false
        try {
            StateMachines.require("NOT_A_MACHINE")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
