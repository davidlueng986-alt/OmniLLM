package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret Broker (SEC-AUTH-NET §5, SEC-PROFILE §5, process-trust-topology §4).
 *
 * Lives only in the runtime control plane (ADR-010). Workers / UI / companion
 * never receive general Keystore aliases, token plaintext, or pairing secrets.
 *
 * Responsibilities:
 * - Manage purpose-scoped keys (token-verifier, record encryption, TLS ref)
 * - Issue / verify bearer tokens (256-bit CSPRNG, HMAC-SHA-256 verifier only)
 * - One-time plaintext display path (bounded issuance receipt)
 * - Encrypt pending pairing secrets (AES-256-GCM)
 * - Compute / verify channel-bound LAN pairing proofs
 *
 * Android Keystore non-exportable binding is a platform adapter; this class is
 * the portable control-plane authority with in-process keys for JVM tests and
 * bootstrap until the Android Keystore adapter attaches.
 */
interface SecretBroker {
    fun activeKey(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial?

    fun rotateKey(
        purpose: SecurityProfile.KeyPurpose,
        reason: String,
    ): OmniResult<BrokerKeyMaterial>

    /** Generate bearer plaintext (base64url) + HMAC verifier. Does not store. */
    fun mintBearerMaterial(): BearerMint

    /** HMAC-SHA-256 verifier for presented bearer plaintext (constant-time compare ready). */
    fun computeTokenVerifier(plaintext: String, keyVersion: Int = activeTokenKeyVersion()): ByteArray?

    fun activeTokenKeyVersion(): Int

    /**
     * Stage a one-time plaintext display receipt. After [takePlaintextOnce],
     * the secret is wiped and cannot be recovered from the broker.
     */
    fun stageOneTimePlaintext(
        issuanceKey: String,
        tokenId: String,
        plaintext: String,
        expiresAtEpochMs: Long,
    ): OmniResult<OneTimePlaintextReceipt>

    fun takePlaintextOnce(issuanceKey: String, nowEpochMs: Long): OmniResult<String>

    fun peekPlaintextReceipt(issuanceKey: String): OneTimePlaintextReceipt?

    fun erasePlaintextReceipt(issuanceKey: String)

    /**
     * Encrypt a classified secret record (pairing secret, TLS key wrap, etc.).
     *
     * @param purpose defaults to [SecurityProfile.KeyPurpose.RECORD_ENCRYPTION].
     *   Content-report draft queue **must** use [SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION]
     *   (FEAT-AI-CONTENT-REPORT §6 / SEC-PROFILE).
     */
    fun encryptRecord(
        recordType: String,
        recordId: String,
        plaintext: ByteArray,
        expiresAtEpochMs: Long,
        schemaVersion: Int = SecurityProfile.SCHEMA_VERSION,
        purpose: SecurityProfile.KeyPurpose = SecurityProfile.KeyPurpose.RECORD_ENCRYPTION,
    ): OmniResult<EncryptedRecord>

    fun decryptRecord(
        record: EncryptedRecord,
        purpose: SecurityProfile.KeyPurpose = SecurityProfile.KeyPurpose.RECORD_ENCRYPTION,
    ): OmniResult<ByteArray>

    /** Mint a 192-bit pairing secret and encrypt it for durable storage. */
    fun mintPairingSecret(
        challengeId: String,
        expiresAtEpochMs: Long,
    ): OmniResult<MintedPairingSecret>

    /**
     * Canonical LAN pairing transcript HMAC-SHA-256 proof
     * (SEC-PROFILE lanPairing.transcriptFields + pairingProof).
     */
    fun computeLanPairingProof(
        transcript: LanPairingTranscript,
        pairingSecret: ByteArray,
    ): OmniResult<ByteArray>

    fun verifyLanPairingProof(
        transcript: LanPairingTranscript,
        pairingSecret: ByteArray,
        presentedProof: ByteArray,
    ): OmniResult<Unit>
}

data class BrokerKeyMaterial(
    val purpose: SecurityProfile.KeyPurpose,
    val keyVersion: Int,
    val state: SecurityProfile.KeyState,
    val createdAtEpochMs: Long,
    val rotationReason: String?,
    /** Raw key bytes — never leave Secret Broker / control plane. */
    internal val keyBytes: ByteArray,
) {
    init {
        require(keyVersion > 0)
        require(keyBytes.size == SecurityProfile.SECRET_KEY_BYTES)
    }

    /** Public metadata only — no key material. */
    fun metadata(): BrokerKeyMetadata =
        BrokerKeyMetadata(purpose, keyVersion, state, createdAtEpochMs, rotationReason)
}

data class BrokerKeyMetadata(
    val purpose: SecurityProfile.KeyPurpose,
    val keyVersion: Int,
    val state: SecurityProfile.KeyState,
    val createdAtEpochMs: Long,
    val rotationReason: String?,
)

data class BearerMint(
    /** base64url-without-padding 256-bit secret. */
    val plaintext: String,
    /** HMAC-SHA-256 verifier bytes for durable storage. */
    val verifier: ByteArray,
    val verifierAlgorithm: String = SecurityProfile.BEARER_TOKEN_VERIFIER,
    val verifierKeyVersion: Int,
) {
    init {
        require(plaintext.isNotBlank())
        require(verifier.size == 32)
        require(verifierKeyVersion > 0)
    }
}

/**
 * One-time display path (SEC-AUTH-NET §2 / SEC-PROFILE §3).
 * Plaintext is shown once to local UI, then erased.
 */
data class OneTimePlaintextReceipt(
    val issuanceKey: String,
    val tokenId: String,
    val expiresAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val acknowledgedAtEpochMs: Long?,
    /** True when plaintext has already been taken or wiped. */
    val consumed: Boolean,
)

data class EncryptedRecord(
    val profileId: String,
    val recordType: String,
    val recordId: String,
    val schemaVersion: Int,
    val keyVersion: Int,
    val expiresAtEpochMs: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

data class MintedPairingSecret(
    val challengeId: String,
    /** Plaintext only returned once to the create-challenge path for QR/display. */
    val plaintextBase64Url: String,
    val encrypted: EncryptedRecord,
)

/**
 * Channel-bound LAN pairing transcript fields (security-profile.yaml lanPairing).
 * Field set is closed; unknown schema version fails closed.
 */
data class LanPairingTranscript(
    val protocolLabel: String = SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL,
    val serverSpkiSha256: String,
    val connectionEpoch: Long,
    val challengeId: String,
    /** Server nonce as base64url (challenge binding material). */
    val serverNonce: String,
    /** Client public key encoding (raw base64url or digest hex as presented). */
    val clientPublicKey: String,
    /** Sorted unique scope ids joined by ',' for canonical transcript. */
    val requestedScopes: Set<String>,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val transcriptSchemaVersion: Int = SecurityProfile.LAN_PAIRING_TRANSCRIPT_SCHEMA_VERSION,
) {
    init {
        require(serverSpkiSha256.matches(HEX64)) { "serverSpkiSha256 must be lower-case hex SHA-256" }
        require(connectionEpoch >= 0L)
        require(challengeId.isNotBlank())
        require(serverNonce.isNotBlank())
        require(clientPublicKey.isNotBlank())
        require(requestedScopes.isNotEmpty())
        require(expiresAtEpochMs > issuedAtEpochMs)
    }

    /**
     * Canonical UTF-8 transcript: field order from profile, LF-separated
     * `name=value` lines. Scopes sorted for total order.
     */
    fun canonicalBytes(): ByteArray {
        val scopes = requestedScopes.toSortedSet().joinToString(",")
        val lines = listOf(
            "protocolLabel=$protocolLabel",
            "serverSpkiSha256=$serverSpkiSha256",
            "connectionEpoch=$connectionEpoch",
            "challengeId=$challengeId",
            "serverNonce=$serverNonce",
            "clientPublicKey=$clientPublicKey",
            "requestedScopes=$scopes",
            "issuedAt=$issuedAtEpochMs",
            "expiresAt=$expiresAtEpochMs",
        )
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Vault-backed Secret Broker (SEC-AUTH-NET §5 / SEC-PROFILE §5).
 *
 * Crypto authority for tokens, pairing, and record encryption. Key material is
 * supplied by [SecretKeyVault] (in-memory, encrypted-blob durable, or Android
 * Keystore). One-time plaintext receipts stay process-local (bounded TTL);
 * durable token **verifiers** live in [AccessTokenStore] / SQLite.
 *
 * Production control plane: Keystore or encrypted-blob vault + SQLite verifiers.
 * JVM unit tests: [InMemorySecretBroker] alias.
 */
class VaultSecretBroker(
    private val vault: SecretKeyVault,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    bootstrapKeys: Boolean = true,
) : SecretBroker {

    private val plaintextReceipts = ConcurrentHashMap<String, StagedPlaintext>()
    private val lock = Any()

    init {
        if (bootstrapKeys) {
            for (purpose in BOOTSTRAP_PURPOSES) {
                if (vault.active(purpose) == null) {
                    when (val r = vault.installNew(purpose, reason = "bootstrap", retirePrevious = false)) {
                        is OmniResult.Err -> error("Secret Broker bootstrap failed for $purpose: ${r.error.message}")
                        is OmniResult.Ok -> Unit
                    }
                }
            }
        }
    }

    override fun activeKey(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial? =
        vault.active(purpose)

    override fun rotateKey(
        purpose: SecurityProfile.KeyPurpose,
        reason: String,
    ): OmniResult<BrokerKeyMaterial> =
        vault.installNew(purpose, reason = reason, retirePrevious = true)

    override fun mintBearerMaterial(): BearerMint {
        val key = requireActive(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)
        val raw = CryptoPrimitives.randomBearerTokenBytes()
        val plaintext = CryptoPrimitives.encodeBase64Url(raw)
        CryptoPrimitives.wipe(raw)
        val verifier = CryptoPrimitives.hmacSha256(key.keyBytes, plaintext)
        return BearerMint(
            plaintext = plaintext,
            verifier = verifier,
            verifierKeyVersion = key.keyVersion,
        )
    }

    override fun computeTokenVerifier(plaintext: String, keyVersion: Int): ByteArray? {
        if (plaintext.isBlank()) return null
        // Prefer exact version (ACTIVE or RETIRED) for rotation overlap verify.
        val material = vault.get(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, keyVersion)
            ?: vault.active(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)?.takeIf { it.keyVersion == keyVersion }
            ?: return null
        if (material.state == SecurityProfile.KeyState.REVOKED) return null
        return CryptoPrimitives.hmacSha256(material.keyBytes, plaintext)
    }

    override fun activeTokenKeyVersion(): Int =
        requireActive(SecurityProfile.KeyPurpose.TOKEN_VERIFIER).keyVersion

    override fun stageOneTimePlaintext(
        issuanceKey: String,
        tokenId: String,
        plaintext: String,
        expiresAtEpochMs: Long,
    ): OmniResult<OneTimePlaintextReceipt> {
        if (issuanceKey.isBlank() || tokenId.isBlank() || plaintext.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "issuance receipt fields required"))
        }
        val now = clockMs()
        if (expiresAtEpochMs <= now) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "receipt expiry must be in the future"))
        }
        val staged = StagedPlaintext(
            receipt = OneTimePlaintextReceipt(
                issuanceKey = issuanceKey,
                tokenId = tokenId,
                expiresAtEpochMs = expiresAtEpochMs,
                createdAtEpochMs = now,
                acknowledgedAtEpochMs = null,
                consumed = false,
            ),
            plaintext = plaintext,
        )
        plaintextReceipts[issuanceKey] = staged
        return OmniResult.ok(staged.receipt)
    }

    override fun takePlaintextOnce(issuanceKey: String, nowEpochMs: Long): OmniResult<String> {
        synchronized(lock) {
            val staged = plaintextReceipts[issuanceKey]
                ?: return OmniResult.err(OmniError.NOT_FOUND(message = "issuance receipt not found"))
            if (staged.receipt.consumed || staged.plaintext == null) {
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "plaintext already displayed or erased"),
                )
            }
            if (nowEpochMs >= staged.receipt.expiresAtEpochMs) {
                wipeStaged(issuanceKey)
                return OmniResult.err(OmniError.FORBIDDEN(message = "issuance receipt expired"))
            }
            val plain = staged.plaintext!!
            plaintextReceipts[issuanceKey] = staged.copy(
                receipt = staged.receipt.copy(
                    consumed = true,
                    acknowledgedAtEpochMs = nowEpochMs,
                ),
                plaintext = null,
            )
            return OmniResult.ok(plain)
        }
    }

    override fun peekPlaintextReceipt(issuanceKey: String): OneTimePlaintextReceipt? =
        plaintextReceipts[issuanceKey]?.receipt

    override fun erasePlaintextReceipt(issuanceKey: String) {
        wipeStaged(issuanceKey)
    }

    override fun encryptRecord(
        recordType: String,
        recordId: String,
        plaintext: ByteArray,
        expiresAtEpochMs: Long,
        schemaVersion: Int,
        purpose: SecurityProfile.KeyPurpose,
    ): OmniResult<EncryptedRecord> {
        if (recordType.isBlank() || recordId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "recordType/recordId required"))
        }
        if (plaintext.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "plaintext empty"))
        }
        if (purpose != SecurityProfile.KeyPurpose.RECORD_ENCRYPTION &&
            purpose != SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "encryptRecord purpose not allowed (fail closed)",
                    details = mapOf("purpose" to purpose.name),
                ),
            )
        }
        val key = requireActive(purpose)
        val nonce = CryptoPrimitives.randomGcmNonce()
        val aad = aadBytes(
            profileId = SecurityProfile.PROFILE_ID,
            recordType = recordType,
            recordId = recordId,
            schemaVersion = schemaVersion,
            keyVersion = key.keyVersion,
            expiresAtEpochMs = expiresAtEpochMs,
            purpose = purpose,
        )
        val ct = CryptoPrimitives.aesGcmEncrypt(key.keyBytes, nonce, plaintext, aad)
        return OmniResult.ok(
            EncryptedRecord(
                profileId = SecurityProfile.PROFILE_ID,
                recordType = recordType,
                recordId = recordId,
                schemaVersion = schemaVersion,
                keyVersion = key.keyVersion,
                expiresAtEpochMs = expiresAtEpochMs,
                nonce = nonce,
                ciphertext = ct,
            ),
        )
    }

    override fun decryptRecord(
        record: EncryptedRecord,
        purpose: SecurityProfile.KeyPurpose,
    ): OmniResult<ByteArray> {
        if (!SecurityProfile.requireKnownProfileId(record.profileId)) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "unknown security profile (fail closed)"),
            )
        }
        if (purpose != SecurityProfile.KeyPurpose.RECORD_ENCRYPTION &&
            purpose != SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "decryptRecord purpose not allowed (fail closed)",
                    details = mapOf("purpose" to purpose.name),
                ),
            )
        }
        if (clockMs() >= record.expiresAtEpochMs) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "encrypted record expired"))
        }
        val key = vault.get(purpose, record.keyVersion)
            ?: return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "record encryption key unavailable",
                    details = mapOf(
                        "keyVersion" to record.keyVersion.toString(),
                        "purpose" to purpose.name,
                    ),
                ),
            )
        if (key.state == SecurityProfile.KeyState.REVOKED) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "encryption key revoked"))
        }
        val aad = aadBytes(
            profileId = record.profileId,
            recordType = record.recordType,
            recordId = record.recordId,
            schemaVersion = record.schemaVersion,
            keyVersion = record.keyVersion,
            expiresAtEpochMs = record.expiresAtEpochMs,
            purpose = purpose,
        )
        val plain = CryptoPrimitives.aesGcmDecrypt(key.keyBytes, record.nonce, record.ciphertext, aad)
            ?: return OmniResult.err(OmniError.FORBIDDEN(message = "record decryption failed"))
        return OmniResult.ok(plain)
    }

    override fun mintPairingSecret(
        challengeId: String,
        expiresAtEpochMs: Long,
    ): OmniResult<MintedPairingSecret> {
        if (challengeId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "challengeId required"))
        }
        val raw = CryptoPrimitives.randomPairingSecretBytes()
        val plaintextB64 = CryptoPrimitives.encodeBase64Url(raw)
        return when (
            val enc = encryptRecord(
                recordType = RECORD_TYPE_PAIRING_SECRET,
                recordId = challengeId,
                plaintext = raw,
                expiresAtEpochMs = expiresAtEpochMs,
            )
        ) {
            is OmniResult.Err -> {
                CryptoPrimitives.wipe(raw)
                enc
            }
            is OmniResult.Ok -> {
                CryptoPrimitives.wipe(raw)
                OmniResult.ok(
                    MintedPairingSecret(
                        challengeId = challengeId,
                        plaintextBase64Url = plaintextB64,
                        encrypted = enc.value,
                    ),
                )
            }
        }
    }

    override fun computeLanPairingProof(
        transcript: LanPairingTranscript,
        pairingSecret: ByteArray,
    ): OmniResult<ByteArray> {
        val err = validateTranscript(transcript)
        if (err != null) return OmniResult.err(err)
        if (pairingSecret.size != SecurityProfile.PAIRING_SECRET_BYTES) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "pairing secret must be ${SecurityProfile.PAIRING_SECRET_BITS}-bit",
                ),
            )
        }
        val key = CryptoPrimitives.sha256(pairingSecret)
        val mac = CryptoPrimitives.hmacSha256(key, transcript.canonicalBytes())
        CryptoPrimitives.wipe(key)
        return OmniResult.ok(mac)
    }

    override fun verifyLanPairingProof(
        transcript: LanPairingTranscript,
        pairingSecret: ByteArray,
        presentedProof: ByteArray,
    ): OmniResult<Unit> {
        return when (val expected = computeLanPairingProof(transcript, pairingSecret)) {
            is OmniResult.Err -> expected
            is OmniResult.Ok -> {
                if (!CryptoPrimitives.constantTimeEquals(expected.value, presentedProof)) {
                    OmniResult.err(OmniError.UNAUTHORIZED(message = "pairing proof invalid"))
                } else {
                    OmniResult.ok(Unit)
                }
            }
        }
    }

    private fun requireActive(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial =
        vault.active(purpose)
            ?: error("Secret Broker missing ACTIVE key for $purpose")

    private fun wipeStaged(issuanceKey: String) {
        synchronized(lock) {
            plaintextReceipts.remove(issuanceKey)
        }
    }

    private fun validateTranscript(t: LanPairingTranscript): OmniError? {
        if (t.transcriptSchemaVersion != SecurityProfile.LAN_PAIRING_TRANSCRIPT_SCHEMA_VERSION) {
            return OmniError.FORBIDDEN(
                message = "unknown pairing transcript schema (fail closed)",
                details = mapOf("schemaVersion" to t.transcriptSchemaVersion.toString()),
            )
        }
        if (!SecurityProfile.requireKnownPairingProtocol(t.protocolLabel)) {
            return OmniError.FORBIDDEN(
                message = "unknown pairing protocol (fail closed)",
                details = mapOf("protocolLabel" to t.protocolLabel),
            )
        }
        return null
    }

    private fun aadBytes(
        profileId: String,
        recordType: String,
        recordId: String,
        schemaVersion: Int,
        keyVersion: Int,
        expiresAtEpochMs: Long,
        purpose: SecurityProfile.KeyPurpose = SecurityProfile.KeyPurpose.RECORD_ENCRYPTION,
    ): ByteArray {
        // RECORD_ENCRYPTION omits purpose= for wire compatibility with pre-purpose AAD
        // (pairing / TLS wrap). REPORT_QUEUE_ENCRYPTION binds purpose explicitly.
        val lines = mutableListOf(
            "profileId=$profileId",
            "recordType=$recordType",
            "recordId=$recordId",
            "schemaVersion=$schemaVersion",
            "keyVersion=$keyVersion",
            "expiresAt=$expiresAtEpochMs",
        )
        if (purpose != SecurityProfile.KeyPurpose.RECORD_ENCRYPTION) {
            lines.add("purpose=${purpose.name}")
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private data class StagedPlaintext(
        val receipt: OneTimePlaintextReceipt,
        val plaintext: String?,
    )

    companion object {
        const val RECORD_TYPE_PAIRING_SECRET: String = "LAN_PAIRING_SECRET"
        const val RECORD_TYPE_TOKEN_ISSUANCE: String = "TOKEN_ISSUANCE_RECEIPT"
        const val RECORD_TYPE_CONTENT_REPORT: String = "AI_CONTENT_REPORT_DRAFT"

        val BOOTSTRAP_PURPOSES: List<SecurityProfile.KeyPurpose> = listOf(
            SecurityProfile.KeyPurpose.TOKEN_VERIFIER,
            SecurityProfile.KeyPurpose.RECORD_ENCRYPTION,
            SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        )

        fun newIssuanceKey(): String = UUID.randomUUID().toString()
    }
}

/**
 * In-process Secret Broker (JVM unit tests / ephemeral bootstrap).
 * Delegates to [VaultSecretBroker] + [InMemorySecretKeyVault].
 */
class InMemorySecretBroker(
    clockMs: () -> Long = { System.currentTimeMillis() },
) : SecretBroker by VaultSecretBroker(
    vault = InMemorySecretKeyVault(clockMs),
    clockMs = clockMs,
    bootstrapKeys = true,
) {
    companion object {
        const val RECORD_TYPE_PAIRING_SECRET: String = VaultSecretBroker.RECORD_TYPE_PAIRING_SECRET
        const val RECORD_TYPE_TOKEN_ISSUANCE: String = VaultSecretBroker.RECORD_TYPE_TOKEN_ISSUANCE
        const val RECORD_TYPE_CONTENT_REPORT: String = VaultSecretBroker.RECORD_TYPE_CONTENT_REPORT

        fun newIssuanceKey(): String = VaultSecretBroker.newIssuanceKey()
    }
}
