package com.omnillm.features.lan.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec

/**
 * Runtime ports for FEAT-LAN.
 *
 * Implementations live in the control plane (ADR-010). The feature module
 * never opens DB, token vault, TLS keystore, or loads native engines.
 */
data class LanRuntimePorts(
    val service: LanServicePort,
    val pairing: LanPairingPort,
    val clients: LanClientPort,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/** LAN_SERVICE lifecycle + TLS identity facts. */
interface LanServicePort {
    suspend fun status(): OmniResult<LanServiceStatus>

    suspend fun enable(
        principal: PrincipalId,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus>

    suspend fun disable(
        principal: PrincipalId,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus>
}

/** Pairing challenge create / approve / consume (PAIRING_CHALLENGE). */
interface LanPairingPort {
    suspend fun createChallenge(
        principal: PrincipalId,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView>

    suspend fun approveChallenge(
        principal: PrincipalId,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView>

    /**
     * Channel-bound exchange. Control plane verifies proof, issues token,
     * stores HMAC only. Feature validates SPKI/epoch policy before calling
     * when the status snapshot is available.
     */
    suspend fun completeExchange(
        principal: PrincipalId,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt>
}

interface LanClientPort {
    suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>>

    suspend fun revoke(
        principal: PrincipalId,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView>
}
