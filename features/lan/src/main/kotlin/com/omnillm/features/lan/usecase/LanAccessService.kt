package com.omnillm.features.lan.usecase

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanAccessApi
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.domain.LanAccessSnapshot
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.domain.LanScopeValidation
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy
import com.omnillm.features.lan.domain.PairingChallengePolicy
import com.omnillm.features.lan.domain.PairingDecision
import com.omnillm.features.lan.domain.QrPayloadPolicy
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.projection.LanStateProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Default [LanAccessApi] (FEAT-LAN use-cases).
 *
 * Holds screen projection only — durable LAN/TLS/token mutations go through
 * ports owned by the runtime control plane (ADR-010).
 */
class LanAccessService(
    private val ports: LanRuntimePorts,
) : LanAccessApi {

    private val lock = Any()

    private var hasRefreshed: Boolean = false
    private var loading: Boolean = false
    private var service: LanServiceStatus? = null
    private var clients: List<LanClientView> = emptyList()
    private var pendingChallenge: LanChallengeView? = null
    private var pendingReceipt: LanTokenIssuanceReceipt? = null
    private var lastError: OmniError? = null

    override fun snapshot(): LanAccessSnapshot = synchronized(lock) { projectLocked() }

    override suspend fun refresh(principal: PrincipalId): OmniResult<LanAccessSnapshot> {
        requireLocalUi(principal)
        setLoading(true)
        val status = ports.service.status()
        val clientList = ports.clients.listClients(principal)
        val err = firstErr(status, clientList)
        synchronized(lock) {
            loading = false
            hasRefreshed = true
            if (status is OmniResult.Ok) service = status.value
            if (clientList is OmniResult.Ok) clients = clientList.value
            pendingReceipt = pendingReceipt?.takeUnless {
                it.isReceiptExpired(ports.clockMs())
            }
            pendingChallenge = pendingChallenge?.takeUnless {
                it.isExpired(ports.clockMs())
            }
            lastError = err
        }
        val snap = snapshot()
        return if (err != null && snap.presentation == com.omnillm.features.lan.domain.LanScreenPhase.ERROR) {
            OmniResult.err(err)
        } else {
            OmniResult.ok(snap)
        }
    }

    override suspend fun enableLan(
        principal: PrincipalId,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.service.enable(principal, spec)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    hasRefreshed = true
                    service = r.value
                    lastError = null
                }
                r
            }
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
        }
    }

    override suspend fun disableLan(
        principal: PrincipalId,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.service.disable(principal, spec)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    hasRefreshed = true
                    service = r.value
                    // Epoch bump invalidates pending challenges bound to old epoch.
                    pendingChallenge = null
                    lastError = null
                }
                r
            }
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
        }
    }

    override suspend fun createPairingChallenge(
        principal: PrincipalId,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        requireLocalUi(principal)
        when (
            val v = LanScopePolicy.validateRequestedScopes(
                requested = spec.requestedScopes,
                explicitlyApproved = spec.explicitlyApprovedScopes,
            )
        ) {
            is LanScopeValidation.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = v.message,
                    details = buildMap {
                        if (v.unknown.isNotEmpty()) put("unknown", v.unknown.joinToString(","))
                        if (v.disallowed.isNotEmpty()) put("disallowed", v.disallowed.joinToString(","))
                    },
                )
                fail(err)
                return OmniResult.err(err)
            }
            is LanScopeValidation.Ok -> Unit
        }
        if (spec.ttlSeconds != PairingChallengePolicy.TTL_SECONDS) {
            val err = OmniError.INVALID_REQUEST(
                message = "ttl_seconds must be ${PairingChallengePolicy.TTL_SECONDS}",
            )
            fail(err)
            return OmniResult.err(err)
        }
        val current = synchronized(lock) { service }
        if (current != null && !LanServiceLifecyclePolicy.mayCreatePairingChallenge(current.state)) {
            val err = OmniError.STATE_CONFLICT(
                message = "LAN service not ready for pairing challenges",
                details = mapOf("state" to current.state),
            )
            fail(err)
            return OmniResult.err(err)
        }
        setLoading(true)
        return when (val r = ports.pairing.createChallenge(principal, spec)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                // Enforce QR policy on returned material when present.
                val challenge = r.value
                if (challenge.qrPayload != null) {
                    val keys = parseQrKeys(challenge.qrPayload)
                    when (val qv = QrPayloadPolicy.validatePayloadKeys(keys)) {
                        is com.omnillm.features.lan.domain.QrValidation.Rejected -> {
                            val err = OmniError.INVALID_REQUEST(
                                message = qv.reason,
                                details = mapOf("fields" to qv.forbiddenFields.joinToString(",")),
                            )
                            fail(err)
                            return OmniResult.err(err)
                        }
                        is com.omnillm.features.lan.domain.QrValidation.Accepted -> Unit
                    }
                }
                if (challenge.pairingSecret != null &&
                    !QrPayloadPolicy.isOneTimePairingSecretShape(challenge.pairingSecret)
                ) {
                    val err = OmniError.INVALID_REQUEST(
                        message = "pairing_secret shape invalid for one-time QR transfer",
                    )
                    fail(err)
                    return OmniResult.err(err)
                }
                synchronized(lock) {
                    loading = false
                    lastError = null
                    pendingChallenge = challenge
                }
                r
            }
        }
    }

    override suspend fun approvePairingChallenge(
        principal: PrincipalId,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView> {
        requireLocalUi(principal)
        when (
            val v = LanScopePolicy.validateRequestedScopes(
                requested = spec.approvedScopes,
                explicitlyApproved = spec.approvedScopes,
            )
        ) {
            is LanScopeValidation.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = v.message,
                    details = buildMap {
                        if (v.unknown.isNotEmpty()) put("unknown", v.unknown.joinToString(","))
                        if (v.disallowed.isNotEmpty()) put("disallowed", v.disallowed.joinToString(","))
                    },
                )
                fail(err)
                return OmniResult.err(err)
            }
            is LanScopeValidation.Ok -> Unit
        }
        setLoading(true)
        return when (val r = ports.pairing.approveChallenge(principal, spec)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    pendingChallenge = r.value
                }
                r
            }
        }
    }

    override suspend fun completePairingExchange(
        principal: PrincipalId,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt> {
        // Exchange is normally unauthenticated on LAN TLS; LOCAL_UI may drive tests.
        val challenge = synchronized(lock) { pendingChallenge }
        val status = synchronized(lock) { service } ?: when (val s = ports.service.status()) {
            is OmniResult.Ok -> s.value.also {
                synchronized(lock) { service = it }
            }
            is OmniResult.Err -> {
                fail(s.error)
                return OmniResult.err(s.error)
            }
        }

        if (challenge != null && challenge.challengeId == spec.challengeId) {
            val decision = PairingChallengePolicy.evaluateExchange(
                challenge = PairingChallengePolicy.ChallengeSnapshot(
                    challengeId = challenge.challengeId,
                    state = challenge.state,
                    connectionEpoch = challenge.connectionEpoch,
                    serverSpkiSha256 = challenge.serverSpkiSha256,
                    requestedScopes = challenge.requestedScopes,
                    expiresAtEpochMs = challenge.expiresAtEpochMs,
                    attemptsRemaining = challenge.attemptsRemaining,
                    protocolLabel = challenge.protocolLabel,
                ),
                proof = PairingChallengePolicy.ExchangeProof(
                    challengeId = spec.challengeId,
                    observedSpkiSha256 = spec.observedSpkiSha256,
                    observedConnectionEpoch = spec.observedConnectionEpoch,
                    proofValid = spec.proofBase64Url.length >= 43,
                    nowEpochMs = ports.clockMs(),
                ),
            )
            when (decision) {
                is PairingDecision.Reject -> {
                    val err = mapPairingReject(decision)
                    fail(err)
                    return OmniResult.err(err)
                }
                is PairingDecision.Accept -> Unit
            }
        } else {
            // No local challenge view — still fail closed on epoch/SPKI vs service status.
            if (status.serverSpkiSha256 != null &&
                !PairingChallengePolicy.constantTimeEqualsHex(
                    status.serverSpkiSha256,
                    spec.observedSpkiSha256,
                )
            ) {
                val err = OmniError.FORBIDDEN(
                    message = "SPKI fingerprint mismatch (possible MITM)",
                    details = mapOf("reason" to "spki_mismatch"),
                )
                fail(err)
                return OmniResult.err(err)
            }
            if (spec.observedConnectionEpoch != status.connectionEpoch) {
                val err = OmniError.PAIRING_REQUIRED(
                    message = "connection epoch mismatch — re-pair required",
                    details = mapOf(
                        "serviceEpoch" to status.connectionEpoch.toString(),
                        "observedEpoch" to spec.observedConnectionEpoch.toString(),
                    ),
                )
                fail(err)
                return OmniResult.err(err)
            }
            when (
                val v = LanScopePolicy.validateRequestedScopes(
                    requested = spec.requestedScopes,
                    explicitlyApproved = spec.requestedScopes,
                )
            ) {
                is LanScopeValidation.Invalid -> {
                    val err = OmniError.INVALID_REQUEST(message = v.message)
                    fail(err)
                    return OmniResult.err(err)
                }
                is LanScopeValidation.Ok -> Unit
            }
        }

        setLoading(true)
        return when (val r = ports.pairing.completeExchange(principal, spec)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                // Token must not be loopback-only; receipt is LAN channel.
                require(!r.value.loopbackOnly) { "LAN token must not be loopback-only" }
                synchronized(lock) {
                    loading = false
                    lastError = null
                    pendingReceipt = r.value
                    pendingChallenge = pendingChallenge?.copy(state = "CONSUMED", pairingSecret = null)
                }
                r
            }
        }
    }

    override fun acknowledgeTokenReceipt() {
        synchronized(lock) {
            pendingReceipt = null
        }
    }

    override suspend fun revokeClient(
        principal: PrincipalId,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.clients.revoke(principal, spec)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    clients = clients.map {
                        if (it.clientId == r.value.clientId) r.value else it
                    }
                }
                r
            }
        }
    }

    // ------------------------------------------------------------------

    private fun projectLocked(): LanAccessSnapshot =
        LanStateProjection.project(
            loading = loading,
            service = service,
            clients = clients,
            pendingChallenge = pendingChallenge,
            pendingReceipt = pendingReceipt,
            error = lastError,
            nowEpochMs = ports.clockMs(),
            hasRefreshed = hasRefreshed,
        )

    private fun setLoading(value: Boolean) {
        synchronized(lock) {
            loading = value
            if (value) lastError = null
        }
    }

    private fun fail(error: OmniError) {
        synchronized(lock) {
            loading = false
            lastError = error
            hasRefreshed = true
        }
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "FEAT-LAN UI mutations require LOCAL_UI principal"
        }
    }

    private fun firstErr(vararg results: OmniResult<*>): OmniError? {
        for (r in results) {
            if (r is OmniResult.Err) return r.error
        }
        return null
    }

    private fun mapPairingReject(decision: PairingDecision.Reject): OmniError {
        val reason = decision.reason
        return when {
            reason.contains("SPKI") || reason.contains("MITM") ->
                OmniError.FORBIDDEN(message = reason, details = decision.details)
            reason.contains("epoch") ->
                OmniError.PAIRING_REQUIRED(message = reason, details = decision.details)
            reason.contains("expired") ->
                OmniError.STATE_CONFLICT(message = reason, details = decision.details)
            reason.contains("attempt") ->
                OmniError.RATE_LIMITED(message = reason, details = decision.details)
            reason.contains("approved") ->
                OmniError.FORBIDDEN(message = reason, details = decision.details)
            else ->
                OmniError.FORBIDDEN(message = reason, details = decision.details)
        }
    }

    private fun parseQrKeys(payload: String): Set<String> {
        // Supports omnillm-lan-pairing-1|k=v|k=v
        return payload.split('|')
            .drop(1)
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq)
            }
            .toSet()
    }
}
