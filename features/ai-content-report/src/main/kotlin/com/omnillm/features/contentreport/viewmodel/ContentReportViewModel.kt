package com.omnillm.features.contentreport.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.ContentReportInfoView
import com.omnillm.features.contentreport.api.ContentReportReviewView
import com.omnillm.features.contentreport.api.ContentReportSnapshot
import com.omnillm.features.contentreport.api.CreateProposalSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.GrantIssueResult
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.api.SubmitResult
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Pure JVM UI state holder for AI content report (INV-001: no engines / DB).
 * Compose screens in `:android:app-ui` collect [uiState] only.
 */
class ContentReportViewModel(
    private val api: ContentReportApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    @Volatile
    private var state: ContentReportUiState = ContentReportUiState()

    private val listeners =
        java.util.concurrent.CopyOnWriteArrayList<(ContentReportUiState) -> Unit>()

    fun uiState(): ContentReportUiState = state

    fun observe(listener: (ContentReportUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun refresh() {
        publish(state.copy(loading = true, lastError = null))
        when (val snap = api.getSnapshot(principal)) {
            is OmniResult.Ok -> publish(ContentReportUiState.from(snap.value, loading = false))
            is OmniResult.Err -> publish(
                state.copy(loading = false, lastError = snap.error),
            )
        }
    }

    suspend fun createProposal(spec: CreateProposalSpec): OmniResult<ContentReportInfoView> {
        val result = api.createProposal(
            principal = principal,
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            profileAuthenticated = true,
            accessProfileId = "LOCAL_ADMIN",
            spec = spec,
        )
        applyInfo(result)
        return result
    }

    suspend fun beginReview(spec: BeginReviewSpec): OmniResult<ContentReportReviewView> {
        val result = api.beginLocalReview(
            principal = principal,
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            spec = spec,
        )
        when (result) {
            is OmniResult.Ok -> publish(
                state.copy(
                    activeReview = result.value,
                    lastError = null,
                    loading = false,
                ),
            )
            is OmniResult.Err -> publish(state.copy(lastError = result.error, loading = false))
        }
        return result
    }

    suspend fun grantConsent(spec: GrantConsentSpec): OmniResult<GrantIssueResult> {
        val result = api.grantConsent(
            principal = principal,
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            spec = spec,
        )
        when (result) {
            is OmniResult.Ok -> publish(
                state.copy(
                    reports = upsert(state.reports, result.value.report),
                    lastError = null,
                    loading = false,
                ),
            )
            is OmniResult.Err -> publish(state.copy(lastError = result.error, loading = false))
        }
        return result
    }

    suspend fun submit(spec: SubmitReportSpec): OmniResult<SubmitResult> {
        val result = api.submitReport(
            principal = principal,
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            spec = spec,
        )
        when (result) {
            is OmniResult.Ok -> publish(
                state.copy(
                    reports = upsert(state.reports, result.value.report),
                    lastError = null,
                    loading = false,
                ),
            )
            is OmniResult.Err -> publish(state.copy(lastError = result.error, loading = false))
        }
        return result
    }

    suspend fun discard(spec: DiscardReportSpec): OmniResult<ContentReportInfoView> {
        val result = api.discardReport(
            principal = principal,
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            accessProfileId = "LOCAL_ADMIN",
            authenticated = true,
            spec = spec,
        )
        applyInfo(result)
        return result
    }

    private fun applyInfo(result: OmniResult<ContentReportInfoView>) {
        when (result) {
            is OmniResult.Ok -> publish(
                state.copy(
                    reports = upsert(state.reports, result.value),
                    lastError = null,
                    loading = false,
                ),
            )
            is OmniResult.Err -> publish(state.copy(lastError = result.error, loading = false))
        }
    }

    private fun upsert(
        list: List<ContentReportInfoView>,
        item: ContentReportInfoView,
    ): List<ContentReportInfoView> {
        val without = list.filterNot { it.reportId == item.reportId }
        return listOf(item) + without
    }

    private fun publish(next: ContentReportUiState) {
        state = next
        listeners.forEach { it(next) }
    }
}

data class ContentReportUiState(
    val loading: Boolean = false,
    val reports: List<ContentReportInfoView> = emptyList(),
    val activeReview: ContentReportReviewView? = null,
    val lastError: OmniError? = null,
    val endpointConfigured: Boolean = false,
    val networkAvailable: Boolean = false,
    val dataStreamKind: String = ContentReportPolicy.DATA_STREAM_KIND,
    val isTelemetryStream: Boolean = false,
) {
    companion object {
        fun from(snap: ContentReportSnapshot, loading: Boolean): ContentReportUiState =
            ContentReportUiState(
                loading = loading,
                reports = snap.reports,
                activeReview = snap.activeReview,
                lastError = snap.lastError,
                endpointConfigured = snap.endpointConfigured,
                networkAvailable = snap.networkAvailable,
                dataStreamKind = snap.dataStreamKind,
                isTelemetryStream = snap.isTelemetryStream,
            )
    }
}
