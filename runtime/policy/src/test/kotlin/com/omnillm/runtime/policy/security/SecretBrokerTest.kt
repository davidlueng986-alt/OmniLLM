package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.ports.security.SecurityProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretBrokerTest {

    private var now = 1_700_000_000_000L
    private val broker = InMemorySecretBroker { now }

    @Test
    fun mintBearer_is256BitBase64Url_andHmacVerifier() {
        val mint = broker.mintBearerMaterial()
        val decoded = CryptoPrimitives.decodeBase64Url(mint.plaintext)
        assertNotNull(decoded)
        assertEquals(SecurityProfile.BEARER_TOKEN_BYTES, decoded!!.size)
        assertEquals(SecurityProfile.BEARER_TOKEN_VERIFIER, mint.verifierAlgorithm)
        assertEquals(32, mint.verifier.size)
        assertEquals(broker.activeTokenKeyVersion(), mint.verifierKeyVersion)

        val again = broker.computeTokenVerifier(mint.plaintext)!!
        assertTrue(CryptoPrimitives.constantTimeEquals(mint.verifier, again))
        assertFalse(
            CryptoPrimitives.constantTimeEquals(
                mint.verifier,
                broker.computeTokenVerifier(mint.plaintext + "x") ?: ByteArray(32),
            ),
        )
    }

    @Test
    fun oneTimePlaintext_displayOnceThenFailClosed() {
        val mint = broker.mintBearerMaterial()
        val key = InMemorySecretBroker.newIssuanceKey()
        val staged = broker.stageOneTimePlaintext(
            issuanceKey = key,
            tokenId = "tok-1",
            plaintext = mint.plaintext,
            expiresAtEpochMs = now + 60_000L,
        ) as OmniResult.Ok
        assertFalse(staged.value.consumed)

        val first = broker.takePlaintextOnce(key, now) as OmniResult.Ok
        assertEquals(mint.plaintext, first.value)

        val second = broker.takePlaintextOnce(key, now) as OmniResult.Err
        assertEquals(OmniErrorCode.STATE_CONFLICT, second.error.code)
    }

    @Test
    fun oneTimePlaintext_expired_failClosed() {
        val mint = broker.mintBearerMaterial()
        val key = InMemorySecretBroker.newIssuanceKey()
        broker.stageOneTimePlaintext(
            issuanceKey = key,
            tokenId = "tok-2",
            plaintext = mint.plaintext,
            expiresAtEpochMs = now + 1_000L,
        )
        now += 2_000L
        val err = broker.takePlaintextOnce(key, now) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, err.error.code)
    }

    @Test
    fun pairingSecret_192bit_encryptedAtRest_andProofRoundTrip() {
        val challengeId = "chal-1"
        val minted = broker.mintPairingSecret(challengeId, now + 300_000L) as OmniResult.Ok
        val secretBytes = CryptoPrimitives.decodeBase64Url(minted.value.plaintextBase64Url)!!
        assertEquals(SecurityProfile.PAIRING_SECRET_BYTES, secretBytes.size)

        val decrypted = broker.decryptRecord(minted.value.encrypted) as OmniResult.Ok
        assertArrayEquals(secretBytes, decrypted.value)

        val transcript = LanPairingTranscript(
            serverSpkiSha256 = "a".repeat(64),
            connectionEpoch = 1L,
            challengeId = challengeId,
            serverNonce = "nonce-b64",
            clientPublicKey = "client-pub",
            requestedScopes = setOf("inference.create", "models.read"),
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + 300_000L,
        )
        val proof = broker.computeLanPairingProof(transcript, secretBytes) as OmniResult.Ok
        assertTrue(
            broker.verifyLanPairingProof(transcript, secretBytes, proof.value) is OmniResult.Ok,
        )
        val bad = broker.verifyLanPairingProof(
            transcript,
            secretBytes,
            ByteArray(32) { 1 },
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.UNAUTHORIZED, bad.error.code)
    }

    @Test
    fun unknownPairingProtocol_failClosed() {
        val secret = CryptoPrimitives.randomPairingSecretBytes()
        val transcript = LanPairingTranscript(
            protocolLabel = "Unknown-Protocol",
            serverSpkiSha256 = "b".repeat(64),
            connectionEpoch = 0L,
            challengeId = "c",
            serverNonce = "n",
            clientPublicKey = "k",
            requestedScopes = setOf("inference.create"),
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + 1_000L,
        )
        val err = broker.computeLanPairingProof(transcript, secret) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, err.error.code)
    }

    @Test
    fun recordEncryption_aadBind_rejectsTamper() {
        val plain = "pairing-secret-bytes".toByteArray()
        val enc = broker.encryptRecord(
            recordType = InMemorySecretBroker.RECORD_TYPE_PAIRING_SECRET,
            recordId = "r1",
            plaintext = plain,
            expiresAtEpochMs = now + 60_000L,
        ) as OmniResult.Ok
        val ok = broker.decryptRecord(enc.value) as OmniResult.Ok
        assertArrayEquals(plain, ok.value)

        val tampered = enc.value.copy(recordId = "r2")
        val fail = broker.decryptRecord(tampered) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, fail.error.code)
    }

    @Test
    fun reportQueueEncryption_purposeIsolated_andCodecRoundTrip() {
        val plain = """{"reportId":"r-queue","category":"HATE_HARASSMENT"}""".toByteArray()
        val enc = broker.encryptRecord(
            recordType = InMemorySecretBroker.RECORD_TYPE_CONTENT_REPORT,
            recordId = "r-queue",
            plaintext = plain,
            expiresAtEpochMs = now + 86_400_000L,
            purpose = SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        ) as OmniResult.Ok
        // Wrong purpose key must fail closed.
        val wrongPurpose = broker.decryptRecord(
            enc.value,
            purpose = SecurityProfile.KeyPurpose.RECORD_ENCRYPTION,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, wrongPurpose.error.code)

        val ok = broker.decryptRecord(
            enc.value,
            purpose = SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        ) as OmniResult.Ok
        assertArrayEquals(plain, ok.value)

        val blob = EncryptedRecordCodec.encode(enc.value)
        assertTrue(EncryptedRecordCodec.isSealedEnvelope(blob))
        val decoded = EncryptedRecordCodec.decode(blob)!!
        val again = broker.decryptRecord(
            decoded,
            purpose = SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        ) as OmniResult.Ok
        assertArrayEquals(plain, again.value)
    }

    @Test
    fun keyRotation_changesVersion() {
        val before = broker.activeTokenKeyVersion()
        val rotated = broker.rotateKey(
            SecurityProfile.KeyPurpose.TOKEN_VERIFIER,
            reason = "compromise-test",
        ) as OmniResult.Ok
        assertNotEquals(before, rotated.value.keyVersion)
        assertEquals(SecurityProfile.KeyState.ACTIVE, rotated.value.state)
    }

    @Test
    fun securityProfile_constantsMatchSpec() {
        assertEquals("OMNILLM-SECURITY-PROFILE-2", SecurityProfile.PROFILE_ID)
        assertEquals(256, SecurityProfile.BEARER_TOKEN_BITS)
        assertEquals(192, SecurityProfile.PAIRING_SECRET_BITS)
        assertEquals(300, SecurityProfile.LAN_PAIRING_TTL_SECONDS)
        assertEquals(5, SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS)
        assertEquals("OmniLLM-LAN-Pairing-1", SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL)
        assertEquals(9, SecurityProfile.LAN_PAIRING_TRANSCRIPT_FIELDS.size)
    }
}
