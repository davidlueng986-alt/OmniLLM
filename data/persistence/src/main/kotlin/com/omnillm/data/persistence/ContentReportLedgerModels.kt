package com.omnillm.data.persistence

/**
 * Durable ContentReport ledger rows (FEAT-AI-CONTENT-REPORT / DATA-OWNERSHIP).
 *
 * Authority base: `specs/database/omnillm-schema.sql#content_reports` (+ grants/receipts).
 * Control-plane projection adds [activeGrantId], receipt denorm columns, and
 * [payloadCreatedAt] so crash recovery can rehydrate without re-parsing wiped blobs.
 *
 * **Stream kind:** `AI_CONTENT_REPORT` — never telemetry (SEC-PRIVACY).
 * Raw prompt/output bytes live only in [encryptedProposal]/[encryptedPayload] BLOBs;
 * metrics/logs must use digests/status only.
 */

/** CONTENT_REPORT FSM states from specs/state-machines.yaml / ContentReportState. */
object ContentReportLedgerStates {
    val ALL: Set<String> = setOf(
        "DRAFT",
        "REVIEWING",
        "CONSENT_GRANTED",
        "QUEUED_OFFLINE",
        "SUBMITTING",
        "CANCELLING",
        "RECONCILING",
        "FAILED_RETRYABLE",
        "FAILED_FINAL",
        "SUBMITTED",
        "DISCARDED",
        "EXPIRED",
    )

    val TERMINAL: Set<String> = setOf(
        "SUBMITTED",
        "FAILED_FINAL",
        "DISCARDED",
        "EXPIRED",
    )
}

/** ContentReportCategory catalog names. */
object ContentReportLedgerCategories {
    val ALL: Set<String> = setOf(
        "HATE_HARASSMENT",
        "SEXUAL_CONTENT",
        "CHILD_SAFETY",
        "VIOLENCE_SELF_HARM",
        "ILLEGAL_ACTIVITY",
        "DECEPTION_IMPERSONATION",
        "PRIVACY_PERSONAL_DATA",
        "DANGEROUS_ADVICE",
        "OTHER",
    )
}

/** content_report_consent_grants.state */
object ContentReportGrantStates {
    val ALL: Set<String> = setOf("ISSUED", "CONSUMED", "EXPIRED", "REVOKED")
}

/**
 * Durable content-report row (control-plane sole writer, ADR-010).
 *
 * [encryptedProposal] is required by authority SQL (NOT NULL); use empty bytes when
 * sensitive payload has been retention-wiped.
 */
data class ContentReportRecordRow(
    val reportId: String,
    val principalId: String,
    val proposalCommandId: String,
    val idempotencyKey: String,
    val resourceVersion: Long,
    val state: String,
    val category: String,
    val appBuild: String,
    val modelRevisionId: String,
    val engineBuildId: String,
    val backend: String,
    val localPolicyVersion: String,
    val outputDigest: String,
    val userLocale: String,
    val encryptedProposal: ByteArray,
    val canonicalPayloadDigest: String? = null,
    val encryptedPayload: ByteArray? = null,
    val cancelPending: Boolean = false,
    val expiresAt: String,
    val errorCode: String? = null,
    val activeGrantId: String? = null,
    val receiptId: String? = null,
    val receiptAcceptedAt: String? = null,
    val receiptStatusUrl: String? = null,
    val payloadCreatedAt: String,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(reportId.isNotEmpty()) { "reportId must be non-empty" }
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
        require(state in ContentReportLedgerStates.ALL) { "unknown content report state: $state" }
        require(category in ContentReportLedgerCategories.ALL) {
            "unknown content report category: $category"
        }
    }

    val hasEncryptedPayloadBytes: Boolean
        get() = encryptedPayload != null && encryptedPayload.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentReportRecordRow) return false
        return reportId == other.reportId &&
            principalId == other.principalId &&
            proposalCommandId == other.proposalCommandId &&
            idempotencyKey == other.idempotencyKey &&
            resourceVersion == other.resourceVersion &&
            state == other.state &&
            category == other.category &&
            appBuild == other.appBuild &&
            modelRevisionId == other.modelRevisionId &&
            engineBuildId == other.engineBuildId &&
            backend == other.backend &&
            localPolicyVersion == other.localPolicyVersion &&
            outputDigest == other.outputDigest &&
            userLocale == other.userLocale &&
            encryptedProposal.contentEquals(other.encryptedProposal) &&
            canonicalPayloadDigest == other.canonicalPayloadDigest &&
            (
                encryptedPayload == null && other.encryptedPayload == null ||
                    encryptedPayload != null && other.encryptedPayload != null &&
                    encryptedPayload.contentEquals(other.encryptedPayload)
                ) &&
            cancelPending == other.cancelPending &&
            expiresAt == other.expiresAt &&
            errorCode == other.errorCode &&
            activeGrantId == other.activeGrantId &&
            receiptId == other.receiptId &&
            receiptAcceptedAt == other.receiptAcceptedAt &&
            receiptStatusUrl == other.receiptStatusUrl &&
            payloadCreatedAt == other.payloadCreatedAt &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = reportId.hashCode()
        result = 31 * result + principalId.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + resourceVersion.hashCode()
        result = 31 * result + encryptedProposal.contentHashCode()
        result = 31 * result + (encryptedPayload?.contentHashCode() ?: 0)
        return result
    }
}

data class ContentReportGrantRow(
    val grantId: String,
    val reportId: String,
    val principalId: String,
    val canonicalPayloadDigest: String,
    val warningPolicyVersion: String,
    val localUserProfileId: String,
    val nonce: String,
    val state: String,
    val issuedAt: String,
    val expiresAt: String,
    val consumedAt: String? = null,
) {
    init {
        require(grantId.isNotEmpty())
        require(reportId.isNotEmpty())
        require(state in ContentReportGrantStates.ALL) { "unknown grant state: $state" }
        if (state == "CONSUMED") {
            require(consumedAt != null) { "CONSUMED grants require consumedAt" }
        }
    }
}

data class ContentReportReceiptRow(
    val receiptId: String,
    val reportId: String,
    val acceptedAt: String,
    val statusUrl: String,
    val responseDigest: String,
    val lastQueriedAt: String? = null,
) {
    init {
        require(receiptId.isNotEmpty())
        require(reportId.isNotEmpty())
        require(statusUrl.isNotEmpty())
        require(responseDigest.length == 64) {
            "responseDigest must be 64-char hex (got ${responseDigest.length})"
        }
    }
}
