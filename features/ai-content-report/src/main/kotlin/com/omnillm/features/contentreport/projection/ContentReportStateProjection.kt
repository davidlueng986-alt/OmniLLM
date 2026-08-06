package com.omnillm.features.contentreport.projection

import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.features.contentreport.api.ContentReportInfoView
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportRecord
import com.omnillm.features.contentreport.domain.ContentReportReceipt

/**
 * Projects CONTENT_REPORT catalog states to UX labels / actions
 * (specs/ux-projection-catalog.yaml content-report.*).
 */
object ContentReportStateProjection {

    fun labelKey(state: ContentReportState): String = when (state) {
        ContentReportState.DRAFT -> "content-report.draft"
        ContentReportState.REVIEWING -> "content-report.reviewing"
        ContentReportState.CONSENT_GRANTED -> "content-report.consent-granted"
        ContentReportState.QUEUED_OFFLINE -> "content-report.queued-offline"
        ContentReportState.SUBMITTING -> "content-report.submitting"
        ContentReportState.CANCELLING -> "content-report.cancelling"
        ContentReportState.RECONCILING -> "content-report.reconciling"
        ContentReportState.FAILED_RETRYABLE -> "content-report.retryable-failure"
        ContentReportState.FAILED_FINAL -> "content-report.final-failure"
        ContentReportState.SUBMITTED -> "content-report.submitted"
        ContentReportState.DISCARDED -> "content-report.discarded"
        ContentReportState.EXPIRED -> "content-report.expired"
    }

    fun actionsFor(state: ContentReportState): List<String> = when (state) {
        ContentReportState.DRAFT -> listOf("review-payload", "discard")
        ContentReportState.REVIEWING -> listOf("grant-consent", "discard")
        ContentReportState.CONSENT_GRANTED ->
            listOf("submit", "queue-offline", "discard", "review-payload")
        ContentReportState.QUEUED_OFFLINE -> listOf("wait-for-network", "discard")
        ContentReportState.SUBMITTING -> listOf("cancel", "wait")
        ContentReportState.CANCELLING -> listOf("wait")
        ContentReportState.RECONCILING -> listOf("wait")
        ContentReportState.FAILED_RETRYABLE ->
            listOf("retry", "discard", "review-payload")
        ContentReportState.FAILED_FINAL -> listOf("view-details", "delete-local-record")
        ContentReportState.SUBMITTED -> listOf("view-receipt")
        ContentReportState.DISCARDED -> emptyList()
        ContentReportState.EXPIRED -> listOf("delete-local-record")
    }

    fun toInfoView(record: ContentReportRecord): ContentReportInfoView =
        ContentReportInfoView(
            reportId = record.reportId,
            state = record.state,
            category = record.category,
            expiresAtEpochMs = record.expiresAtEpochMs,
            resourceVersion = record.resourceVersion,
            receiptId = record.receiptId,
            cancelPending = record.cancelPending,
            hasEncryptedPayload = record.retainsSensitivePayload(),
            error = record.error,
            actions = actionsFor(record.state),
            labelKey = labelKey(record.state),
            dataStreamKind = ContentReportPolicy.DATA_STREAM_KIND,
        )

    /**
     * Receipt is remote acceptance only — never describe as moderated/resolved
     * (ux-projection-catalog: never describe a submitted content report as
     * moderated or resolved).
     */
    fun receiptIsModerationOutcome(): Boolean = false

    fun assertNotTelemetry(streamKind: String) {
        require(streamKind != ContentReportPolicy.TELEMETRY_STREAM_KIND) {
            "content report must not use telemetry stream"
        }
        require(streamKind == ContentReportPolicy.DATA_STREAM_KIND) {
            "unknown content-report stream kind (fail closed): $streamKind"
        }
    }

    fun projectReceipt(receipt: ContentReportReceipt, state: ContentReportState) =
        Triple(receipt, state, receiptIsModerationOutcome())
}
