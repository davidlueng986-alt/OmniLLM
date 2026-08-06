package com.omnillm.features.admin.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.admin.model.AdminHomeUi
import com.omnillm.features.admin.model.CommandStatusUi
import com.omnillm.features.admin.model.JobDetailUi
import com.omnillm.features.admin.model.JobListItemUi
import com.omnillm.features.admin.model.JobObserverSessionUi
import com.omnillm.features.admin.model.SettingsScreenUi
import com.omnillm.features.admin.projection.JobUiAction
import com.omnillm.features.admin.usecase.AdminFeatureApi
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminJobEvent
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.policy.SettingValue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pure state holders for FEAT-ADMIN screens (no Android Lifecycle dependency).
 * UI process binds Admin and feeds events here; never opens DB/engines (INV-001).
 */

class AdminHomeViewModel(
    private val api: AdminFeatureApi,
) {
    data class State(
        val home: AdminHomeUi? = null,
        val loading: Boolean = false,
        val error: OmniError? = null,
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun refresh() {
        state = state.copy(loading = true, error = null)
        publish()
        try {
            val home = api.getHome()
            state = State(home = home, loading = false, error = null)
        } catch (e: Exception) {
            state = state.copy(
                loading = false,
                error = OmniError.INTERNAL(message = e.message ?: "refresh failed"),
            )
        }
        publish()
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

class AdminJobsViewModel(
    private val api: AdminFeatureApi,
) {
    data class State(
        val jobs: List<JobListItemUi> = emptyList(),
        val selected: JobDetailUi? = null,
        val lastCommand: CommandStatusUi? = null,
        val observer: JobObserverSessionUi? = null,
        val recentEvents: List<AdminJobEvent> = emptyList(),
        val loading: Boolean = false,
        val error: OmniError? = null,
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun refreshList() {
        state = state.copy(loading = true, error = null)
        publish()
        try {
            val jobs = api.listActiveJobs()
            state = state.copy(jobs = jobs, loading = false)
        } catch (e: Exception) {
            state = state.copy(
                loading = false,
                error = OmniError.INTERNAL(message = e.message ?: "list jobs failed"),
            )
        }
        publish()
    }

    fun selectJob(jobId: String) {
        when (val r = api.getJob(jobId)) {
            is OmniResult.Ok -> {
                state = state.copy(selected = r.value, error = null)
            }
            is OmniResult.Err -> {
                state = state.copy(error = r.error, selected = null)
            }
        }
        publish()
    }

    fun startJob(
        jobId: String,
        kind: String,
        command: AdminCommandRequest,
        parameters: JobParameters,
    ) {
        when (val r = api.startJob(jobId, kind, command, parameters)) {
            is OmniResult.Ok -> {
                state = state.copy(selected = r.value, error = null)
                refreshList()
            }
            is OmniResult.Err -> {
                state = state.copy(error = r.error)
                publish()
            }
        }
    }

    /**
     * Cancel if UX action [JobUiAction.CANCEL] is legal; otherwise no-op with STATE_CONFLICT.
     */
    fun cancelSelected(command: AdminCommandRequest) {
        val selected = state.selected
            ?: run {
                state = state.copy(
                    error = OmniError.INVALID_REQUEST(message = "no job selected"),
                )
                publish()
                return
            }
        if (JobUiAction.CANCEL !in selected.list.allowedActions &&
            JobUiAction.WAIT_SAFE_STOP !in selected.list.allowedActions
        ) {
            state = state.copy(
                error = OmniError.STATE_CONFLICT(
                    message = "cancel not allowed in state ${selected.list.state}",
                ),
            )
            publish()
            return
        }
        val cmd = api.cancelJob(selected.list.jobId, command)
        state = state.copy(lastCommand = cmd, error = cmd.error)
        // Refresh detail after cancel (or cancel-requested).
        selectJob(selected.list.jobId)
        refreshList()
    }

    fun startObserving(credit: Int = 32) {
        stopObserving()
        val result = api.observeJobs.subscribe(
            cursor = null,
            credit = credit,
            onEvents = { events ->
                val merged = (state.recentEvents + events).takeLast(MAX_RECENT_EVENTS)
                state = state.copy(recentEvents = merged)
                // Refresh list projection on job lifecycle events.
                refreshList()
            },
            onRejected = { err ->
                state = state.copy(error = err)
                publish()
            },
        )
        when (result) {
            is OmniResult.Ok -> {
                state = state.copy(
                    observer = result.value.session,
                    error = null,
                )
                if (result.value.rebuiltFromSnapshot && result.value.homeAfterRebuild != null) {
                    state = state.copy(
                        jobs = result.value.homeAfterRebuild!!.jobs,
                    )
                }
            }
            is OmniResult.Err -> {
                state = state.copy(error = result.error)
            }
        }
        publish()
    }

    fun ackEvents(eventToExclusive: Long) {
        val session = state.observer ?: return
        when (val r = api.observeJobs.ack(session, eventToExclusive)) {
            is OmniResult.Ok -> state = state.copy(observer = r.value)
            is OmniResult.Err -> state = state.copy(error = r.error)
        }
        publish()
    }

    fun stopObserving() {
        state.observer?.let { api.observeJobs.close(it) }
        state = state.copy(observer = null)
        publish()
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

    companion object {
        const val MAX_RECENT_EVENTS: Int = 64
    }
}

class AdminSettingsViewModel(
    private val api: AdminFeatureApi,
) {
    data class State(
        val screen: SettingsScreenUi? = null,
        val lastCommand: CommandStatusUi? = null,
        val loading: Boolean = false,
        val error: OmniError? = null,
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun refresh() {
        state = state.copy(loading = true, error = null)
        publish()
        try {
            val screen = api.getSettings()
            state = State(screen = screen, loading = false)
        } catch (e: Exception) {
            state = state.copy(
                loading = false,
                error = OmniError.INTERNAL(message = e.message ?: "settings refresh failed"),
            )
        }
        publish()
    }

    fun applyPatch(command: AdminCommandRequest, changes: Map<String, SettingValue>) {
        val cmd = api.patchSettings(command, changes)
        state = state.copy(lastCommand = cmd, error = cmd.error)
        if (cmd.isSuccess) {
            // Re-load settings for new resourceVersion.
            val screen = api.getSettings().copy(lastCommand = cmd)
            state = state.copy(screen = screen, lastCommand = cmd, error = null)
        }
        publish()
    }

    /**
     * Reply-loss: query command by id without re-applying (ADR-004/005).
     */
    fun reconcileCommand(commandId: String) {
        val cmd = api.queryCommand(commandId)
        state = state.copy(lastCommand = cmd, error = cmd.error)
        if (cmd.isSuccess) {
            val screen = api.getSettings().copy(lastCommand = cmd)
            state = state.copy(screen = screen)
        }
        publish()
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
