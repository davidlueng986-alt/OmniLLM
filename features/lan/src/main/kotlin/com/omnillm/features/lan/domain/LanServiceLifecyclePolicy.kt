package com.omnillm.features.lan.domain

import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.lan.LanFeatureModule

/**
 * LAN_SERVICE lifecycle policy (state-machines.yaml + FEAT-LAN §1).
 *
 * Default-off: initial state DISABLED; configuration `server.lanEnabled` defaults false.
 * Network / certificate / binding changes bump connection epoch before accepting new work.
 * Old pairing must not silently continue across epochs.
 */
object LanServiceLifecyclePolicy {

    val STATES: Set<String> = StateMachines.LAN_SERVICE.states
    val INITIAL: String = StateMachines.LAN_SERVICE.initial

    /** Product default — specs/configuration-catalog.yaml server.lanEnabled */
    const val DEFAULT_ENABLED: Boolean = false
    const val SETTING_KEY: String = LanFeatureModule.SETTING_LAN_ENABLED

    init {
        require(INITIAL == "DISABLED") {
            "LAN_SERVICE must start DISABLED (default-off)"
        }
        require(!DEFAULT_ENABLED) {
            "server.lanEnabled product default must be false"
        }
    }

    fun step(
        from: String,
        event: String,
        guards: Map<String, Boolean> = emptyMap(),
    ): TransitionOutcome =
        StateMachineDriver.transition(
            machine = StateMachines.LAN_SERVICE,
            from = from,
            event = event,
            guards = if (guards.isEmpty()) GuardEvaluator.ALWAYS_TRUE else GuardEvaluator.of(guards),
        )

    /**
     * Whether the service may accept inference / pairing exchanges.
     * Never before TLS identity + pairing endpoint are ready (LAN_SERVICE invariants).
     */
    fun mayAcceptClients(state: String): Boolean =
        state == "ADVERTISING" || state == "ACTIVE"

    fun mayCreatePairingChallenge(state: String): Boolean =
        mayAcceptClients(state)

    fun isDrainingOrStopped(state: String): Boolean =
        state == "DISABLED" || state == "DRAINING" || state == "STARTING" || state == "ERROR"

    /**
     * After IDENTITY_OR_BINDING_CHANGED / DISABLE the catalog requires bumpConnectionEpoch.
     * Tokens bound to the previous epoch must fail closed until re-pair.
     */
    fun eventsThatBumpEpoch(): Set<String> = setOf(
        "IDENTITY_OR_BINDING_CHANGED",
        "DISABLE",
    )

    fun transitionBumpsEpoch(actions: List<String>): Boolean =
        "bumpConnectionEpoch" in actions
}
