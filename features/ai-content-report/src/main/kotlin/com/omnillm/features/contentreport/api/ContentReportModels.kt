package com.omnillm.features.contentreport.api

import com.omnillm.core.canonical.generated.ContentReportCategory
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ContentReportPayload
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.domain.PayloadFieldPreview

/**
 * Client-generated command identity for report mutations (ADR-004/005).
 */
data class ContentReportCommandIdentity(
    val commandId: String,
    val idempotencyKey: String,
) {
    init {
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
    }
}

/**
 * External / local proposal create (untrusted — never consent evidence).
 * Matches OpenAPI ContentReportProposalRequest required fields.
 */
data class CreateProposalSpec(
    val command: ContentReportCommandIdentity,
    val reportId: String,
    val category: String,
    val createdAt: String,
    val appBuild: String,
    val modelRevisionId: String,
    val engineBuildId: String,
    val backend: String,
    val localPolicyVersion: String,
    val outputDigest: String,
    val userLocale: String,
    val description: String? = null,
    val promptExcerpt: String? = null,
    val outputExcerpt: String? = null,
    val diagnosticSummary: String? = null,
    /**
     * Forbidden on external surfaces (CR-N001). LOCAL_UI may set only after
     * field-by-field review — still requires separate grant issuance.
     */
    val userConfirmed: Boolean = false,
)

/**
 * Trusted local UI: begin field-by-field review of the exact minimized payload.
 */
data class BeginReviewSpec(
    val reportId: String,
    val command: ContentReportCommandIdentity,
)

/**
 * Trusted local UI: issue one-time ConsentGrant after exact payload preview.
 * Digest must match frozen proposal canonical form (CR-N003).
 */
data class GrantConsentSpec(
    val reportId: String,
    val command: ContentReportCommandIdentity,
    val canonicalPayloadDigest: String,
    val warningPolicyVersion: String,
    val localUserProfileId: String,
    /** Optional user-selected excerpts applied before grant if still DRAFT/REVIEWING. */
    val includePromptExcerpt: String? = null,
    val includeOutputExcerpt: String? = null,
    val includeDescription: String? = null,
    val includeDiagnosticSummary: String? = null,
)

/**
 * Atomic submit: consume grant + freeze + SUBMITTING or QUEUED_OFFLINE.
 */
data class SubmitReportSpec(
    val reportId: String,
    val consentGrantId: String,
    val command: ContentReportCommandIdentity,
    /** Prefer online when endpoint+network available; else offline queue. */
    val preferOnline: Boolean = true,
)

data class CancelReportSpec(
    val reportId: String,
    val command: ContentReportCommandIdentity,
)

data class DiscardReportSpec(
    val reportId: String,
    val command: ContentReportCommandIdentity,
)

data class ContentReportInfoView(
    val reportId: String,
    val state: ContentReportState,
    val category: ContentReportCategory?,
    val expiresAtEpochMs: Long,
    val resourceVersion: Long,
    val receiptId: String?,
    val cancelPending: Boolean,
    val hasEncryptedPayload: Boolean,
    val error: OmniError?,
    val actions: List<String>,
    val labelKey: String,
    val dataStreamKind: String,
)

data class ContentReportReviewView(
    val reportId: String,
    val state: ContentReportState,
    val payloadPreview: List<PayloadFieldPreview>,
    val canonicalPayloadDigest: String,
    val minimizedDefault: Boolean,
    val privacyNoticeKey: String = "content-report.privacy.not-telemetry",
    val retentionNoticeKey: String = "content-report.retention.ttl",
)

data class ContentReportSnapshot(
    val reports: List<ContentReportInfoView>,
    val activeReview: ContentReportReviewView?,
    val lastError: OmniError?,
    val endpointConfigured: Boolean,
    val networkAvailable: Boolean,
    val dataStreamKind: String,
    val isTelemetryStream: Boolean,
)

data class GrantIssueResult(
    val grant: ConsentGrant,
    val report: ContentReportInfoView,
)

data class SubmitResult(
    val report: ContentReportInfoView,
    val jobId: String?,
    val queuedOffline: Boolean,
)

data class ReceiptView(
    val receipt: ContentReportReceipt,
    val reportState: ContentReportState,
    /** Receipt is remote acceptance only — never moderation outcome. */
    val isModerationOutcome: Boolean = false,
)

/** Payload shape for unit tests / plan-only preview (ADR-002). */
data class PayloadPlan(
    val payload: ContentReportPayload,
    val digestHex: String,
    val fields: List<PayloadFieldPreview>,
)
