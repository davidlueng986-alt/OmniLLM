package com.omnillm.features.lan

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanCommandIdentity
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.domain.QrPayloadPolicy
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.features.lan.usecase.LanAccessService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun principal(): PrincipalId = LocalUiPrincipal.ID

fun cmd(suffix: String = "1"): LanCommandIdentity =
    LanCommandIdentity(
        commandId = uuid("c$suffix"),
        idempotencyKey = "idem-$suffix",
    )

fun uuid(seed: String = "a"): String {
    val h = seed.hashCode().toUInt().toString(16).padStart(8, '0')
    return "$h-aaaa-bbbb-cccc-${h.padStart(12, '0').take(12)}"
}

/** 64-char lowercase hex SPKI fingerprint (only 0-9a-f). */
fun spki(c: Char = 'a'): String {
    require(c in '0'..'9' || c in 'a'..'f') { "spki char must be hex digit" }
    return c.toString().repeat(64)
}

fun ports(
    service: FakeLanService = FakeLanService(),
    pairing: FakeLanPairing = FakeLanPairing(service),
    clients: FakeLanClients = FakeLanClients(),
    clock: () -> Long = { 1_700_000_000_000L },
): LanRuntimePorts =
    LanRuntimePorts(
        service = service,
        pairing = pairing,
        clients = clients,
        clockMs = clock,
    )

fun service(p: LanRuntimePorts = ports()): LanAccessService = LanAccessService(p)

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

class FakeLanService(
    var statusValue: LanServiceStatus = LanServiceStatus(
        enabled = false,
        state = "DISABLED",
        connectionEpoch = 0L,
        serverSpkiSha256 = null,
        boundAddresses = emptyList(),
        port = null,
        certificateValid = false,
        tlsReady = false,
        pairingEndpointReady = false,
    ),
) : LanServicePort {
    var enableCalls: Int = 0
    var disableCalls: Int = 0

    fun markActive(
        epoch: Long = 1L,
        spkiSha: String = spki('b'),
        host: String = "192.168.1.10",
        port: Int = 11443,
    ) {
        statusValue = LanServiceStatus(
            enabled = true,
            state = "ACTIVE",
            connectionEpoch = epoch,
            serverSpkiSha256 = spkiSha,
            boundAddresses = listOf(host),
            port = port,
            certificateValid = true,
            tlsReady = true,
            pairingEndpointReady = true,
            activeClientCount = 0,
            resourceVersion = 1L,
        )
    }

    override suspend fun status(): OmniResult<LanServiceStatus> = OmniResult.ok(statusValue)

    override suspend fun enable(
        principal: PrincipalId,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus> {
        enableCalls++
        markActive()
        return OmniResult.ok(statusValue)
    }

    override suspend fun disable(
        principal: PrincipalId,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus> {
        disableCalls++
        val nextEpoch = statusValue.connectionEpoch + 1
        statusValue = LanServiceStatus(
            enabled = false,
            state = "DISABLED",
            connectionEpoch = nextEpoch,
            serverSpkiSha256 = null,
            boundAddresses = emptyList(),
            port = null,
            certificateValid = false,
            tlsReady = false,
            pairingEndpointReady = false,
            resourceVersion = statusValue.resourceVersion + 1,
        )
        return OmniResult.ok(statusValue)
    }
}

class FakeLanPairing(
    private val service: FakeLanService,
) : LanPairingPort {
    private val challenges = ConcurrentHashMap<String, LanChallengeView>()
    private val now = AtomicLong(1_700_000_000_000L)

    fun setNow(ms: Long) {
        now.set(ms)
    }

    override suspend fun createChallenge(
        principal: PrincipalId,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        val st = service.statusValue
        if (!st.mayAcceptClients) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "LAN not ready"),
            )
        }
        val secret = "A".repeat(32) // base64url-ish 192-bit-ish shape
        val expires = now.get() + spec.ttlSeconds * 1000L
        val material = QrPayloadPolicy.QrMaterial(
            protocolLabel = LanFeatureModule.PAIRING_PROTOCOL_LABEL,
            serverLocator = st.serverLocator ?: "https://192.168.1.10:11443",
            serverSpkiSha256 = st.serverSpkiSha256!!,
            connectionEpoch = st.connectionEpoch,
            challengeId = spec.challengeId,
            pairingSecret = secret,
            expiresAtEpochMs = expires,
            requestedScopes = spec.requestedScopes,
        )
        val view = LanChallengeView(
            challengeId = spec.challengeId,
            state = "PENDING",
            serverSpkiSha256 = st.serverSpkiSha256!!,
            connectionEpoch = st.connectionEpoch,
            requestedScopes = spec.requestedScopes,
            expiresAtEpochMs = expires,
            attemptsRemaining = LanFeatureModule.PAIRING_MAX_ATTEMPTS,
            pairingSecret = secret,
            qrPayload = QrPayloadPolicy.encode(material),
            clientDisplayHint = spec.clientDisplayHint,
        )
        challenges[spec.challengeId] = view
        return OmniResult.ok(view)
    }

    override suspend fun approveChallenge(
        principal: PrincipalId,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        val cur = challenges[spec.challengeId]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "challenge not found"))
        if (cur.state != "PENDING") {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "not PENDING"))
        }
        val next = cur.copy(
            state = "APPROVED",
            requestedScopes = spec.approvedScopes,
        )
        challenges[spec.challengeId] = next
        return OmniResult.ok(next)
    }

    override suspend fun completeExchange(
        principal: PrincipalId,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt> {
        val cur = challenges[spec.challengeId]
        if (cur != null) {
            if (cur.state != "APPROVED") {
                return OmniResult.err(
                    OmniError.FORBIDDEN(message = "challenge not approved"),
                )
            }
            challenges[spec.challengeId] = cur.copy(state = "CONSUMED", pairingSecret = null)
        }
        val st = service.statusValue
        val scopes = spec.requestedScopes.ifEmpty { LanScopePolicy.DEFAULT_INFER_SCOPES }
        return OmniResult.ok(
            LanTokenIssuanceReceipt(
                exchangeId = spec.exchangeId,
                clientId = "lan-client-${spec.challengeId.take(8)}",
                tokenId = uuid("tok"),
                tokenPlaintext = "lan-bearer-" + "x".repeat(40),
                scopes = scopes,
                expiresAtEpochMs = now.get() + 86_400_000L,
                receiptExpiresAtEpochMs = now.get() + 60_000L,
                revocationEpoch = 0L,
                connectionEpoch = st.connectionEpoch,
                serverSpkiSha256 = st.serverSpkiSha256 ?: spec.observedSpkiSha256,
            ),
        )
    }
}

class FakeLanClients : LanClientPort {
    private val map = ConcurrentHashMap<String, LanClientView>()

    fun seed(client: LanClientView) {
        map[client.clientId] = client
    }

    override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
        OmniResult.ok(map.values.toList())

    override suspend fun revoke(
        principal: PrincipalId,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView> {
        val cur = map[spec.clientId]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "client not found"))
        val next = cur.copy(state = "REVOKED", revocationEpoch = cur.revocationEpoch + 1)
        map[spec.clientId] = next
        return OmniResult.ok(next)
    }
}
