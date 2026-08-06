package com.omnillm.features.lan.domain

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt

/**
 * UI presentation phases for FEAT-LAN (UX-STATE-CATALOG labels only).
 * Durable lifecycle remains LAN_SERVICE / PAIRING_CHALLENGE from state-machines.yaml.
 */
enum class LanScreenPhase {
    /** LAN never enabled / no snapshot. */
    EMPTY,
    LOADING,
    /** Service DISABLED (default-off). */
    DISABLED,
    /** STARTING / ADVERTISING / ACTIVE with healthy TLS. */
    READY,
    /** ROTATING / DRAINING / partial. */
    DEGRADED,
    ERROR,
    ;

    companion object {
        fun isKnown(name: String): Boolean = entries.any { it.name == name }
    }
}

data class LanAccessSnapshot(
    val presentation: LanScreenPhase,
    val service: LanServiceStatus?,
    val clients: List<LanClientView>,
    val pendingChallenge: LanChallengeView?,
    /** Plaintext token shown once after successful pairing (bounded receipt). */
    val pendingTokenReceipt: LanTokenIssuanceReceipt?,
    val error: OmniError?,
    val updatedAtEpochMs: Long,
    /** Product default disclosure for UI. */
    val defaultLanEnabled: Boolean = LanServiceLifecyclePolicy.DEFAULT_ENABLED,
) {
    init {
        require(updatedAtEpochMs >= 0L)
    }

    val isEmpty: Boolean
        get() = presentation == LanScreenPhase.EMPTY ||
            (service == null && clients.isEmpty() && error == null)

    val isDisabled: Boolean
        get() = presentation == LanScreenPhase.DISABLED ||
            service?.state == "DISABLED"

    val isLoading: Boolean get() = presentation == LanScreenPhase.LOADING
    val isError: Boolean get() = presentation == LanScreenPhase.ERROR
}
