package com.omnillm.features.lan

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.lan.domain.LanAuthPolicy
import com.omnillm.features.lan.domain.LanAuthResult
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.interfaces.http.auth.HttpTransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative auth cases: fail closed on LAN (FEAT-LAN / SEC-AUTH-NET).
 */
class LanAuthNegativeTest {

    @Test
    fun lan_missingBearer_unauthorized() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = false,
                tokenChannel = LanAuthPolicy.TokenChannel.NONE,
                loopbackOnly = false,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_loopbackOnlyToken_forbidden() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LOOPBACK,
                loopbackOnly = true,
                grantedScopes = setOf("inference.create"),
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as LanAuthResult.Fail).error.code)
        assertTrue(r.error.message!!.contains("loopback-only"))
    }

    @Test
    fun lan_loopbackChannelEvenIfFlagFalse_forbidden() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LOOPBACK,
                loopbackOnly = false,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_nonPairingChannel_unauthorized() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.NONE,
                loopbackOnly = false,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_epochMismatch_pairingRequired() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenConnectionEpoch = 1L,
                currentConnectionEpoch = 2L,
                grantedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.PAIRING_REQUIRED, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_missingRequiredScope_forbidden() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenConnectionEpoch = 1L,
                currentConnectionEpoch = 1L,
                grantedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                requiredScope = "models.read",
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_neverOnLanScope_forbidden() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenConnectionEpoch = 1L,
                currentConnectionEpoch = 1L,
                grantedScopes = setOf("tokens.manage"),
                requiredScope = "tokens.manage",
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_revokedToken_unauthorized() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenRevoked = true,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_expiredToken_unauthorized() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenExpired = true,
            ),
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun lan_validPairingToken_ok() {
        val r = LanAuthPolicy.authenticate(
            LanAuthPolicy.AuthContext(
                transport = HttpTransportKind.LAN_TLS13,
                hasBearer = true,
                tokenChannel = LanAuthPolicy.TokenChannel.LAN_PAIRING,
                loopbackOnly = false,
                tokenConnectionEpoch = 3L,
                currentConnectionEpoch = 3L,
                grantedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                requiredScope = "inference.create",
            ),
        )
        assertTrue(r is LanAuthResult.Ok)
    }

    @Test
    fun pairingExchange_onlyOnLanTls() {
        val bad = LanAuthPolicy.requireLanTlsForPairingExchange(HttpTransportKind.LOOPBACK)
        assertTrue(bad is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (bad as LanAuthResult.Fail).error.code)

        val good = LanAuthPolicy.requireLanTlsForPairingExchange(HttpTransportKind.LAN_TLS13)
        assertTrue(good is LanAuthResult.Ok)
    }

    @Test
    fun createChallenge_notOnLanListener() {
        val r = LanAuthPolicy.requireLanManageForChallengeCreate(
            transport = HttpTransportKind.LAN_TLS13,
            grantedScopes = setOf("lan.manage"),
            isLocalUi = false,
        )
        assertTrue(r is LanAuthResult.Fail)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as LanAuthResult.Fail).error.code)
    }

    @Test
    fun createChallenge_localUiOnLoopback_ok() {
        val r = LanAuthPolicy.requireLanManageForChallengeCreate(
            transport = HttpTransportKind.LOOPBACK,
            grantedScopes = emptySet(),
            isLocalUi = true,
        )
        assertTrue(r is LanAuthResult.Ok)
    }
}
