package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.OmniConsentGrant
import ai.omnillm.api.OmniContentReportInfo
import ai.omnillm.api.OmniContentReportReceipt
import com.omnillm.features.contentreport.api.ContentReportInfoView
import com.omnillm.features.contentreport.api.SubmitResult
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import java.time.Instant

/**
 * AIDL projection for FEAT-AI-CONTENT-REPORT (INV-013).
 * Maps domain consent/receipt types onto parcelables without inventing fields.
 * Same semantic surface as HTTP ContentReport* DTOs (ADR-011).
 */
object ContentReportAidlMapper {

    fun toAidlConsentGrant(grant: ConsentGrant): OmniConsentGrant {
        val out = OmniConsentGrant()
        out.grantId = grant.grantId
        out.principalId = grant.principalId
        out.reportId = grant.reportId
        out.canonicalPayloadDigest = grant.canonicalPayloadDigest
        out.warningPolicyVersion = grant.warningPolicyVersion
        out.localUserProfileId = grant.localUserProfileId
        out.issuedAtEpochMillis = grant.issuedAtEpochMs
        out.expiresAtEpochMillis = grant.expiresAtEpochMs
        out.nonce = grant.nonce
        return out
    }

    fun toAidlInfo(view: ContentReportInfoView): OmniContentReportInfo {
        val out = OmniContentReportInfo()
        out.reportId = view.reportId
        out.state = view.state.name
        out.expiresAtEpochMillis = view.expiresAtEpochMs
        out.receiptId = view.receiptId
        out.resourceVersion = view.resourceVersion
        out.error = view.error?.let { AdminAidlMapper.toAidlError(it) }
        return out
    }

    fun toAidlReceipt(receipt: ContentReportReceipt): OmniContentReportReceipt {
        val out = OmniContentReportReceipt()
        out.receiptId = receipt.receiptId
        out.reportId = receipt.reportId
        out.acceptedAtEpochMillis = parseEpochMs(receipt.acceptedAt)
        out.statusUrl = receipt.statusUrl
        return out
    }

    fun submitResultJson(result: SubmitResult): String {
        val job = result.jobId?.let { "\"$it\"" } ?: "null"
        return """{"reportId":"${result.report.reportId}","state":"${result.report.state.name}","jobId":$job,"queuedOffline":${result.queuedOffline}}"""
    }

    private fun parseEpochMs(isoOrEpoch: String): Long {
        if (isoOrEpoch.isBlank()) return 0L
        isoOrEpoch.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(isoOrEpoch).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
