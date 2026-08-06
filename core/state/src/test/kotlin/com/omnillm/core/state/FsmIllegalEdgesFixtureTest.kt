package com.omnillm.core.state

import com.omnillm.core.state.generated.FsmStepResult
import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit illegal-edge catalog for required machines (INV-018 / DATA-STATES).
 * Complements [StateMachinePropertyTest] with human-readable named fixtures.
 */
class FsmIllegalEdgesFixtureTest {

    private fun assertIllegal(machineId: String, from: String, event: String) {
        val m = StateMachines.require(machineId)
        val result = m.step(from, event)
        assertTrue(
            "$machineId: $from+$event must be Illegal, got $result",
            result is FsmStepResult.Illegal,
        )
    }

    private fun assertTaken(machineId: String, from: String, event: String, to: String) {
        val m = StateMachines.require(machineId)
        val result = m.step(from, event)
        assertTrue("$machineId: $from+$event expected Taken, got $result", result is FsmStepResult.Taken)
        assertEquals(to, (result as FsmStepResult.Taken).transition.to)
    }

    @Test
    fun request_illegalSkipsAndTerminals() {
        assertIllegal("REQUEST", "RECEIVED", "ENGINE_TERMINAL")
        assertIllegal("REQUEST", "RECEIVED", "BEGIN_COMMIT")
        assertIllegal("REQUEST", "COMPLETED", "CLAIM_SUCCEEDED")
        assertIllegal("REQUEST", "FAILED", "CLAIM_SUCCEEDED")
        assertIllegal("REQUEST", "CANCELLED", "CLAIM_SUCCEEDED")
        assertTaken("REQUEST", "RECEIVED", "CLAIM_SUCCEEDED", "CLAIMED")
    }

    @Test
    fun commit_illegalSkips() {
        assertIllegal("COMMIT", "INTENT_RECORDED", "PUBLISH_SUCCESS")
        assertIllegal("COMMIT", "INTENT_RECORDED", "RESULT_FOUND_SUCCESS")
        assertIllegal("COMMIT", "COMMITTED", "EXECUTE")
        assertIllegal("COMMIT", "ABORTED", "EXECUTE")
        assertIllegal("COMMIT", "UNCERTAIN_QUARANTINED", "RESULT_UNPROVABLE")
        assertTaken("COMMIT", "INTENT_RECORDED", "EXECUTE", "EXECUTING")
    }

    @Test
    fun reservation_illegalSkips() {
        assertIllegal("RESERVATION", "HELD", "CONVERSION_DURABLE")
        assertIllegal("RESERVATION", "COMMITTED", "COMMIT")
        assertIllegal("RESERVATION", "RELEASED", "COMMIT")
        assertTaken("RESERVATION", "HELD", "COMMIT", "CONVERTING")
    }

    @Test
    fun session_poisonedNeverReturnsToActive() {
        assertIllegal("SESSION", "POISONED", "PUBLISH")
        assertIllegal("SESSION", "CLOSED", "PUBLISH")
        assertTaken("SESSION", "NEW", "PUBLISH", "ACTIVE")
    }

    @Test
    fun job_illegalSkips() {
        assertIllegal("JOB", "QUEUED", "SUCCESS")
        assertIllegal("JOB", "QUEUED", "FAILURE")
        assertIllegal("JOB", "SUCCEEDED", "START")
        assertTaken("JOB", "QUEUED", "START", "RUNNING")
    }

    @Test
    fun operation_illegalSkips() {
        assertIllegal("OPERATION", "PREPARED", "ENGINE_TERMINAL")
        assertIllegal("OPERATION", "COMPLETED", "START_ACCEPTED")
        assertTaken("OPERATION", "PREPARED", "START_ACCEPTED", "STARTING")
    }

    @Test
    fun loadedModel_illegalSkips() {
        assertIllegal("LOADED_MODEL", "PLANNED", "LOAD_SUCCEEDED")
        assertIllegal("LOADED_MODEL", "UNLOADED", "ADMISSION_GRANTED")
        assertTaken("LOADED_MODEL", "PLANNED", "ADMISSION_GRANTED", "RESERVED")
    }

    @Test
    fun everyRequiredMachine_rejectsUnknownEventFromInitial() {
        for (id in StateMachineDriver.REQUIRED_MACHINE_IDS) {
            val m = StateMachines.require(id)
            assertIllegal(id, m.initial, "__NOT_A_CATALOG_EVENT__")
        }
    }

    @Test
    fun everyRequiredMachine_rejectsOutboundFromEachTerminal() {
        for (id in StateMachineDriver.REQUIRED_MACHINE_IDS) {
            val m = StateMachines.require(id)
            for (terminal in m.terminal) {
                for (event in StateMachineDriver.catalogEvents(m) + "__X__") {
                    val outcome = StateMachineDriver.transition(m, terminal, event)
                    assertTrue(
                        "$id terminal $terminal must reject $event, got $outcome",
                        outcome is TransitionOutcome.Rejected.TerminalHasNoOutbound,
                    )
                }
            }
        }
    }
}
