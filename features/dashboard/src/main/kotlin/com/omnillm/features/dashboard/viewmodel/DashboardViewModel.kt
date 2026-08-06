package com.omnillm.features.dashboard.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.dashboard.api.CancelRequestResult
import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.dashboard.api.DashboardSnapshot
import com.omnillm.features.dashboard.api.DashboardUiPhase
import com.omnillm.features.dashboard.api.TraceSummaryUi
import com.omnillm.features.dashboard.projection.DashboardProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure JVM UI state holder for Dashboard (UX-IA / FEAT-DASHBOARD).
 *
 * Android Compose screens in `:android:app-ui` observe [state] and never touch
 * observability registries / governors / DB directly (INV-001).
 *
 * Presentation phases: EMPTY / LOADING / READY / DEGRADED / ERROR.
 * Client generates requestId before cancel/query (ADR-004/005).
 */
class DashboardViewModel(
    private val api: DashboardApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    data class State(
        val phase: DashboardUiPhase = DashboardUiPhase.EMPTY,
        val snapshot: DashboardSnapshot? = null,
        val selectedTrace: TraceSummaryUi? = null,
        val lastCancel: CancelRequestResult? = null,
        val loading: Boolean = false,
        val lastError: OmniError? = null,
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun refresh() {
        state = state.copy(loading = true, lastError = null)
        state = state.copy(
            phase = DashboardProjection.resolveUiPhase(
                hasSnapshot = state.snapshot != null,
                loading = true,
                error = false,
                snapshot = state.snapshot,
            ),
        )
        publish()
        when (val result = api.getSnapshot(principal)) {
            is OmniResult.Ok -> {
                state = State(
                    phase = DashboardProjection.resolveUiPhase(
                        hasSnapshot = true,
                        loading = false,
                        error = false,
                        snapshot = result.value,
                    ),
                    snapshot = result.value,
                    selectedTrace = state.selectedTrace,
                    lastCancel = state.lastCancel,
                    loading = false,
                    lastError = null,
                )
            }
            is OmniResult.Err -> {
                state = state.copy(
                    loading = false,
                    lastError = result.error,
                    phase = DashboardProjection.resolveUiPhase(
                        hasSnapshot = state.snapshot != null,
                        loading = false,
                        error = true,
                        snapshot = state.snapshot,
                    ),
                )
            }
        }
        publish()
    }

    fun selectTrace(correlationId: String) {
        when (val r = api.getTrace(principal, correlationId)) {
            is OmniResult.Ok -> {
                state = state.copy(selectedTrace = r.value, lastError = null)
            }
            is OmniResult.Err -> {
                state = state.copy(lastError = r.error, selectedTrace = null)
            }
        }
        publish()
    }

    /**
     * Cancel using client-generated [requestId].
     * On success, refreshes snapshot so Requests section reflects terminal.
     */
    fun cancelRequest(requestId: RequestId): OmniResult<CancelRequestResult> {
        val result = api.cancelRequest(principal, requestId)
        when (result) {
            is OmniResult.Ok -> {
                state = state.copy(lastCancel = result.value, lastError = null)
                refresh()
            }
            is OmniResult.Err -> {
                state = state.copy(lastError = result.error)
                publish()
            }
        }
        return result
    }

    /**
     * Reply-loss reconciliation: query by client-generated id without re-submit.
     */
    fun queryRequest(requestId: RequestId): OmniResult<CancelRequestResult> {
        val result = api.queryRequest(principal, requestId)
        when (result) {
            is OmniResult.Ok -> state = state.copy(lastCancel = result.value, lastError = null)
            is OmniResult.Err -> state = state.copy(lastError = result.error)
        }
        publish()
        return result
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish() {
        listeners.forEach { it(state) }
    }
}
