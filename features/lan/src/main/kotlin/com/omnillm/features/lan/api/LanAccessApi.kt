package com.omnillm.features.lan.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.lan.domain.LanAccessSnapshot
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Public API for UI / Admin composition (FEAT-LAN).
 *
 * - LAN default-off; enable requires LOCAL_UI / lan.manage
 * - Pairing challenge material shown once; QR has no long-lived secrets
 * - Token plaintext only in bounded [LanTokenIssuanceReceipt]
 * - Mutations go through control-plane ports (ADR-010)
 */
interface LanAccessApi {

    fun snapshot(): LanAccessSnapshot

    suspend fun refresh(
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<LanAccessSnapshot>

    /** ENABLE: DISABLED → STARTING (control plane binds TLS + pairing). */
    suspend fun enableLan(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus>

    /** DISABLE → DRAINING → DISABLED; bumps connection epoch. */
    suspend fun disableLan(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus>

    /**
     * Create a locally approved pairing challenge (lan.manage).
     * Returns secret material for QR / short-code display only.
     */
    suspend fun createPairingChallenge(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView>

    /** Local UI approval: PENDING → APPROVED. */
    suspend fun approvePairingChallenge(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView>

    /**
     * Complete channel-bound exchange (normally on LAN TLS listener).
     * Feature API used by control-plane adapter / tests; validates SPKI + epoch.
     */
    suspend fun completePairingExchange(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt>

    fun acknowledgeTokenReceipt()

    suspend fun revokeClient(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView>
}
