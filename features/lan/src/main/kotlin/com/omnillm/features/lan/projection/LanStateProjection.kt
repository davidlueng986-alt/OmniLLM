package com.omnillm.features.lan.projection

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.domain.LanAccessSnapshot
import com.omnillm.features.lan.domain.LanScreenPhase
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy

/**
 * Projects control-plane facts into FEAT-LAN UI snapshots.
 * Does not invent domain FSM states.
 */
object LanStateProjection {

    private val LAN_STATES: Set<String> = StateMachines.LAN_SERVICE.states
    private val CHALLENGE_STATES: Set<String> = StateMachines.PAIRING_CHALLENGE.states

    fun project(
        loading: Boolean,
        service: LanServiceStatus?,
        clients: List<LanClientView>,
        pendingChallenge: LanChallengeView?,
        pendingReceipt: LanTokenIssuanceReceipt?,
        error: OmniError?,
        nowEpochMs: Long,
        hasRefreshed: Boolean,
    ): LanAccessSnapshot {
        service?.let {
            require(it.state in LAN_STATES) { "unknown LAN_SERVICE state: ${it.state}" }
        }
        pendingChallenge?.let {
            require(it.state in CHALLENGE_STATES) {
                "unknown PAIRING_CHALLENGE state: ${it.state}"
            }
        }

        val presentation = resolvePhase(
            loading = loading,
            service = service,
            error = error,
            hasRefreshed = hasRefreshed,
        )

        return LanAccessSnapshot(
            presentation = presentation,
            service = service,
            clients = clients,
            pendingChallenge = pendingChallenge,
            pendingTokenReceipt = pendingReceipt,
            error = error,
            updatedAtEpochMs = nowEpochMs,
            defaultLanEnabled = LanServiceLifecyclePolicy.DEFAULT_ENABLED,
        )
    }

    fun resolvePhase(
        loading: Boolean,
        service: LanServiceStatus?,
        error: OmniError?,
        hasRefreshed: Boolean,
    ): LanScreenPhase {
        if (loading) return LanScreenPhase.LOADING
        if (!hasRefreshed && service == null && error == null) return LanScreenPhase.EMPTY
        if (error != null && service == null) return LanScreenPhase.ERROR
        if (service == null) return LanScreenPhase.EMPTY
        return when (service.state) {
            "DISABLED" -> LanScreenPhase.DISABLED
            "ERROR" -> LanScreenPhase.ERROR
            "STARTING", "ADVERTISING", "ACTIVE" -> {
                if (service.tlsReady && service.certificateValid) {
                    LanScreenPhase.READY
                } else {
                    LanScreenPhase.DEGRADED
                }
            }
            "ROTATING", "DRAINING" -> LanScreenPhase.DEGRADED
            else -> LanScreenPhase.ERROR
        }
    }
}
