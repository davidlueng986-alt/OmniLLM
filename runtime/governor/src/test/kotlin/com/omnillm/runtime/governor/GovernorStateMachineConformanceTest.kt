package com.omnillm.runtime.governor

import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARC-03 conformance: the local label objects must match the generated
 * RESERVATION / ALLOCATION machines (specs/state-machines.yaml) exactly —
 * any drift fails closed here before it can corrupt governor transitions.
 */
class GovernorStateMachineConformanceTest {

    @Test
    fun reservationLabels_matchGeneratedMachine() {
        val machine = StateMachines.RESERVATION
        assertEquals("RESERVATION", machine.id)
        assertEquals(ReservationFsm.HELD, machine.initial)
        assertEquals(ReservationFsm.TERMINAL, machine.terminal)
        assertEquals(ReservationFsm.states(), machine.states)
    }

    @Test
    fun allocationLabels_matchGeneratedMachine() {
        val machine = StateMachines.ALLOCATION
        assertEquals("ALLOCATION", machine.id)
        assertEquals(AllocationFsm.ACTIVE, machine.initial)
        assertEquals(AllocationFsm.TERMINAL, machine.terminal)
        assertEquals(AllocationFsm.states(), machine.states)
    }

    @Test
    fun reservationEvents_coverAllGeneratedEdges() {
        val generatedEvents = StateMachines.RESERVATION.transitions.map { it.event }.toSet()
        assertEquals(ReservationFsm.events(), generatedEvents)
        assertTrue(
            "COMMIT / CONVERSION_DURABLE must exist in catalog",
            generatedEvents.containsAll(
                setOf(
                    ReservationFsm.EVENT_COMMIT,
                    ReservationFsm.EVENT_CONVERSION_DURABLE,
                ),
            ),
        )
    }

    @Test
    fun allocationEvents_coverAllGeneratedEdges() {
        val generatedEvents = StateMachines.ALLOCATION.transitions.map { it.event }.toSet()
        assertEquals(AllocationFsm.events(), generatedEvents)
    }
}
