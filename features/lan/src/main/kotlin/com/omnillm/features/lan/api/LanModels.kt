package com.omnillm.features.lan.api

import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.lan.LanFeatureModule
import com.omnillm.features.lan.domain.LanScopePolicy

/**
 * LAN service status projection (LAN_SERVICE FSM).
 * [enabled] reflects server.lanEnabled; default product value is false.
 */
data class LanServiceStatus(
    val enabled: Boolean,
    /** LAN_SERVICE state from catalog. */
    val state: String,
    val connectionEpoch: Long,
    val serverSpkiSha256: String?,
    val boundAddresses: List<String> = emptyList(),
    val port: Int? = null,
    val certificateValid: Boolean = false,
    val tlsReady: Boolean = false,
    val pairingEndpointReady: Boolean = false,
    val activeClientCount: Int = 0,
    val resourceVersion: Long = 0L,
    val failureReason: String? = null,
) {
    init {
        require(state in LAN_SERVICE_STATES) {
            "unknown LAN_SERVICE state (fail closed): $state"
        }
        require(connectionEpoch >= 0L)
        require(activeClientCount >= 0)
        require(resourceVersion >= 0L)
        port?.let { require(it in 1..65535) }
        serverSpkiSha256?.let {
            require(it.matches(HEX64)) { "serverSpkiSha256 must be 64-char hex" }
        }
    }

    val mayAcceptClients: Boolean
        get() = enabled && (state == "ADVERTISING" || state == "ACTIVE") &&
            tlsReady && pairingEndpointReady && certificateValid

    val serverLocator: String?
        get() {
            val host = boundAddresses.firstOrNull() ?: return null
            val p = port ?: return null
            return "https://$host:$p"
        }

    companion object {
        val LAN_SERVICE_STATES: Set<String> = StateMachines.LAN_SERVICE.states
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * PAIRING_CHALLENGE projection for local admin UI / QR display.
 * [pairingSecret] is one-time short-TTL; never a long-lived bearer.
 */
data class LanChallengeView(
    val challengeId: String,
    val state: String,
    val protocolLabel: String = LanFeatureModule.PAIRING_PROTOCOL_LABEL,
    val serverSpkiSha256: String,
    val connectionEpoch: Long,
    val requestedScopes: Set<String>,
    val expiresAtEpochMs: Long,
    val attemptsRemaining: Int,
    /** One-time secret for QR — not a long-lived credential. */
    val pairingSecret: String?,
    val qrPayload: String?,
    val clientDisplayHint: String? = null,
) {
    init {
        require(challengeId.isNotBlank())
        require(state in CHALLENGE_STATES) {
            "unknown PAIRING_CHALLENGE state (fail closed): $state"
        }
        require(protocolLabel == LanFeatureModule.PAIRING_PROTOCOL_LABEL)
        require(serverSpkiSha256.matches(HEX64))
        require(connectionEpoch >= 0L)
        require(requestedScopes.isNotEmpty())
        require(attemptsRemaining >= 0)
        require(expiresAtEpochMs >= 0L)
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    companion object {
        val CHALLENGE_STATES: Set<String> = StateMachines.PAIRING_CHALLENGE.states
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

data class LanClientView(
    val clientId: String,
    val displayName: String,
    /** CLIENT_REGISTRATION state. */
    val state: String,
    val scopes: Set<String>,
    val connectionEpoch: Long,
    val revocationEpoch: Long,
    val lastSeenAtEpochMs: Long? = null,
) {
    init {
        require(clientId.isNotBlank())
        require(displayName.isNotBlank())
        require(state in CLIENT_STATES)
        require(connectionEpoch >= 0L)
        require(revocationEpoch >= 0L)
        require(scopes.isNotEmpty())
    }

    companion object {
        val CLIENT_STATES: Set<String> = setOf(
            "PENDING",
            "ACTIVE",
            "SUSPENDED",
            "REVOCATION_REQUESTED",
            "DRAINING",
            "REVOKED",
            "EXPIRED",
        )
    }
}

/**
 * Bounded LAN token issuance receipt — plaintext once (SEC-PROFILE).
 * [loopbackOnly] is always false for LAN pairing tokens.
 */
data class LanTokenIssuanceReceipt(
    val exchangeId: String,
    val clientId: String,
    val tokenId: String,
    val tokenPlaintext: String,
    val scopes: Set<String>,
    val expiresAtEpochMs: Long,
    val receiptExpiresAtEpochMs: Long,
    val revocationEpoch: Long,
    val connectionEpoch: Long,
    val serverSpkiSha256: String,
) {
    init {
        require(exchangeId.isNotBlank())
        require(clientId.isNotBlank())
        require(tokenId.isNotBlank())
        require(tokenPlaintext.isNotBlank())
        require(scopes.isNotEmpty())
        require(expiresAtEpochMs >= 0L)
        require(receiptExpiresAtEpochMs >= 0L)
        require(revocationEpoch >= 0L)
        require(connectionEpoch >= 0L)
        require(serverSpkiSha256.matches(HEX64))
    }

    val loopbackOnly: Boolean get() = false

    fun isReceiptExpired(nowEpochMs: Long): Boolean = nowEpochMs >= receiptExpiresAtEpochMs

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

data class LanCommandIdentity(
    val commandId: String,
    val idempotencyKey: String,
) {
    init {
        require(commandId.isNotBlank())
        require(idempotencyKey.isNotBlank())
    }
}

data class EnableLanSpec(
    val command: LanCommandIdentity,
    /** Approved network interfaces / address families (opaque to feature; control plane binds). */
    val approvedInterfaces: List<String> = emptyList(),
)

data class DisableLanSpec(
    val command: LanCommandIdentity,
)

data class CreatePairingChallengeSpec(
    val command: LanCommandIdentity,
    val challengeId: String,
    val requestedScopes: Set<String> = LanScopePolicy.DEFAULT_INFER_SCOPES,
    /** Scopes the local admin explicitly approved (required for every LAN scope). */
    val explicitlyApprovedScopes: Set<String> = requestedScopes,
    val clientDisplayHint: String? = null,
    val ttlSeconds: Int = LanFeatureModule.PAIRING_TTL_SECONDS,
) {
    init {
        require(challengeId.isNotBlank())
        require(requestedScopes.isNotEmpty())
        require(ttlSeconds == LanFeatureModule.PAIRING_TTL_SECONDS) {
            "ttl_seconds must be ${LanFeatureModule.PAIRING_TTL_SECONDS} (SEC-PROFILE)"
        }
    }
}

data class ApprovePairingChallengeSpec(
    val command: LanCommandIdentity,
    val challengeId: String,
    val approvedScopes: Set<String>,
)

data class CompletePairingExchangeSpec(
    val command: LanCommandIdentity,
    val exchangeId: String,
    val challengeId: String,
    val clientPublicKey: String,
    val requestedScopes: Set<String>,
    val proofBase64Url: String,
    val observedSpkiSha256: String,
    val observedConnectionEpoch: Long,
)

data class RevokeLanClientSpec(
    val command: LanCommandIdentity,
    val clientId: String,
)
