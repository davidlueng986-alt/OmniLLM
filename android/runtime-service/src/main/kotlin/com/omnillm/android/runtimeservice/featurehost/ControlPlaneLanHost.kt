package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.http.LanTlsEndpoint
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.LanFeatureModule
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
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy
import com.omnillm.features.lan.domain.QrPayloadPolicy
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.interfaces.http.gateway.LanTlsGatewayConfig
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.runtime.policy.security.PairingChallengeService
import com.omnillm.runtime.policy.security.TokenService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Control-plane host for FEAT-LAN ports (ADR-010 sole writer).
 *
 * - LAN default-off ([LanServiceLifecyclePolicy.DEFAULT_ENABLED] / `server.lanEnabled`)
 * - TLS identity via [LanTlsEndpoint] + Secret Broker keys (SEC-PROFILE / SEC-AUTH-NET)
 * - Pairing via [PairingChallengeService] (TTL, max attempts, channel binding, one-time)
 * - Scoped LAN tokens via [TokenService] (HMAC verifier only; plaintext once)
 * - Connection epoch bump on disable / identity change (no silent cross-epoch reuse)
 * - QR carries locator / SPKI / one-time challenge only — never long-lived secrets
 */
class ControlPlaneLanHost(
    private val policyManager: PolicyManager,
    private val pairingService: PairingChallengeService? = null,
    private val tokenService: TokenService? = null,
    private val tlsEndpoint: LanTlsEndpoint? = null,
    /**
     * When true (production), ENABLE starts network TLS bind.
     * Hermetic unit tests set false so identity is real without opening sockets.
     */
    private val bindNetworkOnEnable: Boolean = false,
    private val lanPort: Int = LanTlsGatewayConfig.DEFAULT_PORT,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : LanServicePort, LanPairingPort, LanClientPort {

    private val lock = Any()
    private val resourceVersion = AtomicLong(0L)
    private val connectionEpoch = AtomicLong(0L)

    @Volatile
    private var status: LanServiceStatus = disabledStatus(resourceVersion = 0L, epoch = 0L)

    /** Fallback challenge store when [pairingService] is not injected (tests only). */
    private val localChallenges = ConcurrentHashMap<String, LocalChallenge>()
    private val clients = ConcurrentHashMap<String, LanClientView>()
    private val clientTokenIds = ConcurrentHashMap<String, String>()

    fun asPorts(): LanRuntimePorts =
        LanRuntimePorts(
            service = this,
            pairing = this,
            clients = this,
            clockMs = clockMs,
        )

    fun isDefaultOff(): Boolean =
        !LanServiceLifecyclePolicy.DEFAULT_ENABLED && !status.enabled && status.state == "DISABLED"

    fun currentStatus(): LanServiceStatus = snapshotStatus()

    fun adminLanStateLabel(): String {
        val st = snapshotStatus()
        return when {
            st.state == "DRAINING" -> "DRAINING"
            st.enabled && st.state != "DISABLED" -> "ENABLED"
            else -> "DISABLED"
        }
    }

    override suspend fun status(): OmniResult<LanServiceStatus> = OmniResult.ok(snapshotStatus())

    override suspend fun enable(
        principal: PrincipalId,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus> {
        synchronized(lock) {
            val cur = status
            when (cur.state) {
                "DISABLED", "ERROR" -> Unit
                "STARTING", "ADVERTISING", "ACTIVE", "ROTATING" -> {
                    if (cur.enabled && cur.tlsReady) return OmniResult.ok(cur)
                }
                "DRAINING" ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(message = "LAN is draining; wait for DISABLED"),
                    )
                else ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "LAN enable not allowed from state ${cur.state}",
                        ),
                    )
            }

            // DISABLED → STARTING (catalog LAN-001).
            status = LanServiceStatus(
                enabled = true,
                state = "STARTING",
                connectionEpoch = connectionEpoch.get(),
                serverSpkiSha256 = null,
                boundAddresses = emptyList(),
                port = null,
                certificateValid = false,
                tlsReady = false,
                pairingEndpointReady = false,
                activeClientCount = clients.values.count { it.state == "ACTIVE" },
                resourceVersion = resourceVersion.incrementAndGet(),
            )
            persistLanEnabled(true)

            val endpoint = tlsEndpoint
            if (endpoint == null) {
                // No TLS stack attached — remain STARTING, not pairing-ready (fail closed).
                return OmniResult.ok(status)
            }

            val started = endpoint.start(
                bindNetwork = bindNetworkOnEnable,
                config = LanTlsGatewayConfig(port = lanPort),
                approvedInterfaces = spec.approvedInterfaces,
            )
            when (started) {
                is OmniResult.Err -> {
                    status = LanServiceStatus(
                        enabled = true,
                        state = "ERROR",
                        connectionEpoch = connectionEpoch.get(),
                        serverSpkiSha256 = null,
                        boundAddresses = emptyList(),
                        port = null,
                        certificateValid = false,
                        tlsReady = false,
                        pairingEndpointReady = false,
                        activeClientCount = 0,
                        resourceVersion = resourceVersion.incrementAndGet(),
                        failureReason = started.error.message,
                    )
                    return OmniResult.err(started.error)
                }
                is OmniResult.Ok -> {
                    val ep = started.value
                    // STARTING → ADVERTISING → ACTIVE when TLS + pairing endpoint ready.
                    status = LanServiceStatus(
                        enabled = true,
                        state = if (ep.pairingEndpointReady) "ACTIVE" else "STARTING",
                        connectionEpoch = connectionEpoch.get(),
                        serverSpkiSha256 = ep.serverSpkiSha256,
                        boundAddresses = ep.boundAddresses,
                        port = ep.port,
                        certificateValid = ep.certificateValid,
                        tlsReady = ep.identityReady && ep.certificateValid,
                        pairingEndpointReady = ep.pairingEndpointReady,
                        activeClientCount = clients.values.count { it.state == "ACTIVE" },
                        resourceVersion = resourceVersion.incrementAndGet(),
                        failureReason = ep.failureReason,
                    )
                    return OmniResult.ok(status)
                }
            }
        }
    }

    override suspend fun disable(
        principal: PrincipalId,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus> {
        synchronized(lock) {
            val cur = status
            if (cur.state == "DISABLED" && !cur.enabled) {
                return OmniResult.ok(cur)
            }
            // DISABLE bumps connection epoch (catalog actions).
            val nextEpoch = connectionEpoch.incrementAndGet()
            localChallenges.clear()
            tlsEndpoint?.stop()
            // Revoke active LAN clients / tokens (best-effort; epoch fence is authoritative).
            clients.keys.toList().forEach { id ->
                revokeClientLocked(id)
            }
            val next = disabledStatus(
                resourceVersion = resourceVersion.incrementAndGet(),
                epoch = nextEpoch,
            )
            status = next
            persistLanEnabled(false)
            return OmniResult.ok(next)
        }
    }

    override suspend fun createChallenge(
        principal: PrincipalId,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        val st = snapshotStatus()
        if (!st.mayAcceptClients) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "LAN not ready for pairing (default-off or TLS not ready)",
                    details = mapOf(
                        "enabled" to st.enabled.toString(),
                        "state" to st.state,
                        "tlsReady" to st.tlsReady.toString(),
                    ),
                ),
            )
        }
        val spki = st.serverSpkiSha256
            ?: return OmniResult.err(OmniError.STATE_CONFLICT(message = "TLS SPKI unavailable"))
        val locator = st.serverLocator
            ?: return OmniResult.err(OmniError.STATE_CONFLICT(message = "no server locator"))

        val pairing = pairingService
        if (pairing != null) {
            val created = pairing.createLanChallenge(
                PairingChallengeService.CreateLanChallengeRequest(
                    requestedScopes = spec.requestedScopes,
                    serverSpkiSha256 = spki,
                    connectionEpoch = st.connectionEpoch,
                    ttlSeconds = spec.ttlSeconds,
                    maxAttempts = LanFeatureModule.PAIRING_MAX_ATTEMPTS,
                ),
            )
            return when (created) {
                is OmniResult.Err -> created
                is OmniResult.Ok -> {
                    val v = created.value
                    val secret = v.secretPlaintextOnce
                        ?: return OmniResult.err(
                            OmniError.INTERNAL(message = "pairing secret missing after create"),
                        )
                    // Prefer control-plane challenge id; map requested id for UI when different.
                    val challengeId = v.challengeId
                    val material = QrPayloadPolicy.QrMaterial(
                        protocolLabel = LanFeatureModule.PAIRING_PROTOCOL_LABEL,
                        serverLocator = locator,
                        serverSpkiSha256 = spki,
                        connectionEpoch = st.connectionEpoch,
                        challengeId = challengeId,
                        pairingSecret = secret,
                        expiresAtEpochMs = v.expiresAtEpochMs,
                        requestedScopes = v.requestedScopes,
                    )
                    OmniResult.ok(
                        LanChallengeView(
                            challengeId = challengeId,
                            state = v.state,
                            serverSpkiSha256 = spki,
                            connectionEpoch = st.connectionEpoch,
                            requestedScopes = v.requestedScopes,
                            expiresAtEpochMs = v.expiresAtEpochMs,
                            attemptsRemaining = v.attemptsRemaining,
                            pairingSecret = secret,
                            qrPayload = QrPayloadPolicy.encode(material),
                            clientDisplayHint = spec.clientDisplayHint,
                        ),
                    )
                }
            }
        }

        // Local fallback (no PairingChallengeService) — still one-time short-TTL secret.
        val secret = randomBase64Url(LanFeatureModule.PAIRING_SECRET_BITS / 8)
        val expires = clockMs() + spec.ttlSeconds * 1000L
        val material = QrPayloadPolicy.QrMaterial(
            protocolLabel = LanFeatureModule.PAIRING_PROTOCOL_LABEL,
            serverLocator = locator,
            serverSpkiSha256 = spki,
            connectionEpoch = st.connectionEpoch,
            challengeId = spec.challengeId,
            pairingSecret = secret,
            expiresAtEpochMs = expires,
            requestedScopes = spec.requestedScopes,
        )
        val view = LanChallengeView(
            challengeId = spec.challengeId,
            state = "PENDING",
            serverSpkiSha256 = spki,
            connectionEpoch = st.connectionEpoch,
            requestedScopes = spec.requestedScopes,
            expiresAtEpochMs = expires,
            attemptsRemaining = LanFeatureModule.PAIRING_MAX_ATTEMPTS,
            pairingSecret = secret,
            qrPayload = QrPayloadPolicy.encode(material),
            clientDisplayHint = spec.clientDisplayHint,
        )
        localChallenges[spec.challengeId] = LocalChallenge(view = view, secret = secret)
        return OmniResult.ok(view)
    }

    override suspend fun approveChallenge(
        principal: PrincipalId,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        val pairing = pairingService
        if (pairing != null) {
            return when (
                val r = pairing.approve(
                    challengeId = spec.challengeId,
                    actorIsLocalUi = true,
                    nowEpochMs = clockMs(),
                )
            ) {
                is OmniResult.Err -> r
                is OmniResult.Ok -> {
                    val v = r.value
                    val st = snapshotStatus()
                    OmniResult.ok(
                        LanChallengeView(
                            challengeId = v.challengeId,
                            state = v.state,
                            serverSpkiSha256 = v.serverSpkiSha256 ?: st.serverSpkiSha256!!,
                            connectionEpoch = v.connectionEpoch ?: st.connectionEpoch,
                            requestedScopes = spec.approvedScopes.ifEmpty { v.requestedScopes },
                            expiresAtEpochMs = v.expiresAtEpochMs,
                            attemptsRemaining = v.attemptsRemaining,
                            pairingSecret = null,
                            qrPayload = null,
                        ),
                    )
                }
            }
        }
        val cur = localChallenges[spec.challengeId]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "challenge not found"))
        if (cur.view.state != "PENDING") {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "challenge not PENDING"))
        }
        val next = cur.view.copy(state = "APPROVED", requestedScopes = spec.approvedScopes)
        localChallenges[spec.challengeId] = cur.copy(view = next)
        return OmniResult.ok(next)
    }

    override suspend fun completeExchange(
        principal: PrincipalId,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt> {
        val st = snapshotStatus()
        if (!st.mayAcceptClients) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "LAN not ready for pairing exchange"),
            )
        }
        if (st.serverSpkiSha256 != null &&
            !spec.observedSpkiSha256.equals(st.serverSpkiSha256, ignoreCase = true)
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "SPKI pin mismatch",
                    details = mapOf(
                        "observed" to spec.observedSpkiSha256,
                        "expected" to st.serverSpkiSha256!!,
                    ),
                ),
            )
        }
        if (spec.observedConnectionEpoch != st.connectionEpoch) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "connection epoch mismatch (re-pair required)",
                    details = mapOf(
                        "observed" to spec.observedConnectionEpoch.toString(),
                        "current" to st.connectionEpoch.toString(),
                    ),
                ),
            )
        }

        val pairing = pairingService
        if (pairing != null) {
            val registrationId = "lan-reg-${spec.challengeId}"
            val exchange = pairing.completeLanExchange(
                PairingChallengeService.LanExchangeRequest(
                    challengeId = spec.challengeId,
                    observedSpkiSha256 = spec.observedSpkiSha256,
                    observedConnectionEpoch = spec.observedConnectionEpoch,
                    clientPublicKey = spec.clientPublicKey,
                    proofBase64Url = spec.proofBase64Url,
                    registrationId = registrationId,
                    principalId = PrincipalId.parse("http-lan-$registrationId"),
                ),
            )
            return when (exchange) {
                is OmniResult.Err -> exchange
                is OmniResult.Ok -> {
                    val tok = exchange.value.token
                    val clientId = registrationId
                    clients[clientId] = LanClientView(
                        clientId = clientId,
                        displayName = clientId,
                        state = "ACTIVE",
                        scopes = tok.scopes,
                        connectionEpoch = st.connectionEpoch,
                        revocationEpoch = tok.revocationEpoch,
                        lastSeenAtEpochMs = clockMs(),
                    )
                    clientTokenIds[clientId] = tok.tokenId
                    bumpActiveClientsLocked()
                    OmniResult.ok(
                        LanTokenIssuanceReceipt(
                            exchangeId = spec.exchangeId,
                            clientId = clientId,
                            tokenId = tok.tokenId,
                            tokenPlaintext = tok.plaintextOnce,
                            scopes = tok.scopes,
                            expiresAtEpochMs = tok.expiresAtEpochMs,
                            receiptExpiresAtEpochMs = clockMs() + 60_000L,
                            revocationEpoch = tok.revocationEpoch,
                            connectionEpoch = st.connectionEpoch,
                            serverSpkiSha256 = st.serverSpkiSha256 ?: spec.observedSpkiSha256,
                        ),
                    )
                }
            }
        }

        // Local fallback path (no durable pairing service).
        val stored = localChallenges[spec.challengeId]
        if (stored != null) {
            if (stored.view.state != "APPROVED") {
                val remaining = (stored.view.attemptsRemaining - 1).coerceAtLeast(0)
                localChallenges[spec.challengeId] = stored.copy(
                    view = stored.view.copy(attemptsRemaining = remaining),
                )
                if (remaining <= 0) {
                    localChallenges[spec.challengeId] = stored.copy(
                        view = stored.view.copy(
                            state = "EXPIRED",
                            attemptsRemaining = 0,
                            pairingSecret = null,
                        ),
                    )
                    return OmniResult.err(
                        OmniError.FORBIDDEN(message = "attempt budget exhausted"),
                    )
                }
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "challenge not approved",
                        details = mapOf("attemptsRemaining" to remaining.toString()),
                    ),
                )
            }
            localChallenges[spec.challengeId] = stored.copy(
                view = stored.view.copy(state = "CONSUMED", pairingSecret = null),
            )
        }
        val clientId = "lan-client-${spec.challengeId.take(8)}"
        val tokenId = UUID.randomUUID().toString()
        val now = clockMs()
        val scopes = spec.requestedScopes.ifEmpty { stored?.view?.requestedScopes.orEmpty() }
        if (scopes.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "empty scopes"))
        }
        val plaintext = if (tokenService != null) {
            when (
                val issued = tokenService.issue(
                    TokenService.IssueRequest(
                        registrationId = clientId,
                        principalId = PrincipalId.parse("http-lan-$clientId"),
                        scopes = scopes,
                        transportConstraint = TokenService.TransportConstraint.LAN_ONLY,
                        ttlSeconds = 86_400L,
                        profile = com.omnillm.core.canonical.generated.AccessProfile.LAN_CLIENT,
                        label = "lan-pairing",
                        clientId = clientId,
                    ),
                )
            ) {
                is OmniResult.Err -> return issued
                is OmniResult.Ok -> {
                    clientTokenIds[clientId] = issued.value.tokenId
                    issued.value
                }
            }
        } else {
            null
        }
        clients[clientId] = LanClientView(
            clientId = clientId,
            displayName = clientId,
            state = "ACTIVE",
            scopes = scopes,
            connectionEpoch = st.connectionEpoch,
            revocationEpoch = plaintext?.revocationEpoch ?: 0L,
            lastSeenAtEpochMs = now,
        )
        bumpActiveClientsLocked()
        return OmniResult.ok(
            LanTokenIssuanceReceipt(
                exchangeId = spec.exchangeId,
                clientId = clientId,
                tokenId = plaintext?.tokenId ?: tokenId,
                tokenPlaintext = plaintext?.plaintextOnce
                    ?: ("lan-" + UUID.randomUUID().toString().replace("-", "")),
                scopes = scopes,
                expiresAtEpochMs = plaintext?.expiresAtEpochMs ?: (now + 86_400_000L),
                receiptExpiresAtEpochMs = now + 60_000L,
                revocationEpoch = plaintext?.revocationEpoch ?: 0L,
                connectionEpoch = st.connectionEpoch,
                serverSpkiSha256 = st.serverSpkiSha256 ?: spec.observedSpkiSha256,
            ),
        )
    }

    override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
        OmniResult.ok(clients.values.sortedBy { it.clientId })

    override suspend fun revoke(
        principal: PrincipalId,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView> {
        synchronized(lock) {
            val next = revokeClientLocked(spec.clientId)
                ?: return OmniResult.err(OmniError.NOT_FOUND(message = "client not found"))
            bumpActiveClientsLocked()
            return OmniResult.ok(next)
        }
    }

    private fun revokeClientLocked(clientId: String): LanClientView? {
        val cur = clients[clientId] ?: return null
        val tokenId = clientTokenIds.remove(clientId)
        if (tokenId != null && tokenService != null) {
            tokenService.revoke(
                tokenId = tokenId,
                actor = PrincipalId.parse("local-ui"),
                reason = "lan client revoke",
                authorised = true,
            )
        }
        val next = cur.copy(
            state = "REVOKED",
            revocationEpoch = cur.revocationEpoch + 1,
        )
        clients[clientId] = next
        return next
    }

    private fun bumpActiveClientsLocked() {
        val cur = status
        status = cur.copy(
            activeClientCount = clients.values.count { it.state == "ACTIVE" },
            resourceVersion = resourceVersion.incrementAndGet(),
        )
    }

    private fun snapshotStatus(): LanServiceStatus = synchronized(lock) {
        // Refresh TLS facts if endpoint reports drift.
        val ep = tlsEndpoint?.status()
        if (ep != null && status.enabled && status.state != "DISABLED" && status.state != "DRAINING") {
            if (ep.serverSpkiSha256 != null && status.serverSpkiSha256 != ep.serverSpkiSha256) {
                // Identity change → bump epoch (fail closed for old pairings).
                connectionEpoch.incrementAndGet()
            }
            if (status.tlsReady != (ep.identityReady && ep.certificateValid) ||
                status.pairingEndpointReady != ep.pairingEndpointReady
            ) {
                status = status.copy(
                    serverSpkiSha256 = ep.serverSpkiSha256 ?: status.serverSpkiSha256,
                    boundAddresses = ep.boundAddresses.ifEmpty { status.boundAddresses },
                    port = ep.port ?: status.port,
                    certificateValid = ep.certificateValid,
                    tlsReady = ep.identityReady && ep.certificateValid,
                    pairingEndpointReady = ep.pairingEndpointReady,
                    connectionEpoch = connectionEpoch.get(),
                    state = when {
                        ep.pairingEndpointReady && status.enabled -> "ACTIVE"
                        status.enabled && status.state == "STARTING" -> "STARTING"
                        else -> status.state
                    },
                    resourceVersion = resourceVersion.incrementAndGet(),
                )
            }
        }
        status
    }

    private fun persistLanEnabled(enabled: Boolean) {
        val snap = policyManager.settingsSnapshot()
        policyManager.patchSettings(
            baseVersion = snap.resourceVersion,
            changes = mapOf(
                LanFeatureModule.SETTING_LAN_ENABLED to SettingValue.BoolValue(enabled),
            ),
            source = "administrator-policy",
        )
    }

    private data class LocalChallenge(
        val view: LanChallengeView,
        val secret: String,
    )

    companion object {
        fun disabledStatus(resourceVersion: Long, epoch: Long): LanServiceStatus =
            LanServiceStatus(
                enabled = false,
                state = "DISABLED",
                connectionEpoch = epoch,
                serverSpkiSha256 = null,
                boundAddresses = emptyList(),
                port = null,
                certificateValid = false,
                tlsReady = false,
                pairingEndpointReady = false,
                activeClientCount = 0,
                resourceVersion = resourceVersion,
            )

        private fun randomBase64Url(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
