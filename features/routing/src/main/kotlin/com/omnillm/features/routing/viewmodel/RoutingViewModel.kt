package com.omnillm.features.routing.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.routing.api.PlanRouteSpec
import com.omnillm.features.routing.api.RouteSubmitIdentity
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.routing.api.RoutingDecisionView
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.api.RoutingSnapshot
import com.omnillm.features.routing.api.RoutingUiPhase
import com.omnillm.features.routing.projection.RoutingProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure JVM UI state holder for Routing (FEAT-ROUTING).
 *
 * Android Compose screens in `:android:app-ui` observe [state] and never touch
 * Orchestrator / DB / engines directly (INV-001).
 */
class RoutingViewModel(
    private val api: RoutingApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    data class State(
        val phase: RoutingUiPhase = RoutingUiPhase.EMPTY,
        val snapshot: RoutingSnapshot? = null,
        val lastDecision: RoutingDecisionView? = null,
        val loading: Boolean = false,
        val lastError: OmniError? = null,
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun refreshSnapshot() {
        val snap = api.snapshot()
        state = state.copy(
            phase = snap.phase,
            snapshot = snap,
            lastDecision = snap.lastDecision,
            loading = false,
            lastError = null,
        )
        publish()
    }

    fun negotiate() {
        setLoading(true)
        when (val r = api.negotiate(principal)) {
            is OmniResult.Ok -> {
                state = state.copy(
                    loading = false,
                    lastError = null,
                    snapshot = api.snapshot(),
                    phase = api.snapshot().phase,
                )
            }
            is OmniResult.Err -> {
                state = state.copy(
                    loading = false,
                    lastError = r.error,
                    phase = RoutingUiPhase.ERROR,
                    snapshot = api.snapshot(),
                )
            }
        }
        publish()
    }

    fun validatePreference(preference: RoutingPreferenceView) {
        when (val r = api.validatePreference(principal, preference)) {
            is OmniResult.Ok -> {
                state = state.copy(lastError = null, snapshot = api.snapshot())
            }
            is OmniResult.Err -> {
                state = state.copy(lastError = r.error, phase = RoutingUiPhase.ERROR)
            }
        }
        publish()
    }

    suspend fun plan(spec: PlanRouteSpec) {
        setLoading(true)
        publish()
        when (val r = api.planRoute(principal, spec)) {
            is OmniResult.Ok -> {
                state = State(
                    phase = RoutingUiPhase.READY,
                    snapshot = api.snapshot(),
                    lastDecision = r.value,
                    loading = false,
                    lastError = null,
                )
            }
            is OmniResult.Err -> {
                state = state.copy(
                    loading = false,
                    lastError = r.error,
                    phase = RoutingUiPhase.ERROR,
                    snapshot = api.snapshot(),
                    lastDecision = api.snapshot().lastDecision,
                )
            }
        }
        publish()
    }

    suspend fun submit(spec: PlanRouteSpec, identity: RouteSubmitIdentity) {
        setLoading(true)
        publish()
        when (val r = api.submitRoute(principal, spec, identity)) {
            is OmniResult.Ok -> {
                state = State(
                    phase = RoutingUiPhase.READY,
                    snapshot = api.snapshot(),
                    lastDecision = r.value,
                    loading = false,
                    lastError = null,
                )
            }
            is OmniResult.Err -> {
                state = state.copy(
                    loading = false,
                    lastError = r.error,
                    phase = RoutingUiPhase.ERROR,
                    snapshot = api.snapshot(),
                    lastDecision = api.snapshot().lastDecision,
                )
            }
        }
        publish()
    }

    suspend fun query(requestId: String) {
        setLoading(true)
        publish()
        when (val r = api.queryRoute(principal, requestId)) {
            is OmniResult.Ok -> {
                state = state.copy(
                    loading = false,
                    lastDecision = r.value,
                    lastError = null,
                    phase = RoutingUiPhase.READY,
                    snapshot = api.snapshot(),
                )
            }
            is OmniResult.Err -> {
                state = state.copy(
                    loading = false,
                    lastError = r.error,
                    phase = RoutingProjection.resolveUiPhase(
                        hasDecision = state.lastDecision != null,
                        loading = false,
                        error = true,
                    ),
                    snapshot = api.snapshot(),
                )
            }
        }
        publish()
    }

    private fun setLoading(value: Boolean) {
        state = state.copy(
            loading = value,
            phase = RoutingProjection.resolveUiPhase(
                hasDecision = state.lastDecision != null,
                loading = value,
                error = state.lastError != null && state.lastDecision == null,
            ),
        )
    }

    private fun publish() {
        listeners.forEach { it(state) }
    }
}
