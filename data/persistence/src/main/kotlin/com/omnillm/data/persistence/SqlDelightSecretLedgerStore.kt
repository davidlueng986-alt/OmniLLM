package com.omnillm.data.persistence

import com.omnillm.runtime.policy.RevocationEpochStore
import com.omnillm.runtime.policy.RevocationRecord
import com.omnillm.runtime.policy.RevocationScope
import com.omnillm.runtime.policy.RevocationSubjectKind
import com.omnillm.runtime.policy.security.AccessTokenStore
import com.omnillm.runtime.policy.security.BrokerKeyMetadata
import com.omnillm.runtime.policy.security.EncryptedKeyBlobStore
import com.omnillm.runtime.policy.security.EncryptedRecord
import com.omnillm.runtime.policy.security.PairingChallengeService
import com.omnillm.runtime.policy.security.PairingChallengeStore
import com.omnillm.runtime.policy.security.SecurityProfile
import com.omnillm.runtime.policy.security.TokenService

/**
 * SQLDelight-backed secret / token / pairing / revocation ledgers (ADR-010).
 *
 * DB stores HMAC verifiers and encrypted pairing secrets only — never bearer
 * plaintext (SEC-AUTH-NET §2 / SEC-PROFILE §3).
 *
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 */
class SqlDelightSecretLedgerStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ControlPlaneWriter {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    val accessTokens: AccessTokenStore = object : AccessTokenStore {
        override fun get(tokenId: String): TokenService.AccessTokenRecord? =
            database.accessTokensQueries.selectByTokenId(tokenId).executeAsOneOrNull()?.toRecord()

        override fun listAll(): List<TokenService.AccessTokenRecord> =
            database.accessTokensQueries.listAll().executeAsList().map { it.toRecord() }

        override fun upsert(record: TokenService.AccessTokenRecord) {
            database.accessTokensQueries.upsertToken(
                token_id = record.tokenId,
                registration_id = record.registrationId,
                principal_id = record.principalId,
                state = record.state,
                verifier = record.verifier,
                verifier_algorithm = record.verifierAlgorithm,
                verifier_key_version = record.verifierKeyVersion.toLong(),
                transport_constraint = record.transportConstraint.name,
                scope_json = encodeScopes(record.scopes),
                revocation_epoch = record.revocationEpoch,
                issued_at_epoch_ms = record.issuedAtEpochMs,
                expires_at_epoch_ms = record.expiresAtEpochMs,
                last_seen_at_epoch_ms = record.lastSeenAtEpochMs,
                label = record.label,
                client_id = record.clientId,
                updated_at_epoch_ms = record.updatedAtEpochMs,
            )
        }

        override fun delete(tokenId: String): Boolean {
            if (get(tokenId) == null) return false
            database.accessTokensQueries.deleteByTokenId(tokenId)
            return true
        }
    }

    val pairingChallenges: PairingChallengeStore = object : PairingChallengeStore {
        override fun get(challengeId: String): PairingChallengeService.PairingChallengeRecord? =
            database.pairingChallengesQueries
                .selectByChallengeId(challengeId)
                .executeAsOneOrNull()
                ?.toRecord()

        override fun listAll(): List<PairingChallengeService.PairingChallengeRecord> =
            database.pairingChallengesQueries.listAll().executeAsList().map { it.toRecord() }

        override fun upsert(record: PairingChallengeService.PairingChallengeRecord) {
            val enc = record.secretEncrypted
            database.pairingChallengesQueries.upsertChallenge(
                challenge_id = record.challengeId,
                challenge_kind = record.kind.name,
                principal_id = record.principalId,
                observed_uid = record.observedUid?.toLong(),
                android_user_id = record.androidUserId?.toLong(),
                state = record.state,
                protocol_label = record.protocolLabel,
                requested_scope_json = encodeScopes(record.requestedScopes),
                server_spki_sha256 = record.serverSpkiSha256,
                connection_epoch = record.connectionEpoch,
                server_nonce = record.serverNonceBase64Url,
                secret_ciphertext = enc?.ciphertext,
                secret_nonce = enc?.nonce,
                secret_key_version = enc?.keyVersion?.toLong(),
                secret_expires_at_epoch_ms = enc?.expiresAtEpochMs,
                secret_schema_version = enc?.schemaVersion?.toLong(),
                attempts_remaining = record.attemptsRemaining.toLong(),
                expires_at_epoch_ms = record.expiresAtEpochMs,
                approved_at_epoch_ms = record.approvedAtEpochMs,
                consumed_at_epoch_ms = record.consumedAtEpochMs,
                created_at_epoch_ms = record.createdAtEpochMs,
                updated_at_epoch_ms = record.updatedAtEpochMs,
            )
        }

        override fun delete(challengeId: String): Boolean {
            if (get(challengeId) == null) return false
            database.pairingChallengesQueries.deleteByChallengeId(challengeId)
            return true
        }
    }

    val revocationEpochs: RevocationEpochStore = object : RevocationEpochStore {
        override fun get(scopeKey: String): RevocationRecord? =
            database.revocationSubjectsQueries
                .selectByScopeKey(scopeKey)
                .executeAsOneOrNull()
                ?.toRecord()

        override fun listAll(): List<RevocationRecord> =
            database.revocationSubjectsQueries.listAll().executeAsList().map { it.toRecord() }

        override fun upsert(record: RevocationRecord) {
            database.revocationSubjectsQueries.upsertSubject(
                scope_key = "${record.scope.kind.name}\u0000${record.scope.subjectId}",
                subject_kind = record.scope.kind.name,
                subject_id = record.scope.subjectId,
                epoch = record.epoch,
                state = record.state,
                reason = record.reason,
                actor_principal_id = record.actorPrincipalId,
                updated_at_epoch_ms = record.updatedAtEpochMs,
            )
        }

        override fun delete(scopeKey: String): Boolean {
            if (get(scopeKey) == null) return false
            database.revocationSubjectsQueries.deleteByScopeKey(scopeKey)
            return true
        }
    }

    /**
     * Encrypted key-blob vault store (ciphertext only). Used by
     * [com.omnillm.runtime.policy.security.EncryptedBlobSecretKeyVault].
     */
    val keyBlobs: EncryptedKeyBlobStore = object : EncryptedKeyBlobStore {
        override fun activeMetadata(purpose: SecurityProfile.KeyPurpose): BrokerKeyMetadata? =
            database.secretBrokerKeysQueries
                .selectActiveByPurpose(purpose.name)
                .executeAsOneOrNull()
                ?.toMetadata()

        override fun getMetadata(
            purpose: SecurityProfile.KeyPurpose,
            keyVersion: Int,
        ): BrokerKeyMetadata? =
            database.secretBrokerKeysQueries
                .selectByPurposeVersion(purpose.name, keyVersion.toLong())
                .executeAsOneOrNull()
                ?.toMetadata()

        override fun listMetadata(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata> =
            database.secretBrokerKeysQueries
                .listByPurpose(purpose.name)
                .executeAsList()
                .map { it.toMetadata() }

        override fun getCiphertext(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): ByteArray? =
            database.secretBrokerKeysQueries
                .selectByPurposeVersion(purpose.name, keyVersion.toLong())
                .executeAsOneOrNull()
                ?.key_ciphertext

        override fun upsert(metadata: BrokerKeyMetadata, ciphertext: ByteArray) {
            database.secretBrokerKeysQueries.upsertKey(
                purpose = metadata.purpose.name,
                key_version = metadata.keyVersion.toLong(),
                state = metadata.state.name,
                created_at_epoch_ms = metadata.createdAtEpochMs,
                rotation_reason = metadata.rotationReason,
                key_storage = KEY_STORAGE_ENCRYPTED_BLOB,
                keystore_alias = null,
                key_ciphertext = ciphertext,
            )
        }

        override fun maxVersion(): Int =
            database.secretBrokerKeysQueries.maxVersion().executeAsOne().toInt()
    }

    val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }

    companion object {
        const val KEY_STORAGE_ENCRYPTED_BLOB: String = "ENCRYPTED_BLOB"
        const val KEY_STORAGE_ANDROID_KEYSTORE: String = "ANDROID_KEYSTORE"
    }
}

// ----- Mapping helpers ------------------------------------------------------

private fun encodeScopes(scopes: Set<String>): String =
    scopes.toSortedSet().joinToString(",")

private fun decodeScopes(json: String): Set<String> =
    if (json.isBlank()) emptySet()
    else json.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun Access_tokens.toRecord(): TokenService.AccessTokenRecord =
    TokenService.AccessTokenRecord(
        tokenId = token_id,
        registrationId = registration_id,
        principalId = principal_id,
        state = state,
        verifier = verifier,
        verifierAlgorithm = verifier_algorithm,
        verifierKeyVersion = verifier_key_version.toInt(),
        transportConstraint = TokenService.TransportConstraint.valueOf(transport_constraint),
        scopes = decodeScopes(scope_json),
        revocationEpoch = revocation_epoch,
        issuedAtEpochMs = issued_at_epoch_ms,
        expiresAtEpochMs = expires_at_epoch_ms,
        label = label,
        clientId = client_id,
        updatedAtEpochMs = updated_at_epoch_ms,
        lastSeenAtEpochMs = last_seen_at_epoch_ms,
    )

private fun Pairing_challenges.toRecord(): PairingChallengeService.PairingChallengeRecord {
    val enc = if (secret_ciphertext != null && secret_nonce != null && secret_key_version != null) {
        EncryptedRecord(
            profileId = SecurityProfile.PROFILE_ID,
            recordType = "LAN_PAIRING_SECRET",
            recordId = challenge_id,
            schemaVersion = (secret_schema_version ?: SecurityProfile.SCHEMA_VERSION.toLong()).toInt(),
            keyVersion = secret_key_version.toInt(),
            expiresAtEpochMs = secret_expires_at_epoch_ms
                ?: expires_at_epoch_ms,
            nonce = secret_nonce,
            ciphertext = secret_ciphertext,
        )
    } else {
        null
    }
    return PairingChallengeService.PairingChallengeRecord(
        challengeId = challenge_id,
        kind = PairingChallengeService.ChallengeKind.valueOf(challenge_kind),
        state = state,
        principalId = principal_id,
        observedUid = observed_uid?.toInt(),
        androidUserId = android_user_id?.toInt(),
        protocolLabel = protocol_label,
        requestedScopes = decodeScopes(requested_scope_json),
        serverSpkiSha256 = server_spki_sha256,
        connectionEpoch = connection_epoch,
        serverNonceBase64Url = server_nonce,
        secretEncrypted = enc,
        attemptsRemaining = attempts_remaining.toInt(),
        expiresAtEpochMs = expires_at_epoch_ms,
        approvedAtEpochMs = approved_at_epoch_ms,
        consumedAtEpochMs = consumed_at_epoch_ms,
        createdAtEpochMs = created_at_epoch_ms,
        updatedAtEpochMs = updated_at_epoch_ms,
        // Plaintext never durable — always null on load.
        secretPlaintextOnce = null,
    )
}

private fun Revocation_subjects.toRecord(): RevocationRecord =
    RevocationRecord(
        scope = RevocationScope(
            subjectId = subject_id,
            kind = RevocationSubjectKind.valueOf(subject_kind),
        ),
        epoch = epoch,
        state = state,
        reason = reason,
        actorPrincipalId = actor_principal_id,
        updatedAtEpochMs = updated_at_epoch_ms,
    )

private fun Secret_broker_keys.toMetadata(): BrokerKeyMetadata =
    BrokerKeyMetadata(
        purpose = SecurityProfile.KeyPurpose.valueOf(purpose),
        keyVersion = key_version.toInt(),
        state = SecurityProfile.KeyState.valueOf(state),
        createdAtEpochMs = created_at_epoch_ms,
        rotationReason = rotation_reason,
    )
