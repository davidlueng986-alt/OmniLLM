package com.omnillm.core.ports.security

import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D14a: `:core:ports` security record port shapes — init/validation contracts
 * (AccessTokenRecord / PairingChallengeRecord / Revocation* / BrokerKeyMetadata /
 * EncryptedRecord). The records are the durable shape shared by
 * `:runtime:policy` and `:data:persistence` (ARC-01); failing validation must
 * fail closed at construction time.
 */
class CorePortsSecurityRecordsTest {

    private val hex64 = "a".repeat(64)
    private val verifier = ByteArray(32) { 1 }

    private fun token(
        state: String = "ACTIVE",
        verifier: ByteArray = this.verifier,
        scopes: Set<String> = setOf("inference.create"),
        revocationEpoch: Long = 0L,
        expiresAtEpochMs: Long = 2_000L,
        issuedAtEpochMs: Long = 1_000L,
        label: String? = "dev",
        clientId: String? = null,
    ) = AccessTokenRecord(
        tokenId = "tok-1",
        registrationId = "reg-1",
        principalId = "principal:u0",
        state = state,
        verifier = verifier,
        verifierAlgorithm = SecurityProfile.BEARER_TOKEN_VERIFIER,
        verifierKeyVersion = 1,
        transportConstraint = TransportConstraint.LOOPBACK_ONLY,
        scopes = scopes,
        revocationEpoch = revocationEpoch,
        issuedAtEpochMs = issuedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        label = label,
        clientId = clientId,
        updatedAtEpochMs = 1_000L,
    )

    // ----- AccessTokenRecord ------------------------------------------------

    @Test
    fun accessTokenRecord_validActiveStateConstructs() {
        val rec = token()
        assertEquals("ACTIVE", rec.state)
        assertTrue(StateMachines.TOKEN.isKnownState(rec.state))
        assertEquals(TransportConstraint.LOOPBACK_ONLY, rec.transportConstraint)
        assertTrue(rec.scopes.contains("inference.create"))
    }

    @Test
    fun accessTokenRecord_blankTokenIdRejected() {
        try {
            token().let { it.copy(tokenId = "  ") }
            fail("blank tokenId must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun accessTokenRecord_unknownStateRejected() {
        try {
            token(state = "NOT_A_STATE")
            fail("unknown TOKEN state must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown TOKEN state"))
        }
    }

    @Test
    fun accessTokenRecord_emptyVerifierRejected() {
        try {
            token(verifier = ByteArray(0))
            fail("empty verifier must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun accessTokenRecord_emptyScopesRejected() {
        try {
            token(scopes = emptySet())
            fail("empty scopes must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun accessTokenRecord_negativeRevocationEpochRejected() {
        try {
            token(revocationEpoch = -1L)
            fail("negative revocationEpoch must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun accessTokenRecord_expiryNotAfterIssueRejected() {
        try {
            token(expiresAtEpochMs = 1_000L, issuedAtEpochMs = 1_000L)
            fail("expiresAtEpochMs <= issuedAtEpochMs must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun accessTokenRecord_metadataStripsVerifierMaterial() {
        val rec = token(label = "lan-device", clientId = "lan-client-1")
        val meta = rec.metadata()
        assertEquals(rec.tokenId, meta.tokenId)
        assertEquals(rec.registrationId, meta.registrationId)
        assertEquals(rec.principalId, meta.principalId)
        assertEquals(rec.state, meta.state)
        assertEquals(rec.scopes, meta.scopes)
        assertEquals(rec.revocationEpoch, meta.revocationEpoch)
        assertEquals("lan-device", meta.label)
        assertEquals("lan-client-1", meta.clientId)
        assertEquals(rec.lastSeenAtEpochMs, meta.lastSeenAtEpochMs)
        // Metadata must never carry the verifier or algorithm/version material.
        val fields = meta.javaClass.declaredFields.map { it.name }
        assertFalse(fields.contains("verifier"))
        assertFalse(fields.contains("verifierAlgorithm"))
        assertFalse(fields.contains("verifierKeyVersion"))
    }

    @Test
    fun transportConstraint_loopbackNeverAcceptedForLanOnly() {
        assertFalse(TransportConstraint.LAN_ONLY == TransportConstraint.LOOPBACK_ONLY)
        assertEquals("LOOPBACK_ONLY", TransportConstraint.LOOPBACK_ONLY.name)
        assertEquals("LAN_ONLY", TransportConstraint.LAN_ONLY.name)
    }

    // ----- BrokerKeyMetadata / EncryptedRecord ------------------------------

    @Test
    fun brokerKeyMetadata_carriesPurposeStateAndRotation() {
        val meta = BrokerKeyMetadata(
            purpose = SecurityProfile.KeyPurpose.TOKEN_VERIFIER,
            keyVersion = 3,
            state = SecurityProfile.KeyState.ACTIVE,
            createdAtEpochMs = 1_000L,
            rotationReason = "scheduled",
        )
        assertEquals(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, meta.purpose)
        assertEquals(SecurityProfile.KeyState.ACTIVE, meta.state)
        assertEquals(3, meta.keyVersion)
        assertEquals("scheduled", meta.rotationReason)
    }

    @Test
    fun encryptedRecord_holdsNonceAndCiphertextWithoutPlaintext() {
        val rec = EncryptedRecord(
            profileId = SecurityProfile.PROFILE_ID,
            recordType = "pairing-secret",
            recordId = "challenge-1",
            schemaVersion = SecurityProfile.SCHEMA_VERSION,
            keyVersion = 1,
            expiresAtEpochMs = 2_000L,
            nonce = ByteArray(SecurityProfile.GCM_NONCE_BYTES) { 7 },
            ciphertext = ByteArray(16) { 9 },
        )
        assertEquals(12, rec.nonce.size) // 96-bit GCM nonce
        assertEquals("AES-256-GCM", SecurityProfile.RECORD_ENCRYPTION)
        assertTrue(rec.ciphertext.contentEquals(ByteArray(16) { 9 }))
    }

    @Test
    fun encryptedRecord_byteArrayEqualityIsReferenceBased() {
        // Persistence ports compare fields (never the record as a key) — pin the
        // documented reference-equality semantics so a future change is deliberate.
        val a = EncryptedRecord(
            profileId = "p", recordType = "t", recordId = "r", schemaVersion = 1,
            keyVersion = 1, expiresAtEpochMs = 1L, nonce = byteArrayOf(1), ciphertext = byteArrayOf(2),
        )
        val b = EncryptedRecord(
            profileId = "p", recordType = "t", recordId = "r", schemaVersion = 1,
            keyVersion = 1, expiresAtEpochMs = 1L, nonce = byteArrayOf(1), ciphertext = byteArrayOf(2),
        )
        assertFalse("data-class ByteArray equality is reference-based", a == b)
    }

    // ----- PairingChallengeRecord ------------------------------------------

    private fun lanChallenge(
        state: String = "PENDING",
        secretEncrypted: EncryptedRecord? = EncryptedRecord(
            profileId = SecurityProfile.PROFILE_ID,
            recordType = "pairing-secret",
            recordId = "enc-1",
            schemaVersion = 1,
            keyVersion = 1,
            expiresAtEpochMs = 2_000L,
            nonce = ByteArray(12),
            ciphertext = ByteArray(16),
        ),
        protocolLabel: String? = SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL,
        spki: String? = hex64,
        connectionEpoch: Long? = 1L,
        serverNonceBase64Url: String? = "bm9uY2U",
        attempts: Int = 5,
        scopes: Set<String> = setOf("inference.create"),
    ) = PairingChallengeRecord(
        challengeId = "challenge-1",
        kind = ChallengeKind.LAN_HMAC,
        state = state,
        principalId = null,
        observedUid = null,
        androidUserId = null,
        protocolLabel = protocolLabel,
        requestedScopes = scopes,
        serverSpkiSha256 = spki,
        connectionEpoch = connectionEpoch,
        serverNonceBase64Url = serverNonceBase64Url,
        secretEncrypted = secretEncrypted,
        attemptsRemaining = attempts,
        expiresAtEpochMs = 2_000L,
        approvedAtEpochMs = null,
        consumedAtEpochMs = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        secretPlaintextOnce = null,
    )

    @Test
    fun lanHmacChallenge_liveStatesRequireEncryptedSecret() {
        val pending = lanChallenge(state = "PENDING")
        assertEquals("PENDING", pending.state)
        val approved = lanChallenge(state = "APPROVED")
        assertEquals("APPROVED", approved.state)

        // Live state without encrypted secret must fail closed.
        for (live in listOf("PENDING", "APPROVED")) {
            try {
                lanChallenge(state = live, secretEncrypted = null)
                fail("$live LAN_HMAC without encrypted secret must fail closed")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("LAN_HMAC live challenge"))
            }
        }
        // Terminal state may drop the secret.
        val consumed = lanChallenge(state = "CONSUMED", secretEncrypted = null)
        assertEquals("CONSUMED", consumed.state)
    }

    @Test
    fun lanHmacChallenge_wrongProtocolLabelRejected() {
        try {
            lanChallenge(protocolLabel = "OmniLLM-LAN-Pairing-2")
            fail("wrong protocol label must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun lanHmacChallenge_invalidSpkiRejected() {
        try {
            lanChallenge(spki = "zz".repeat(32))
            fail("non-hex64 SPKI must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun lanHmacChallenge_negativeEpochOrMissingNonceRejected() {
        try {
            lanChallenge(connectionEpoch = -1L)
            fail("negative connectionEpoch must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            lanChallenge(serverNonceBase64Url = null)
            fail("missing serverNonce must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun lanHmacChallenge_attemptsOutOfRangeRejected() {
        try {
            lanChallenge(attempts = SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS + 1)
            fail("attempts > max must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        val zero = lanChallenge(attempts = 0)
        assertEquals(0, zero.attemptsRemaining)
    }

    @Test
    fun lanHmacChallenge_emptyRequestedScopesRejected() {
        try {
            lanChallenge(scopes = emptySet())
            fail("empty requestedScopes must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun aidlRegistrationChallenge_requiresObservedPrincipal() {
        val rec = PairingChallengeRecord(
            challengeId = "ch-aidl",
            kind = ChallengeKind.AIDL_REGISTRATION,
            state = "PENDING",
            principalId = "principal:u0",
            observedUid = 10_000,
            androidUserId = 0,
            protocolLabel = null,
            requestedScopes = setOf("inference.read-own"),
            serverSpkiSha256 = null,
            connectionEpoch = null,
            serverNonceBase64Url = null,
            secretEncrypted = null,
            attemptsRemaining = 5,
            expiresAtEpochMs = 2_000L,
            approvedAtEpochMs = null,
            consumedAtEpochMs = null,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            secretPlaintextOnce = null,
        )
        assertEquals(ChallengeKind.AIDL_REGISTRATION, rec.kind)
        assertEquals(10_000, rec.observedUid)

        // AIDL never carries an encrypted secret; missing UID fails closed.
        try {
            rec.copy(secretEncrypted = EncryptedRecord(
                profileId = "p", recordType = "t", recordId = "r", schemaVersion = 1,
                keyVersion = 1, expiresAtEpochMs = 1L, nonce = ByteArray(1), ciphertext = ByteArray(1),
            ))
            fail("AIDL challenge with secret must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            rec.copy(observedUid = null)
            fail("AIDL challenge without observedUid must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            rec.copy(androidUserId = null)
            fail("AIDL challenge without androidUserId must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun pairingChallenge_unknownStateRejected() {
        try {
            lanChallenge(state = "BOGUS")
            fail("unknown PAIRING_CHALLENGE state must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown PAIRING_CHALLENGE state"))
        }
    }

    // ----- Revocation -------------------------------------------------------

    @Test
    fun revocationScope_blankSubjectRejected() {
        try {
            RevocationScope(subjectId = "", kind = RevocationSubjectKind.TOKEN)
            fail("blank subjectId must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun revocationScope_storageKeyIsKindNullSeparatorSubject() {
        val scope = RevocationScope(subjectId = "tok-1", kind = RevocationSubjectKind.TOKEN)
        assertEquals("TOKEN\u0000tok-1", scope.storageKey())
        // Distinct kinds with the same id must not collide.
        assertFalse(
            RevocationScope("tok-1", RevocationSubjectKind.TOKEN).storageKey() ==
                RevocationScope("tok-1", RevocationSubjectKind.PRINCIPAL).storageKey(),
        )
    }

    @Test
    fun revocationRecord_validAndValidation() {
        val rec = RevocationRecord(
            scope = RevocationScope("principal:u0", RevocationSubjectKind.PRINCIPAL),
            epoch = 7L,
            state = "ACTIVE",
            reason = "leak",
            actorPrincipalId = "local-ui",
            updatedAtEpochMs = 1_000L,
        )
        assertEquals(7L, rec.epoch)
        try {
            rec.copy(epoch = -1L)
            fail("negative epoch must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
        try {
            rec.copy(state = "BOGUS")
            fail("unknown REVOCATION state must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown REVOCATION state"))
        }
    }

    // ----- SecurityProfile pins ---------------------------------------------

    @Test
    fun securityProfile_pinnedAlgorithmsAndConstants() {
        assertEquals("OMNILLM-SECURITY-PROFILE-2", SecurityProfile.PROFILE_ID)
        assertTrue(SecurityProfile.requireKnownProfileId("OMNILLM-SECURITY-PROFILE-2"))
        assertFalse(SecurityProfile.requireKnownProfileId("OMNILLM-SECURITY-PROFILE-1"))
        assertTrue(SecurityProfile.requireKnownVerifierAlgorithm("HMAC-SHA-256"))
        assertFalse(SecurityProfile.requireKnownVerifierAlgorithm("MD5"))
        assertTrue(SecurityProfile.requireKnownPairingProtocol("OmniLLM-LAN-Pairing-1"))
        assertFalse(SecurityProfile.requireKnownPairingProtocol("pairing-v1"))
        assertEquals(256, SecurityProfile.BEARER_TOKEN_BITS)
        assertEquals(192, SecurityProfile.PAIRING_SECRET_BITS)
        assertEquals(300, SecurityProfile.LAN_PAIRING_TTL_SECONDS)
        assertEquals(5, SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS)
        assertEquals(
            "protocolLabel",
            SecurityProfile.LAN_PAIRING_TRANSCRIPT_FIELDS.first(),
        )
        assertEquals(24, SecurityProfile.PAIRING_SECRET_BYTES) // 192-bit
        assertEquals(12, SecurityProfile.GCM_NONCE_BYTES) // 96-bit
        assertEquals(32, SecurityProfile.SECRET_KEY_BYTES) // 256-bit
    }
}
