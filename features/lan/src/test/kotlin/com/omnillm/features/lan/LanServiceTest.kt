package com.omnillm.features.lan

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.domain.LanScreenPhase
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.viewmodel.LanAccessViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-LAN happy path + lifecycle: default-off, enable, pair, revoke, disable.
 */
class LanServiceTest {

    @Test
    fun initialSnapshot_emptyAndDefaultOff() {
        val api = service()
        val snap = api.snapshot()
        assertEquals(LanScreenPhase.EMPTY, snap.presentation)
        assertTrue(snap.isEmpty)
        assertFalse(snap.defaultLanEnabled)
        assertNull(snap.service)
    }

    @Test
    fun refresh_disabledService_projectsDisabled() = runBlocking {
        val api = service()
        val r = api.refresh()
        assertTrue(r is OmniResult.Ok)
        val snap = (r as OmniResult.Ok).value
        assertEquals(LanScreenPhase.DISABLED, snap.presentation)
        assertFalse(snap.service!!.enabled)
        assertEquals("DISABLED", snap.service!!.state)
    }

    @Test
    fun enableThenPair_issuesLanTokenOnce() = runBlocking {
        val lan = FakeLanService()
        val pairing = FakeLanPairing(lan)
        val api = service(ports(service = lan, pairing = pairing))

        val en = api.enableLan(spec = EnableLanSpec(command = cmd("en")))
        assertTrue(en is OmniResult.Ok)
        assertTrue((en as OmniResult.Ok).value.enabled)
        assertEquals("ACTIVE", en.value.state)
        assertTrue(en.value.tlsReady)

        val chId = uuid("happy-ch")
        val created = api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(
                command = cmd("cr"),
                challengeId = chId,
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )
        assertTrue(created is OmniResult.Ok)
        val challenge = (created as OmniResult.Ok).value
        assertEquals("PENDING", challenge.state)
        assertNotNull(challenge.qrPayload)
        assertNotNull(challenge.pairingSecret)
        assertFalse(challenge.qrPayload!!.contains("bearer_token"))
        assertFalse(challenge.qrPayload!!.contains("token_plaintext"))

        val approved = api.approvePairingChallenge(
            spec = ApprovePairingChallengeSpec(
                command = cmd("ap"),
                challengeId = chId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )
        assertTrue(approved is OmniResult.Ok)
        assertEquals("APPROVED", (approved as OmniResult.Ok).value.state)

        val receipt = api.completePairingExchange(
            spec = CompletePairingExchangeSpec(
                command = cmd("ex"),
                exchangeId = uuid("exh"),
                challengeId = chId,
                clientPublicKey = "D".repeat(64),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                proofBase64Url = "E".repeat(43),
                observedSpkiSha256 = challenge.serverSpkiSha256,
                observedConnectionEpoch = challenge.connectionEpoch,
            ),
        )
        assertTrue(receipt is OmniResult.Ok)
        val tok = (receipt as OmniResult.Ok).value
        assertTrue(tok.tokenPlaintext.isNotBlank())
        assertFalse(tok.loopbackOnly)
        assertEquals(LanScopePolicy.DEFAULT_INFER_SCOPES, tok.scopes)
        assertNotNull(api.snapshot().pendingTokenReceipt)

        api.acknowledgeTokenReceipt()
        assertNull(api.snapshot().pendingTokenReceipt)
    }

    @Test
    fun disable_bumpsEpochAndClearsChallenge() = runBlocking {
        val lan = FakeLanService().also { it.markActive(epoch = 3L) }
        val api = service(ports(service = lan))
        api.refresh()
        val before = lan.statusValue.connectionEpoch

        val chId = uuid("clr")
        api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(command = cmd("c"), challengeId = chId),
        )
        assertNotNull(api.snapshot().pendingChallenge)

        val dis = api.disableLan(spec = DisableLanSpec(command = cmd("d")))
        assertTrue(dis is OmniResult.Ok)
        assertEquals("DISABLED", (dis as OmniResult.Ok).value.state)
        assertTrue(dis.value.connectionEpoch > before)
        assertNull(api.snapshot().pendingChallenge)
    }

    @Test
    fun createChallenge_whileDisabled_fails() = runBlocking {
        val api = service()
        val r = api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(
                command = cmd("x"),
                challengeId = uuid("off"),
            ),
        )
        // Port returns STATE_CONFLICT when not ready; service may also pre-check.
        assertTrue(r is OmniResult.Err)
        val code = (r as OmniResult.Err).error.code
        assertTrue(
            code == OmniErrorCode.STATE_CONFLICT || code == OmniErrorCode.INVALID_REQUEST,
        )
    }

    @Test
    fun revokeClient() = runBlocking {
        val clients = FakeLanClients()
        clients.seed(
            com.omnillm.features.lan.api.LanClientView(
                clientId = "c1",
                displayName = "Phone",
                state = "ACTIVE",
                scopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                connectionEpoch = 1L,
                revocationEpoch = 0L,
            ),
        )
        val api = service(ports(clients = clients))
        api.refresh()
        assertEquals(1, api.snapshot().clients.size)

        val rev = api.revokeClient(
            spec = RevokeLanClientSpec(command = cmd("rv"), clientId = "c1"),
        )
        assertTrue(rev is OmniResult.Ok)
        assertEquals("REVOKED", (rev as OmniResult.Ok).value.state)
        assertEquals(1L, rev.value.revocationEpoch)
    }

    @Test
    fun viewModel_enableRefresh() = runBlocking {
        val lan = FakeLanService()
        val api = service(ports(service = lan))
        val vm = LanAccessViewModel(api)
        assertEquals(LanScreenPhase.EMPTY, vm.uiState().phase)
        assertFalse(vm.uiState().productDefaultLanEnabled)

        vm.onEnable(EnableLanSpec(command = cmd("ve")))
        assertTrue(vm.uiState().lanEnabled)
        assertEquals("ACTIVE", vm.uiState().serviceState)
        assertNotNull(vm.uiState().serverSpkiSha256)
    }

    @Test
    fun featureModule_ids() {
        assertEquals(":features:lan", LanFeatureModule.MODULE_PATH)
        assertEquals("FEAT-LAN", LanFeatureModule.FEATURE_ID)
        assertEquals(300, LanFeatureModule.PAIRING_TTL_SECONDS)
        assertEquals(5, LanFeatureModule.PAIRING_MAX_ATTEMPTS)
        assertEquals("OmniLLM-LAN-Pairing-1", LanFeatureModule.PAIRING_PROTOCOL_LABEL)
    }
}
