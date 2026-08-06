package com.omnillm.features.benchmark.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.api.BenchmarkJobHandle
import com.omnillm.features.benchmark.api.BenchmarkPlan
import com.omnillm.features.benchmark.api.BenchmarkSnapshot
import com.omnillm.features.benchmark.api.CancelBenchmarkSpec
import com.omnillm.features.benchmark.api.ExportReportSpec
import com.omnillm.features.benchmark.api.MeasurementReport
import com.omnillm.features.benchmark.api.PlanBenchmarkSpec
import com.omnillm.features.benchmark.api.ProfileComparisonView
import com.omnillm.features.benchmark.api.StartBenchmarkSpec
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.projection.BenchmarkStateProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Pure JVM UI state holder for Benchmark (INV-001: no engines / no DB writes).
 *
 * Android Compose screens in `:android:app-ui` collect [state] and never touch
 * JobManager / ObservabilityFacade / DB directly.
 */
class BenchmarkViewModel(
    private val api: BenchmarkApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    @Volatile
    private var state: BenchmarkUiState = BenchmarkUiState()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(BenchmarkUiState) -> Unit>()

    fun uiState(): BenchmarkUiState = state

    fun observe(listener: (BenchmarkUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun refresh() {
        publish(state.copy(loading = true, lastError = null))
        when (val snap = api.getSnapshot(principal)) {
            is OmniResult.Ok -> publish(BenchmarkUiState.from(snap.value, loading = false))
            is OmniResult.Err -> publish(
                state.copy(
                    loading = false,
                    lastError = snap.error,
                    uiPhase = BenchmarkUiPhases.ERROR,
                ),
            )
        }
    }

    fun plan(spec: PlanBenchmarkSpec): OmniResult<BenchmarkPlan> {
        val result = api.planBenchmark(principal, spec)
        when (result) {
            is OmniResult.Ok -> {
                val phase = BenchmarkUiPhases.PREVIEW
                publish(
                    state.copy(
                        plan = result.value,
                        lastError = null,
                        uiPhase = phase,
                        phaseLabelKey = BenchmarkStateProjection.uiPhaseLabelKey(phase),
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

    suspend fun start(spec: StartBenchmarkSpec): OmniResult<BenchmarkJobHandle> {
        publish(state.copy(loading = true, lastError = null))
        return when (val r = api.startBenchmark(principal, spec)) {
            is OmniResult.Ok -> {
                publish(
                    state.copy(
                        loading = false,
                        activeJob = r.value,
                        uiPhase = BenchmarkUiPhases.LOADING,
                        lastError = null,
                    ),
                )
                r
            }
            is OmniResult.Err -> {
                publish(state.copy(loading = false, lastError = r.error))
                r
            }
        }
    }

    suspend fun cancel(spec: CancelBenchmarkSpec): OmniResult<BenchmarkJobHandle> =
        when (val r = api.cancelBenchmark(principal, spec)) {
            is OmniResult.Ok -> {
                publish(
                    state.copy(
                        activeJob = r.value,
                        uiPhase = BenchmarkUiPhases.CANCELLED,
                        lastError = null,
                    ),
                )
                r
            }
            is OmniResult.Err -> {
                publish(state.copy(lastError = r.error))
                r
            }
        }

    fun compare(leftProfileId: String, rightProfileId: String): OmniResult<ProfileComparisonView> =
        api.compareProfiles(principal, leftProfileId, rightProfileId)

    suspend fun export(spec: ExportReportSpec): OmniResult<MeasurementReport> =
        when (val r = api.exportReport(principal, spec)) {
            is OmniResult.Ok -> {
                publish(state.copy(lastReport = r.value, lastError = null))
                r
            }
            is OmniResult.Err -> {
                publish(state.copy(lastError = r.error))
                r
            }
        }

    private fun publish(next: BenchmarkUiState) {
        state = next
        listeners.forEach { it(next) }
    }
}

data class BenchmarkUiState(
    val loading: Boolean = false,
    val uiPhase: String = BenchmarkUiPhases.EMPTY,
    val phaseLabelKey: String = BenchmarkStateProjection.uiPhaseLabelKey(BenchmarkUiPhases.EMPTY),
    val plan: BenchmarkPlan? = null,
    val activeJob: BenchmarkJobHandle? = null,
    val runs: List<MeasurementRun> = emptyList(),
    val lastReport: MeasurementReport? = null,
    val lastError: OmniError? = null,
    val degradedReasons: List<String> = emptyList(),
) {
    companion object {
        fun from(snap: BenchmarkSnapshot, loading: Boolean): BenchmarkUiState =
            BenchmarkUiState(
                loading = loading,
                uiPhase = snap.uiPhase,
                phaseLabelKey = BenchmarkStateProjection.uiPhaseLabelKey(snap.uiPhase),
                plan = snap.plan,
                activeJob = snap.activeJob,
                runs = snap.runs,
                lastReport = snap.lastReport,
                lastError = snap.lastError,
                degradedReasons = snap.degradedReasons,
            )
    }
}
