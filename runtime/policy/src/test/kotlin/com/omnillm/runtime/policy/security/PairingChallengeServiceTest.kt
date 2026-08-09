package com.omnillm.runtime.policy.security
import com.omnillm.core.ports.security.ChallengeKind
import com.omnillm.core.ports.security.SecurityProfile
import com.omnillm.core.ports.security.TransportConstraint

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.policy.RevocationEpochManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingChallengeServiceTest {

    private var now = 1_700_000_000_000L
    private val broker = InMemorySecretBroker { now }
    private val tokens = TokenService(broker, RevocationEpochManager(clock = { now })) { now }
    private val pairing = PairingChallengeService(broker, tokens) { now }

    private val spki = "ab".repeat(32)

    private fun createApprovedChallenge(
        scopes: Set<String> = setOf("inference.create", "inference.cancel", "inference.read-own"),
        epoch: Long = 1L,
    ): PairingChallengeService.PairingChallengeView {
        val created = pairing.createLanChallenge(
            PairingChallengeService.CreateLanChallengeRequest(
                requestedScopes = scopes,
                serverSpkiSha256 = spki,
                connectionEpoch = epoch,
            ),
        ) as OmniResult.Ok
        assertNotNull(created.value.secretPlaintextOnce)
        val approved = pairing.approve(
            created.value.challengeId,
            actorIsLocalUi = true,
            nowEpochMs = now,
        ) as OmniResult.Ok
        assertEquals("APPROVED", approved.value.state)
        assertNull(approved.value.secretPlaintextOnce)
        // Re-fetch secret from create path is gone; recover via decrypt for test proof.
        return created.value
    }

    private fun proofFor(
        created: PairingChallengeService.PairingChallengeView,
        clientPublicKey: String = "client-pub-key",
    ): String {
        val secretB64 = created.secretPlaintextOnce!!
        val secret = CryptoPrimitives.decodeBase64Url(secretB64)!!
        val rec = pairing.get(created.challengeId)!!
        // After approve, get() has no secret; use original created plaintext for proof.
        val transcript = LanPairingTranscript(
            serverSpkiSha256 = spki,
            connectionEpoch = created.connectionEpoch!!,
            challengeId = created.challengeId,
            serverNonce = created.serverNonceBase64Url!!,
            clientPublicKey = clientPublicKey,
            requestedScopes = created.requestedScopes,
            issuedAtEpochMs = now, // may mismatch — use stored createdAt via exchange path
            expiresAtEpochMs = created.expiresAtEpochMs,
        )
        // Use service's stored createdAt: completeLanExchange builds transcript from record.
        // Compute with createdAt from record by re-reading via side channel: challenge view lacks it.
        // For proof we need exact issuedAt = record.createdAtEpochMs which equals `now` at create.
        val proof = broker.computeLanPairingProof(transcript, secret) as OmniResult.Ok
        return CryptoPrimitives.encodeBase64Url(proof.value)
    }

    @Test
    fun lanChallenge_hasChannelBindingFields() {
        val created = pairing.createLanChallenge(
            PairingChallengeService.CreateLanChallengeRequest(
                requestedScopes = setOf("models.read"),
                serverSpkiSha256 = spki,
                connectionEpoch = 7L,
            ),
        ) as OmniResult.Ok
        assertEquals(ChallengeKind.LAN_HMAC, created.value.kind)
        assertEquals(SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL, created.value.protocolLabel)
        assertEquals(spki, created.value.serverSpkiSha256)
        assertEquals(7L, created.value.connectionEpoch)
        assertNotNull(created.value.serverNonceBase64Url)
        assertEquals(SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS, created.value.attemptsRemaining)
        assertTrue(created.value.expiresAtEpochMs > now)
        assertEquals(
            SecurityProfile.PAIRING_SECRET_BYTES,
            CryptoPrimitives.decodeBase64Url(created.value.secretPlaintextOnce!!)!!.size,
        )
    }

    @Test
    fun approve_requiresLocalUi() {
        val created = pairing.createLanChallenge(
            PairingChallengeService.CreateLanChallengeRequest(
                requestedScopes = setOf("inference.create"),
                serverSpkiSha256 = spki,
                connectionEpoch = 1L,
            ),
        ) as OmniResult.Ok
        val denied = pairing.approve(created.value.challengeId, actorIsLocalUi = false) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
    }

    @Test
    fun exchange_success_issuesLanToken_andErasesSecret() {
        val created = createApprovedChallenge()
        // Approve wiped display secret; we kept created.secretPlaintextOnce from create response.
        val proof = proofFor(created)
        val result = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = spki,
                observedConnectionEpoch = 1L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Ok
        assertEquals(TransportConstraint.LAN_ONLY, result.value.token.transportConstraint)
        assertEquals("CONSUMED", pairing.get(created.challengeId)!!.state)

        // Token works on LAN listener.
        val auth = tokens.authenticate(
            result.value.token.plaintextOnce,
            TransportConstraint.LAN_ONLY,
            now,
        ) as OmniResult.Ok
        assertTrue(auth.value.scopes.contains("inference.create"))

        // Replay consume fails.
        val replay = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = spki,
                observedConnectionEpoch = 1L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Err
        assertTrue(
            replay.error.code == OmniErrorCode.FORBIDDEN ||
                replay.error.code == OmniErrorCode.STATE_CONFLICT,
        )
    }

    @Test
    fun exchange_spkiMismatch_decrementsAttempts() {
        val created = createApprovedChallenge()
        val proof = proofFor(created)
        val fail = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = "cd".repeat(32),
                observedConnectionEpoch = 1L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.UNAUTHORIZED, fail.error.code)
        assertEquals(
            SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS - 1,
            pairing.get(created.challengeId)!!.attemptsRemaining,
        )
    }

    @Test
    fun exchange_epochMismatch_invalidates() {
        val created = createApprovedChallenge(epoch = 2L)
        val proof = proofFor(created)
        val fail = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = spki,
                observedConnectionEpoch = 99L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, fail.error.code)
    }

    @Test
    fun exchange_ttlExpiry_failClosed() {
        val created = createApprovedChallenge()
        val proof = proofFor(created)
        now += SecurityProfile.LAN_PAIRING_TTL_SECONDS * 1000L + 1L
        val fail = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = spki,
                observedConnectionEpoch = 1L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, fail.error.code)
    }

    @Test
    fun maxAttempts_invalidatesChallenge() {
        val created = createApprovedChallenge()
        val proof = proofFor(created)
        repeat(SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS) {
            pairing.completeLanExchange(
                PairingChallengeService.LanExchangeRequest(
                    challengeId = created.challengeId,
                    observedSpkiSha256 = spki,
                    observedConnectionEpoch = 1L,
                    clientPublicKey = "client-pub-key",
                    proofBase64Url = "AAAA", // bad proof
                    registrationId = "lan-reg-1",
                    principalId = PrincipalId.parse("http-lan-client-1"),
                ),
            )
        }
        val view = pairing.get(created.challengeId)!!
        assertEquals(0, view.attemptsRemaining)
        // Further exchange rejected.
        val fail = pairing.completeLanExchange(
            PairingChallengeService.LanExchangeRequest(
                challengeId = created.challengeId,
                observedSpkiSha256 = spki,
                observedConnectionEpoch = 1L,
                clientPublicKey = "client-pub-key",
                proofBase64Url = proof,
                registrationId = "lan-reg-1",
                principalId = PrincipalId.parse("http-lan-client-1"),
            ),
        ) as OmniResult.Err
        assertTrue(
            fail.error.code == OmniErrorCode.FORBIDDEN ||
                fail.error.code == OmniErrorCode.UNAUTHORIZED,
        )
    }

    @Test
    fun aidlChallenge_noSecret_localApproval() {
        val created = pairing.createAidLChallenge(
            PairingChallengeService.CreateAidLChallengeRequest(
                observedUid = 10123,
                androidUserId = 0,
                principalId = PrincipalId.parse("aidl:uid=10123:user=0"),
                requestedScopes = setOf("models.read", "inference.create"),
            ),
        ) as OmniResult.Ok
        assertEquals(ChallengeKind.AIDL_REGISTRATION, created.value.kind)
        assertNull(created.value.secretPlaintextOnce)
        val approved = pairing.approve(created.value.challengeId, actorIsLocalUi = true) as OmniResult.Ok
        assertEquals("APPROVED", approved.value.state)
    }

    @Test
    fun unknownScope_failClosed() {
        val err = pairing.createLanChallenge(
            PairingChallengeService.CreateLanChallengeRequest(
                requestedScopes = setOf("admin"),
                serverSpkiSha256 = spki,
                connectionEpoch = 1L,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.error.code)
    }
}
