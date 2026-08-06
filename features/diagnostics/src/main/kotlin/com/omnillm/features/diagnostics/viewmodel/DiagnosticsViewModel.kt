package com.omnillm.features.diagnostics.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.diagnostics.api.CancelExportSpec
import com.omnillm.features.diagnostics.api.ClientInferenceIdentity
import com.omnillm.features.diagnostics.api.DeleteBundleSpec
import com.omnillm.features.diagnostics.api.DiagnosticJobHandle
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.diagnostics.api.DiagnosticsSnapshot
import com.omnillm.features.diagnostics.api.EvidencedMetricView
import com.omnillm.features.diagnostics.api.StartExportSpec
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.diagnostics.projection.DiagnosticStateProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Pure JVM UI state holder for Diagnostics (INV-001: no engines / no DB writes).
 *
 * Android Compose screens in `:android:app-ui` collect [state] and never touch
 * JobManager / ObservabilityFacade / DB directly.
 */
class DiagnosticsViewModel(
    private val api: DiagnosticsApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    @Volatile
    private var state: DiagnosticsUiState = DiagnosticsUiState()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(DiagnosticsUiState) -> Unit>()

    fun uiState(): DiagnosticsUiState = state

    fun observe(listener: (DiagnosticsUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun refresh() {
        publish(state.copy(loading = true, lastError = null))
        when (val snap = api.getSnapshot(principal)) {
            is OmniResult.Ok -> publish(DiagnosticsUiState.from(snap.value, loading = false))
            is OmniResult.Err -> publish(
                state.copy(loading = false, lastError = snap.error, uiPhase = DiagnosticUiPhases.ERROR),
            )
        }
    }

    fun planExport(
        includeDetail: Boolean = false,
        selectedCategories: List<String> = emptyList(),
    ): OmniResult<DiagnosticExportPlan> {
        val result = api.planExport(principal, includeDetail, selectedCategories)
        when (result) {
            is OmniResult.Ok -> {
                val phase = DiagnosticUiPhases.PREVIEW
                publish(
                    state.copy(
                        plan = result.value,
                        lastError = null,
                        uiPhase = phase,
                        phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(phase),
                        actions = DiagnosticsUiState.actionsForPhase(phase, null),
                        loading = false,
                    ),
                )
            }
            is OmniResult.Err -> publish(
                state.copy(lastError = result.error, loading = false),
            )
        }
        return result
    }

    suspend fun startExport(spec: StartExportSpec): OmniResult<DiagnosticJobHandle> {
        publish(
            state.copy(
                loading = true,
                lastError = null,
                uiPhase = DiagnosticUiPhases.LOADING,
                phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(DiagnosticUiPhases.LOADING),
                actions = DiagnosticsUiState.actionsForPhase(DiagnosticUiPhases.LOADING, null),
            ),
        )
        val result = api.startExport(principal, spec)
        when (result) {
            is OmniResult.Ok -> {
                val phase = DiagnosticUiPhases.LOADING
                publish(
                    state.copy(
                        loading = true,
                        lastJob = result.value,
                        lastError = null,
                        uiPhase = phase,
                        phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(phase),
                        actions = DiagnosticsUiState.actionsForPhase(phase, result.value),
                    ),
                )
            }
            is OmniResult.Err -> {
                val phase = DiagnosticUiPhases.ERROR
                publish(
                    state.copy(
                        loading = false,
                        lastError = result.error,
                        uiPhase = phase,
                        phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(phase),
                        actions = DiagnosticsUiState.actionsForPhase(phase, null),
                    ),
                )
            }
        }
        return result
    }

    suspend fun cancelExport(spec: CancelExportSpec): OmniResult<DiagnosticJobHandle> {
        val result = api.cancelExport(principal, spec)
        when (result) {
            is OmniResult.Ok -> {
                val phase = if (result.value.state == "CANCELLED") {
                    DiagnosticUiPhases.CANCELLED
                } else {
                    DiagnosticUiPhases.LOADING
                }
                publish(
                    state.copy(
                        lastJob = result.value,
                        lastError = null,
                        loading = false,
                        uiPhase = phase,
                        phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(phase),
                        actions = DiagnosticsUiState.actionsForPhase(phase, result.value),
                    ),
                )
            }
            is OmniResult.Err -> publish(state.copy(lastError = result.error))
        }
        return result
    }

    suspend fun collectAndSeal(jobId: String): OmniResult<DiagnosticBundleSnapshot> {
        publish(state.copy(loading = true))
        val result = api.collectAndSeal(jobId)
        when (result) {
            is OmniResult.Ok -> {
                refresh()
            }
            is OmniResult.Err -> {
                publish(
                    state.copy(
                        loading = false,
                        lastError = result.error,
                        uiPhase = DiagnosticUiPhases.ERROR,
                    ),
                )
            }
        }
        return result
    }

    suspend fun deleteBundle(spec: DeleteBundleSpec): OmniResult<DiagnosticBundleSnapshot> {
        val result = api.deleteBundle(principal, spec)
        if (result is OmniResult.Ok) {
            refresh()
        } else if (result is OmniResult.Err) {
            publish(state.copy(lastError = result.error))
        }
        return result
    }

    fun newClientInferenceIdentity(idempotencyKey: String? = null): ClientInferenceIdentity =
        api.newClientInferenceIdentity(idempotencyKey)

    fun listEvidencedMetrics(): OmniResult<List<EvidencedMetricView>> =
        api.listEvidencedMetrics(principal)

    /** FEAT-DIAGNOSTICS §5 — versioned redaction allowlist for export preview. */
    fun exportRedactionAllowlist(): OmniResult<com.omnillm.features.diagnostics.api.RedactionAllowlistExport> =
        api.exportRedactionAllowlist(principal)

    private fun publish(next: DiagnosticsUiState) {
        state = next
        for (l in listeners) l(next)
    }
}

/**
 * Stable UI projection for empty / loading / error / degraded / content
 * (UX-STATE-CATALOG + FEAT-DIAGNOSTICS).
 */
data class DiagnosticsUiState(
    val uiPhase: String = DiagnosticUiPhases.EMPTY,
    val phaseLabelKey: String = DiagnosticStateProjection.uiPhaseLabelKey(DiagnosticUiPhases.EMPTY),
    val loading: Boolean = false,
    val plan: DiagnosticExportPlan? = null,
    val snapshot: DiagnosticsSnapshot? = null,
    val lastJob: DiagnosticJobHandle? = null,
    val bundles: List<DiagnosticBundleSnapshot> = emptyList(),
    val metrics: List<EvidencedMetricView> = emptyList(),
    val degradedReasons: List<String> = emptyList(),
    val shareIrreversibleNoticeKey: String = "diagnostics.share.irreversible",
    val lastError: OmniError? = null,
    val actions: List<String> = listOf("plan-export"),
) {
    val isEmpty: Boolean get() = uiPhase == DiagnosticUiPhases.EMPTY
    val isLoading: Boolean get() = loading || uiPhase == DiagnosticUiPhases.LOADING
    val isError: Boolean get() = uiPhase == DiagnosticUiPhases.ERROR || lastError != null
    val isDegraded: Boolean get() = uiPhase == DiagnosticUiPhases.DEGRADED

    companion object {
        fun from(snapshot: DiagnosticsSnapshot, loading: Boolean = false): DiagnosticsUiState =
            DiagnosticsUiState(
                uiPhase = snapshot.uiPhase,
                phaseLabelKey = DiagnosticStateProjection.uiPhaseLabelKey(snapshot.uiPhase),
                loading = loading,
                plan = snapshot.exportPlan,
                snapshot = snapshot,
                lastJob = snapshot.activeJob,
                bundles = snapshot.bundles,
                metrics = snapshot.metrics,
                degradedReasons = snapshot.degradedReasons,
                shareIrreversibleNoticeKey = snapshot.shareIrreversibleNoticeKey,
                lastError = snapshot.lastError,
                actions = actionsForPhase(snapshot.uiPhase, snapshot.activeJob),
            )

        fun actionsForPhase(phase: String, job: DiagnosticJobHandle?): List<String> = when (phase) {
            DiagnosticUiPhases.EMPTY -> listOf("plan-export")
            DiagnosticUiPhases.PREVIEW -> listOf("start-export", "exclude-category", "toggle-detail")
            DiagnosticUiPhases.LOADING -> {
                val acts = mutableListOf("wait", "query-job")
                // During LOADING, cancel is always offered until the job is known terminal.
                if (job == null || job.state !in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
                    acts += "cancel"
                }
                acts
            }
            DiagnosticUiPhases.CONTENT -> listOf("share", "delete", "plan-export")
            DiagnosticUiPhases.DEGRADED -> listOf("share", "delete", "view-degraded-reasons", "plan-export")
            DiagnosticUiPhases.ERROR -> listOf("view-reason", "plan-export", "retry")
            DiagnosticUiPhases.CANCELLED -> listOf("plan-export")
            else -> listOf("wait")
        }
    }
}
