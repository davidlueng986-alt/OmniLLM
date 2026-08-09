package com.omnillm.runtime.governor

import com.omnillm.core.state.generated.StateMachines

/**
 * Catalog state / event labels for RESERVATION and ALLOCATION FSMs
 * (`specs/state-machines.yaml`). Do not invent additional labels.
 *
 * ARC-03: transitions are executed through the generated [StateMachines];
 * these label objects are the human-readable constants mirrored from the
 * catalog. Conformance is pinned by GovernorStateMachineConformanceTest.
 */
object ReservationFsm {
    const val MACHINE_ID: String = "RESERVATION"

    const val HELD: String = "HELD"
    const val CONVERTING: String = "CONVERTING"
    const val COMMITTED: String = "COMMITTED"
    const val RELEASING: String = "RELEASING"
    const val RELEASED: String = "RELEASED"
    const val EXPIRED: String = "EXPIRED"

    const val EVENT_COMMIT: String = "COMMIT"
    const val EVENT_CONVERSION_DURABLE: String = "CONVERSION_DURABLE"
    const val EVENT_RELEASE: String = "RELEASE"
    const val EVENT_RELEASE_BARRIER: String = "RELEASE_BARRIER"
    const val EVENT_DEADLINE_PASSED: String = "DEADLINE_PASSED"

    val TERMINAL: Set<String> = setOf(COMMITTED, RELEASED, EXPIRED)

    /** All catalog states for the machine (conformance mirror). */
    fun states(): Set<String> = StateMachines.RESERVATION.states

    /** All catalog event labels for the machine (conformance mirror). */
    fun events(): Set<String> = StateMachines.RESERVATION.transitions.map { it.event }.toSet()
}

/**
 * Catalog state / event labels for ALLOCATION FSM (ADR-003 release barrier).
 */
object AllocationFsm {
    const val MACHINE_ID: String = "ALLOCATION"

    const val ACTIVE: String = "ACTIVE"
    const val DRAINING: String = "DRAINING"
    const val RELEASING: String = "RELEASING"
    const val RELEASED: String = "RELEASED"

    const val EVENT_DRAIN_REQUESTED: String = "DRAIN_REQUESTED"
    const val EVENT_OWNER_QUIESCENT: String = "OWNER_QUIESCENT"
    const val EVENT_RELEASE_BARRIER: String = "RELEASE_BARRIER"

    val TERMINAL: Set<String> = setOf(RELEASED)

    /** All catalog states for the machine (conformance mirror). */
    fun states(): Set<String> = StateMachines.ALLOCATION.states

    /** All catalog event labels for the machine (conformance mirror). */
    fun events(): Set<String> = StateMachines.ALLOCATION.transitions.map { it.event }.toSet()
}
