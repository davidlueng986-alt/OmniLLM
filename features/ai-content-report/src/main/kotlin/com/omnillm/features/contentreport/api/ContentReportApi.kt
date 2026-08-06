package com.omnillm.features.contentreport.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface

/**
 * Public Content Report API (FEAT-AI-CONTENT-REPORT).
 *
 * - External surfaces: propose / get / cancel / discard / receipt only
 * - Trusted LOCAL_UI: review + ConsentGrant + submit
 * - No domain DB writes from UI (ADR-010 / INV-001)
 * - Report ≠ telemetry ([ContentReportPolicy.DATA_STREAM_KIND])
 */
interface ContentReportApi {

    /** Screen snapshot: list own reports + active review if any. */
    suspend fun getSnapshot(principal: PrincipalId): OmniResult<ContentReportSnapshot>

    /**
     * Create proposal (DRAFT). Allowed for exported profiles with
     * content-reports.propose. Never asserts consent.
     */
    suspend fun createProposal(
        principal: PrincipalId,
        surface: CallerSurface,
        profileAuthenticated: Boolean,
        accessProfileId: String,
        spec: CreateProposalSpec,
    ): OmniResult<ContentReportInfoView>

    /** Query own report by id (content-reports.read-own). */
    suspend fun getReport(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView>

    /**
     * Trusted local UI only: DRAFT → REVIEWING, show exact payload preview.
     * Requires content-reports.review-submit.
     */
    suspend fun beginLocalReview(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: BeginReviewSpec,
    ): OmniResult<ContentReportReviewView>

    /**
     * Trusted local UI only: REVIEWING → CONSENT_GRANTED + issue one-time grant.
     */
    suspend fun grantConsent(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: GrantConsentSpec,
    ): OmniResult<GrantIssueResult>

    /**
     * Trusted local UI only: consume grant atomically → QUEUED_OFFLINE or SUBMITTING.
     */
    suspend fun submitReport(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: SubmitReportSpec,
    ): OmniResult<SubmitResult>

    /** Cancel while submitting (or mark cancel intent). */
    suspend fun cancelReport(
        principal: PrincipalId,
        surface: CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: CancelReportSpec,
    ): OmniResult<ContentReportInfoView>

    /** Discard pre-acceptance drafts / queue / retryable failures. */
    suspend fun discardReport(
        principal: PrincipalId,
        surface: CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: DiscardReportSpec,
    ): OmniResult<ContentReportInfoView>

    /** Receipt query (remote acceptance only; not moderation). */
    suspend fun getReceipt(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ReceiptView>

    /**
     * Reply-loss / process-death reconciliation by reportId — never create a
     * second report identity (ADR-004/005, RPT-010/RPT-016).
     */
    suspend fun reconcile(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView>

    /** Retry FAILED_RETRYABLE when network+endpoint available. */
    suspend fun retry(
        principal: PrincipalId,
        surface: CallerSurface,
        reportId: String,
        command: ContentReportCommandIdentity,
    ): OmniResult<SubmitResult>

    /**
     * Pure plan of minimized payload + digest (ADR-002 — no domain mutation).
     * Used by UI to show "what will be sent" before creating a proposal.
     */
    fun planPayload(spec: CreateProposalSpec): OmniResult<PayloadPlan>

    /** Runtime host: apply remote accept / fail after network work. */
    suspend fun onRemoteAccepted(
        reportId: String,
        receiptId: String,
        acceptedAt: String,
        statusUrl: String,
    ): OmniResult<ContentReportInfoView>

    suspend fun onRemoteRetryableFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView>

    suspend fun onRemoteFinalFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView>

    /** TTL sweep for drafts / grants (host-driven). */
    suspend fun expireDue(nowMs: Long = System.currentTimeMillis()): Int
}
