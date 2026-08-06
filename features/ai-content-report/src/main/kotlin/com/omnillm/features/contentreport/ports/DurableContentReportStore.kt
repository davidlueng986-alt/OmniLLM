package com.omnillm.features.contentreport.ports

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.ContentReportCategory
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.ErrorMapping
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.data.persistence.ContentReportGrantRow
import com.omnillm.data.persistence.ContentReportLedgerPorts
import com.omnillm.data.persistence.ContentReportReceiptRow
import com.omnillm.data.persistence.ContentReportRecordRow
import com.omnillm.data.persistence.SingleWriterPolicy
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ConsentGrantState
import com.omnillm.features.contentreport.domain.ContentReportPayload
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.domain.ContentReportRecord
import com.omnillm.runtime.policy.security.EncryptedRecordCodec
import com.omnillm.runtime.policy.security.SecretBroker
import com.omnillm.runtime.policy.security.SecurityProfile
import com.omnillm.runtime.policy.security.VaultSecretBroker
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * SQLite-backed [ContentReportStorePort] for production control plane (ADR-010).
 *
 * Maps domain aggregates ↔ [ContentReportLedgerPorts] rows. Opens only through
 * runtime [com.omnillm.data.persistence.ControlPlaneDatabase] — UI never holds this
 * writer (INV-001).
 *
 * **Queue encryption (FEAT-AI-CONTENT-REPORT §6 / SEC-PROFILE):** proposal and
 * frozen payload BLOBs are AES-256-GCM sealed via [SecretBroker] with
 * [SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION] + record type
 * [VaultSecretBroker.RECORD_TYPE_CONTENT_REPORT]. Plaintext JSON is never written
 * to `encrypted_*` columns. Empty BLOBs remain empty (retention wipe).
 *
 * Report stream ≠ telemetry (SEC-PRIVACY).
 */
class DurableContentReportStore(
    private val ledger: ContentReportLedgerPorts,
    private val secretBroker: SecretBroker,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ContentReportStorePort {

    init {
        SingleWriterPolicy.assertWriterAllowed(ledger.writerRole)
    }

    override fun putReport(record: ContentReportRecord) {
        ledger.tx.inTransaction {
            val existing = ledger.reports.findByReportId(record.reportId)
            ledger.reports.upsert(record.toRow(existing))
        }
    }

    override fun getReport(reportId: String): ContentReportRecord? =
        ledger.reports.findByReportId(reportId)?.toDomain()

    override fun findByPrincipalAndIdempotency(
        principalId: String,
        idempotencyKey: String,
    ): ContentReportRecord? =
        ledger.reports.findByPrincipalAndIdempotency(principalId, idempotencyKey)?.toDomain()

    override fun listByPrincipal(principalId: String): List<ContentReportRecord> =
        ledger.reports.listByPrincipal(principalId).map { it.toDomain() }

    override fun listAll(): List<ContentReportRecord> =
        ledger.reports.listAll().map { it.toDomain() }

    override fun putGrant(grant: ConsentGrant) {
        ledger.tx.inTransaction {
            ledger.grants.upsert(grant.toRow())
        }
    }

    override fun getGrant(grantId: String): ConsentGrant? =
        ledger.grants.findByGrantId(grantId)?.toDomain()

    override fun listGrantsForReport(reportId: String): List<ConsentGrant> =
        ledger.grants.listByReportId(reportId).map { it.toDomain() }

    override fun putReceipt(receipt: ContentReportReceipt) {
        ledger.tx.inTransaction {
            val digest = IdentityHashing.sha256Hex(
                "${receipt.receiptId}\n${receipt.reportId}\n${receipt.acceptedAt}\n${receipt.statusUrl}",
            )
            ledger.receipts.upsert(
                ContentReportReceiptRow(
                    receiptId = receipt.receiptId,
                    reportId = receipt.reportId,
                    acceptedAt = receipt.acceptedAt,
                    statusUrl = receipt.statusUrl,
                    responseDigest = digest,
                    lastQueriedAt = Instant.now().toString(),
                ),
            )
            // Keep denormalized receipt fields on the report row for rehydrate after wipe.
            val existing = ledger.reports.findByReportId(receipt.reportId)
            if (existing != null) {
                ledger.reports.upsert(
                    existing.copy(
                        receiptId = receipt.receiptId,
                        receiptAcceptedAt = receipt.acceptedAt,
                        receiptStatusUrl = receipt.statusUrl,
                        updatedAt = Instant.now().toString(),
                    ),
                )
            }
        }
    }

    override fun getReceipt(reportId: String): ContentReportReceipt? {
        val row = ledger.receipts.findByReportId(reportId) ?: return null
        return ContentReportReceipt(
            receiptId = row.receiptId,
            reportId = row.reportId,
            acceptedAt = row.acceptedAt,
            statusUrl = row.statusUrl,
        )
    }

    // ---------------------------------------------------------------------------
    // Domain ↔ row mapping (sealed BLOBs)
    // ---------------------------------------------------------------------------

    private fun ContentReportRecord.toRow(existing: ContentReportRecordRow?): ContentReportRecordRow {
        val proposalSource = payload ?: frozenPayload
        val proposalBytes = when {
            proposalSource != null ->
                sealPayload(
                    reportId = reportId,
                    plainUtf8 = proposalSource.toCanonicalJson().toByteArray(StandardCharsets.UTF_8),
                    expiresAtEpochMs = expiresAtEpochMs,
                )
            // Retention wipe: clear sensitive BLOB but keep denormalized columns.
            !hasEncryptedPayload -> ByteArray(0)
            existing != null -> existing.encryptedProposal
            else -> ByteArray(0)
        }
        val frozenBytes = when {
            frozenPayload != null ->
                sealPayload(
                    reportId = reportId,
                    plainUtf8 = frozenPayload.toCanonicalJson().toByteArray(StandardCharsets.UTF_8),
                    expiresAtEpochMs = expiresAtEpochMs,
                )
            !hasEncryptedPayload -> null
            existing != null -> existing.encryptedPayload
            else -> null
        }
        val payloadCreated = proposalSource?.createdAt
            ?: existing?.payloadCreatedAt
            ?: Instant.ofEpochMilli(createdAtEpochMs).toString()

        return ContentReportRecordRow(
            reportId = reportId,
            principalId = principalId,
            proposalCommandId = proposalCommandId,
            idempotencyKey = idempotencyKey,
            resourceVersion = resourceVersion,
            state = state.name,
            category = category.name,
            appBuild = proposalSource?.appBuild ?: existing?.appBuild ?: "unknown",
            modelRevisionId = proposalSource?.modelRevisionId
                ?: existing?.modelRevisionId
                ?: "0".repeat(64),
            engineBuildId = proposalSource?.engineBuildId
                ?: existing?.engineBuildId
                ?: "unknown",
            backend = proposalSource?.backend ?: existing?.backend ?: "unknown",
            localPolicyVersion = proposalSource?.localPolicyVersion
                ?: existing?.localPolicyVersion
                ?: "unknown",
            outputDigest = proposalSource?.outputDigest
                ?: existing?.outputDigest
                ?: "0".repeat(64),
            userLocale = proposalSource?.userLocale ?: existing?.userLocale ?: "und",
            encryptedProposal = proposalBytes,
            canonicalPayloadDigest = canonicalPayloadDigest
                ?: existing?.canonicalPayloadDigest,
            encryptedPayload = frozenBytes,
            cancelPending = cancelPending,
            expiresAt = Instant.ofEpochMilli(expiresAtEpochMs).toString(),
            errorCode = error?.code?.code ?: existing?.errorCode,
            activeGrantId = activeGrantId ?: existing?.activeGrantId,
            receiptId = receiptId ?: existing?.receiptId,
            receiptAcceptedAt = receiptAcceptedAt ?: existing?.receiptAcceptedAt,
            receiptStatusUrl = receiptStatusUrl ?: existing?.receiptStatusUrl,
            payloadCreatedAt = payloadCreated,
            createdAt = existing?.createdAt
                ?: Instant.ofEpochMilli(createdAtEpochMs).toString(),
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMs).toString(),
        )
    }

    private fun ContentReportRecordRow.toDomain(): ContentReportRecord {
        val terminal = state in ContentReportRecord.TERMINAL_STATES.map { it.name }
        val proposal = unsealPayload(reportId, encryptedProposal)
        val frozen = unsealPayload(reportId, encryptedPayload)
        // After retention wipe, blobs are empty — do not resurrect sensitive optional fields.
        val sensitiveRetained = !terminal &&
            (encryptedProposal.isNotEmpty() || (encryptedPayload != null && encryptedPayload!!.isNotEmpty()))

        val payloadForDomain = when {
            !sensitiveRetained -> null
            frozen != null -> frozen
            proposal != null -> proposal
            else -> reconstructMinimalPayload()
        }
        val frozenForDomain = if (sensitiveRetained) frozen else null

        return ContentReportRecord(
            reportId = reportId,
            principalId = principalId,
            proposalCommandId = proposalCommandId,
            idempotencyKey = idempotencyKey,
            state = ContentReportState.requireFromCatalogName(state),
            category = ContentReportCategory.requireFromCatalogName(category),
            payload = payloadForDomain,
            canonicalPayloadDigest = canonicalPayloadDigest,
            frozenPayload = frozenForDomain,
            activeGrantId = activeGrantId,
            cancelPending = cancelPending,
            expiresAtEpochMs = parseEpochMs(expiresAt),
            resourceVersion = resourceVersion,
            receiptId = receiptId,
            receiptAcceptedAt = receiptAcceptedAt,
            receiptStatusUrl = receiptStatusUrl,
            error = errorCode?.let { code ->
                try {
                    ErrorMapping.fromCode(code, message = "persisted content-report error")
                } catch (_: Exception) {
                    OmniError.INTERNAL(message = "unknown persisted error code: $code")
                }
            },
            createdAtEpochMs = parseEpochMs(createdAt),
            updatedAtEpochMs = parseEpochMs(updatedAt),
            hasEncryptedPayload = sensitiveRetained,
        )
    }

    /**
     * AES-GCM seal with REPORT_QUEUE_ENCRYPTION. Fail closed — never fall back to
     * plaintext in the durable queue (FEAT-AI-CONTENT-REPORT §6).
     */
    private fun sealPayload(
        reportId: String,
        plainUtf8: ByteArray,
        expiresAtEpochMs: Long,
    ): ByteArray {
        if (plainUtf8.isEmpty()) return ByteArray(0)
        val sealed = secretBroker.encryptRecord(
            recordType = VaultSecretBroker.RECORD_TYPE_CONTENT_REPORT,
            recordId = reportId,
            plaintext = plainUtf8,
            expiresAtEpochMs = expiresAtEpochMs,
            purpose = SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        )
        return when (sealed) {
            is OmniResult.Ok -> EncryptedRecordCodec.encode(sealed.value)
            is OmniResult.Err ->
                error(
                    "content-report queue encrypt failed (fail closed): " +
                        "${sealed.error.code.code} ${sealed.error.message}",
                )
        }
    }

    /**
     * Decrypt sealed envelope. Fail closed on unknown format / wrong purpose key /
     * expiry: return null so callers use denormalized metadata only (no plaintext
     * resurrection of raw UTF-8 JSON mistaken for a payload).
     */
    private fun unsealPayload(reportId: String, blob: ByteArray?): ContentReportPayload? {
        if (blob == null || blob.isEmpty()) return null
        // Fail closed: reject pre-encryption plaintext JSON blobs.
        if (!EncryptedRecordCodec.isSealedEnvelope(blob)) {
            return null
        }
        val record = EncryptedRecordCodec.decode(blob) ?: return null
        if (record.recordId != reportId) return null
        if (record.recordType != VaultSecretBroker.RECORD_TYPE_CONTENT_REPORT) return null
        val plain = secretBroker.decryptRecord(
            record = record,
            purpose = SecurityProfile.KeyPurpose.REPORT_QUEUE_ENCRYPTION,
        )
        return when (plain) {
            is OmniResult.Ok -> decodePayloadJson(plain.value)
            is OmniResult.Err -> null
        }
    }

    private fun decodePayloadJson(bytes: ByteArray): ContentReportPayload? {
        if (bytes.isEmpty()) return null
        return try {
            ContentReportPayload.fromCanonicalJson(String(bytes, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reconstruct required fields from denormalized columns when blob is empty
     * but report is still non-terminal. Optional excerpts are **not** invented.
     */
    private fun ContentReportRecordRow.reconstructMinimalPayload(): ContentReportPayload? =
        try {
            ContentReportPayload(
                reportId = reportId,
                category = ContentReportCategory.requireFromCatalogName(category),
                createdAt = payloadCreatedAt,
                appBuild = appBuild,
                modelRevisionId = modelRevisionId.lowercase(),
                engineBuildId = engineBuildId,
                backend = backend,
                localPolicyVersion = localPolicyVersion,
                outputDigest = outputDigest.lowercase(),
                userLocale = userLocale,
            )
        } catch (_: Exception) {
            null
        }

    private fun ConsentGrant.toRow(): ContentReportGrantRow =
        ContentReportGrantRow(
            grantId = grantId,
            reportId = reportId,
            principalId = principalId,
            canonicalPayloadDigest = canonicalPayloadDigest,
            warningPolicyVersion = warningPolicyVersion,
            localUserProfileId = localUserProfileId,
            nonce = nonce,
            state = state.name,
            issuedAt = Instant.ofEpochMilli(issuedAtEpochMs).toString(),
            expiresAt = Instant.ofEpochMilli(expiresAtEpochMs).toString(),
            consumedAt = consumedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() },
        )

    private fun ContentReportGrantRow.toDomain(): ConsentGrant =
        ConsentGrant(
            grantId = grantId,
            principalId = principalId,
            reportId = reportId,
            canonicalPayloadDigest = canonicalPayloadDigest.lowercase(),
            warningPolicyVersion = warningPolicyVersion,
            localUserProfileId = localUserProfileId,
            issuedAtEpochMs = parseEpochMs(issuedAt),
            expiresAtEpochMs = parseEpochMs(expiresAt),
            nonce = nonce,
            state = ConsentGrantState.fromCatalogName(state)
                ?: error("unknown grant state: $state"),
            consumedAtEpochMs = consumedAt?.let { parseEpochMs(it) },
        )

    private fun parseEpochMs(iso: String): Long =
        try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            // Allow raw epoch millis strings for hermetic fixtures.
            iso.toLongOrNull() ?: 0L
        }
}
