package com.omnillm.features.lan

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.domain.PairingChallengePolicy
import com.omnillm.features.lan.domain.PairingDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing negative cases: MITM, replay/expiry, epoch, unapproved, over-scope.
 */
class LanPairingNegativeTest {

    private val baseChallenge = PairingChallengePolicy.ChallengeSnapshot(
        challengeId = uuid("ch1"),
        state = "APPROVED",
        connectionEpoch = 2L,
        serverSpkiSha256 = spki('d'),
        requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
        expiresAtEpochMs = 1_700_000_300_000L,
        attemptsRemaining = 5,
    )

    @Test
    fun mitm_spkiMismatch_rejects() {
        val d = PairingChallengePolicy.evaluateExchange(
            baseChallenge,
            PairingChallengePolicy.ExchangeProof(
                challengeId = baseChallenge.challengeId,
                observedSpkiSha256 = spki('e'),
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("SPKI"))
    }

    @Test
    fun epochMismatch_rejects() {
        val d = PairingChallengePolicy.evaluateExchange(
            baseChallenge,
            PairingChallengePolicy.ExchangeProof(
                challengeId = baseChallenge.challengeId,
                observedSpkiSha256 = baseChallenge.serverSpkiSha256,
                observedConnectionEpoch = 99L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("epoch"))
    }

    @Test
    fun expiredChallenge_rejects() {
        val d = PairingChallengePolicy.evaluateExchange(
            baseChallenge,
            PairingChallengePolicy.ExchangeProof(
                challengeId = baseChallenge.challengeId,
                observedSpkiSha256 = baseChallenge.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = baseChallenge.expiresAtEpochMs + 1,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("expired"))
    }

    @Test
    fun attemptExhausted_rejects() {
        val ch = baseChallenge.copy(attemptsRemaining = 0)
        val d = PairingChallengePolicy.evaluateExchange(
            ch,
            PairingChallengePolicy.ExchangeProof(
                challengeId = ch.challengeId,
                observedSpkiSha256 = ch.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("attempt"))
    }

    @Test
    fun unapprovedPending_rejects() {
        val ch = baseChallenge.copy(state = "PENDING")
        val d = PairingChallengePolicy.evaluateExchange(
            ch,
            PairingChallengePolicy.ExchangeProof(
                challengeId = ch.challengeId,
                observedSpkiSha256 = ch.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("approved"))
    }

    @Test
    fun alreadyConsumed_rejects() {
        val ch = baseChallenge.copy(state = "CONSUMED")
        val d = PairingChallengePolicy.evaluateExchange(
            ch,
            PairingChallengePolicy.ExchangeProof(
                challengeId = ch.challengeId,
                observedSpkiSha256 = ch.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
        assertTrue((d as PairingDecision.Reject).reason.contains("terminal"))
    }

    @Test
    fun invalidProof_rejects() {
        val d = PairingChallengePolicy.evaluateExchange(
            baseChallenge,
            PairingChallengePolicy.ExchangeProof(
                challengeId = baseChallenge.challengeId,
                observedSpkiSha256 = baseChallenge.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = false,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Reject)
    }

    @Test
    fun happyPath_accepts() {
        val d = PairingChallengePolicy.evaluateExchange(
            baseChallenge,
            PairingChallengePolicy.ExchangeProof(
                challengeId = baseChallenge.challengeId,
                observedSpkiSha256 = baseChallenge.serverSpkiSha256,
                observedConnectionEpoch = 2L,
                proofValid = true,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertTrue(d is PairingDecision.Accept)
        assertEquals(LanScopePolicy.DEFAULT_INFER_SCOPES, (d as PairingDecision.Accept).scopes)
    }

    @Test
    fun service_createChallenge_rejectsAdminScope() = runBlocking {
        val lan = FakeLanService().also { it.markActive() }
        val api = service(ports(service = lan))
        val r = api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(
                command = cmd("bad"),
                challengeId = uuid("badch"),
                requestedScopes = setOf("inference.create", "tokens.manage"),
                explicitlyApprovedScopes = setOf("inference.create", "tokens.manage"),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun service_completeExchange_spkiMismatch_fails() = runBlocking {
        val lan = FakeLanService().also { it.markActive(epoch = 1L, spkiSha = spki('f')) }
        val pairing = FakeLanPairing(lan)
        val api = service(ports(service = lan, pairing = pairing))

        api.enableLan(spec = EnableLanSpec(command = cmd("en")))
        val chId = uuid("ch-mitm")
        val created = api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(
                command = cmd("cr"),
                challengeId = chId,
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )
        assertTrue(created is OmniResult.Ok)
        api.approvePairingChallenge(
            spec = ApprovePairingChallengeSpec(
                command = cmd("ap"),
                challengeId = chId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )

        val exchange = api.completePairingExchange(
            spec = CompletePairingExchangeSpec(
                command = cmd("ex"),
                exchangeId = uuid("ex1"),
                challengeId = chId,
                clientPublicKey = "B".repeat(64),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                proofBase64Url = "C".repeat(43),
                observedSpkiSha256 = spki('0'), // MITM
                observedConnectionEpoch = 1L,
            ),
        )
        assertTrue(exchange is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (exchange as OmniResult.Err).error.code)
    }

    @Test
    fun service_completeExchange_epochMismatch_fails() = runBlocking {
        val lan = FakeLanService().also { it.markActive(epoch = 5L, spkiSha = spki('9')) }
        val pairing = FakeLanPairing(lan)
        val api = service(ports(service = lan, pairing = pairing))

        val chId = uuid("ch-ep")
        api.createPairingChallenge(
            spec = CreatePairingChallengeSpec(
                command = cmd("cr2"),
                challengeId = chId,
            ),
        )
        api.approvePairingChallenge(
            spec = ApprovePairingChallengeSpec(
                command = cmd("ap2"),
                challengeId = chId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )

        val exchange = api.completePairingExchange(
            spec = CompletePairingExchangeSpec(
                command = cmd("ex2"),
                exchangeId = uuid("ex2"),
                challengeId = chId,
                clientPublicKey = "B".repeat(64),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                proofBase64Url = "C".repeat(43),
                observedSpkiSha256 = spki('9'),
                observedConnectionEpoch = 1L, // wrong epoch
            ),
        )
        assertTrue(exchange is OmniResult.Err)
        assertEquals(OmniErrorCode.PAIRING_REQUIRED, (exchange as OmniResult.Err).error.code)
    }
}
