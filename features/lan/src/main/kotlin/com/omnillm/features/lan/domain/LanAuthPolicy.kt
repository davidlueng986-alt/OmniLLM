package com.omnillm.features.lan.domain

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.auth.HttpTransportKind

/**
 * Fail-closed authentication / transport policy for LAN (FEAT-LAN, SEC-AUTH-NET,
 * access-control-catalog invariants).
 *
 * - loopback admin bearer tokens are never accepted by the LAN listener
 * - LAN bearer tokens are issued only by the channel-bound LAN pairing exchange
 * - missing / invalid credentials ⇒ UNAUTHORIZED (not a soft allow)
 * - wrong transport for pairing exchange ⇒ FORBIDDEN
 */
object LanAuthPolicy {

    enum class TokenChannel {
        /** Issued via loopback developer / local-admin HTTP path. */
        LOOPBACK,
        /** Issued via channel-bound LAN pairing exchange. */
        LAN_PAIRING,
        /** Unknown / unauthenticated. */
        NONE,
    }

    data class AuthContext(
        val transport: HttpTransportKind,
        val hasBearer: Boolean,
        val tokenChannel: TokenChannel,
        /** Token marked loopback-only (must never work on LAN). */
        val loopbackOnly: Boolean,
        val tokenRevoked: Boolean = false,
        val tokenExpired: Boolean = false,
        val tokenConnectionEpoch: Long? = null,
        val currentConnectionEpoch: Long? = null,
        val grantedScopes: Set<String> = emptySet(),
        val requiredScope: String? = null,
    )

    /**
     * Authorize a request on the given transport. Always fail closed.
     */
    fun authenticate(ctx: AuthContext): LanAuthResult {
        when (ctx.transport) {
            HttpTransportKind.LAN_TLS13 -> return authenticateLan(ctx)
            HttpTransportKind.LOOPBACK -> return authenticateLoopbackNote(ctx)
        }
    }

    private fun authenticateLan(ctx: AuthContext): LanAuthResult {
        if (!ctx.hasBearer) {
            return LanAuthResult.Fail(
                OmniError.UNAUTHORIZED(message = "LAN requires scoped bearer token"),
            )
        }
        if (ctx.tokenRevoked) {
            return LanAuthResult.Fail(
                OmniError.UNAUTHORIZED(message = "token revoked"),
            )
        }
        if (ctx.tokenExpired) {
            return LanAuthResult.Fail(
                OmniError.UNAUTHORIZED(message = "token expired"),
            )
        }
        // access-control-catalog: loopback admin bearer tokens never accepted by LAN listener
        if (ctx.loopbackOnly || ctx.tokenChannel == TokenChannel.LOOPBACK) {
            return LanAuthResult.Fail(
                OmniError.FORBIDDEN(
                    message = "loopback-only token rejected on LAN listener",
                    details = mapOf("transport" to HttpTransportKind.LAN_TLS13.name),
                ),
            )
        }
        if (ctx.tokenChannel != TokenChannel.LAN_PAIRING) {
            return LanAuthResult.Fail(
                OmniError.UNAUTHORIZED(
                    message = "LAN bearer tokens are issued only by channel-bound pairing",
                ),
            )
        }
        val tokenEpoch = ctx.tokenConnectionEpoch
        val currentEpoch = ctx.currentConnectionEpoch
        if (tokenEpoch != null && currentEpoch != null && tokenEpoch != currentEpoch) {
            return LanAuthResult.Fail(
                OmniError.PAIRING_REQUIRED(
                    message = "connection epoch changed — re-pair required",
                    details = mapOf(
                        "tokenEpoch" to tokenEpoch.toString(),
                        "currentEpoch" to currentEpoch.toString(),
                    ),
                ),
            )
        }
        val required = ctx.requiredScope
        if (required != null) {
            when (val az = LanScopePolicy.authorizeOperation(ctx.grantedScopes, required)) {
                is LanAuthzDecision.Allowed -> Unit
                is LanAuthzDecision.Denied -> {
                    return LanAuthResult.Fail(
                        OmniError.FORBIDDEN(
                            message = az.reason,
                            details = mapOf("requiredScope" to az.requiredScope),
                        ),
                    )
                }
            }
        }
        return LanAuthResult.Ok
    }

    private fun authenticateLoopbackNote(ctx: AuthContext): LanAuthResult {
        // Feature policy note: LAN pairing exchange is not served on loopback.
        // This path is for clarity in unit tests; full loopback auth lives in HTTP gateway.
        if (!ctx.hasBearer) {
            return LanAuthResult.Fail(
                OmniError.UNAUTHORIZED(message = "bearer required"),
            )
        }
        return LanAuthResult.Ok
    }

    /**
     * Pairing exchange transport gate (OpenAPI x-omnillm-allowed-transports: LAN_TLS13).
     */
    fun requireLanTlsForPairingExchange(transport: HttpTransportKind): LanAuthResult {
        if (transport != HttpTransportKind.LAN_TLS13) {
            return LanAuthResult.Fail(
                OmniError.FORBIDDEN(
                    message = "pairing exchange only on LAN TLS listener",
                    details = mapOf("transport" to transport.name),
                ),
            )
        }
        return LanAuthResult.Ok
    }

    /**
     * Challenge creation requires lan.manage on a non-LAN admin path (loopback / LOCAL_UI).
     */
    fun requireLanManageForChallengeCreate(
        transport: HttpTransportKind,
        grantedScopes: Set<String>,
        isLocalUi: Boolean,
    ): LanAuthResult {
        if (transport == HttpTransportKind.LAN_TLS13) {
            return LanAuthResult.Fail(
                OmniError.FORBIDDEN(
                    message = "createLanPairingChallenge is not accepted on LAN listener",
                ),
            )
        }
        if (isLocalUi) return LanAuthResult.Ok
        return when (LanScopePolicy.authorizeOperation(grantedScopes, "lan.manage")) {
            // lan.manage is NEVER_ON_LAN for LAN_CLIENT — use direct check for admin profiles.
            is LanAuthzDecision.Allowed -> LanAuthResult.Ok
            is LanAuthzDecision.Denied -> {
                if ("lan.manage" in grantedScopes || "*" in grantedScopes) {
                    LanAuthResult.Ok
                } else {
                    LanAuthResult.Fail(
                        OmniError.FORBIDDEN(
                            message = "lan.manage scope required",
                            details = mapOf("requiredScope" to "lan.manage"),
                        ),
                    )
                }
            }
        }
    }
}

sealed class LanAuthResult {
    data object Ok : LanAuthResult()
    data class Fail(val error: OmniError) : LanAuthResult()
}
