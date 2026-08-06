package com.omnillm.features.playground.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.CancelStatusUi
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.api.MultimodalChatRequestSpec
import com.omnillm.features.playground.api.PlaygroundApi
import com.omnillm.features.playground.api.PlaygroundScreenMode
import com.omnillm.features.playground.api.PlaygroundSnapshot
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.features.playground.api.RequestStripUi
import com.omnillm.features.playground.api.StructuredToolsRequestSpec
import com.omnillm.features.playground.api.TabCapabilityView
import com.omnillm.features.playground.projection.PlaygroundUiAction
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/**
 * Pure state holder for FEAT-PLAYGROUND screens (no Android Lifecycle dependency).
 * UI process binds runtime and feeds events here; never opens DB/engines (INV-001).
 *
 * Client must mint requestId / idempotencyKey before [sendChat] / [sendEmbedding]
 * (ADR-004/005).
 */
class PlaygroundViewModel(
    private val api: PlaygroundApi,
) {
    data class State(
        val snapshot: PlaygroundSnapshot? = null,
        val activeTab: PlaygroundTab = PlaygroundTab.CHAT,
        val selectedModelRevisionId: String? = null,
        val activeRequest: RequestStripUi? = null,
        val lastCancel: CancelStatusUi? = null,
        val tabCapability: TabCapabilityView? = null,
        val loading: Boolean = false,
        val error: OmniError? = null,
    ) {
        val screenMode: PlaygroundScreenMode
            get() = snapshot?.screenMode
                ?: if (loading) PlaygroundScreenMode.LOADING else PlaygroundScreenMode.EMPTY
    }

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    var state: State = State()
        private set

    fun selectTab(tab: PlaygroundTab) {
        state = state.copy(activeTab = tab)
        refresh()
        renegotiate()
    }

    fun selectModel(modelRevisionId: String) {
        state = state.copy(selectedModelRevisionId = modelRevisionId)
        refresh()
        renegotiate()
    }

    fun refresh() {
        state = state.copy(loading = true, error = null)
        publish()
        runBlocking {
            when (
                val r = api.getSnapshot(
                    principal = LocalUiPrincipal.ID,
                    activeTab = state.activeTab,
                    selectedModelRevisionId = state.selectedModelRevisionId,
                )
            ) {
                is OmniResult.Ok -> {
                    state = state.copy(
                        snapshot = r.value,
                        selectedModelRevisionId = r.value.selectedModelRevisionId
                            ?: state.selectedModelRevisionId,
                        activeRequest = r.value.activeRequest ?: state.activeRequest,
                        loading = false,
                        error = r.value.lastError,
                    )
                }
                is OmniResult.Err -> {
                    state = state.copy(loading = false, error = r.error)
                }
            }
        }
        publish()
    }

    fun renegotiate() {
        val model = state.selectedModelRevisionId ?: return
        runBlocking {
            when (
                val r = api.negotiateCapabilities(
                    principal = LocalUiPrincipal.ID,
                    tab = state.activeTab,
                    modelRevisionId = model,
                )
            ) {
                is OmniResult.Ok -> state = state.copy(tabCapability = r.value, error = null)
                is OmniResult.Err -> state = state.copy(error = r.error, tabCapability = null)
            }
        }
        publish()
    }

    /**
     * Start chat. [spec.identity] must be client-generated before this call.
     */
    fun sendChat(spec: ChatRequestSpec) {
        if (state.tabCapability?.operable == false) {
            state = state.copy(
                error = OmniError.CAPABILITY_UNSUPPORTED(
                    message = "tab not operable",
                    details = mapOf(
                        "tab" to state.activeTab.name,
                        "reason" to (state.tabCapability?.blockingReasonKey ?: "unknown"),
                    ),
                ),
            )
            publish()
            return
        }
        state = state.copy(loading = true, error = null)
        publish()
        runBlocking {
            when (val r = api.startChat(LocalUiPrincipal.ID, spec)) {
                is OmniResult.Ok -> {
                    state = state.copy(
                        activeRequest = r.value,
                        loading = false,
                        error = null,
                    )
                    refresh()
                }
                is OmniResult.Err -> {
                    state = state.copy(loading = false, error = r.error)
                    publish()
                }
            }
        }
    }

    fun sendEmbedding(spec: EmbeddingRequestSpec) {
        if (state.tabCapability?.operable == false) {
            state = state.copy(
                error = OmniError.CAPABILITY_UNSUPPORTED(
                    message = "embeddings not operable for selected model",
                ),
            )
            publish()
            return
        }
        state = state.copy(loading = true, error = null)
        publish()
        runBlocking {
            when (val r = api.startEmbedding(LocalUiPrincipal.ID, spec)) {
                is OmniResult.Ok -> {
                    state = state.copy(activeRequest = r.value, loading = false, error = null)
                    refresh()
                }
                is OmniResult.Err -> {
                    state = state.copy(loading = false, error = r.error)
                    publish()
                }
            }
        }
    }

    fun sendMultimodal(spec: MultimodalChatRequestSpec) {
        state = state.copy(loading = true, error = null)
        publish()
        runBlocking {
            when (val r = api.startMultimodalChat(LocalUiPrincipal.ID, spec)) {
                is OmniResult.Ok -> {
                    state = state.copy(activeRequest = r.value, loading = false, error = null)
                    refresh()
                }
                is OmniResult.Err -> {
                    state = state.copy(loading = false, error = r.error)
                    publish()
                }
            }
        }
    }

    /**
     * STRUCTURED_TOOLS tab. [spec.identity] must be client-generated (ADR-004/005).
     */
    fun sendStructuredTools(spec: StructuredToolsRequestSpec) {
        if (state.tabCapability?.operable == false) {
            state = state.copy(
                error = OmniError.CAPABILITY_UNSUPPORTED(
                    message = "structured tools tab not operable",
                    details = mapOf(
                        "tab" to PlaygroundTab.STRUCTURED_TOOLS.name,
                        "reason" to (state.tabCapability?.blockingReasonKey ?: "unknown"),
                    ),
                ),
            )
            publish()
            return
        }
        state = state.copy(loading = true, error = null)
        publish()
        runBlocking {
            when (val r = api.startStructuredTools(LocalUiPrincipal.ID, spec)) {
                is OmniResult.Ok -> {
                    state = state.copy(activeRequest = r.value, loading = false, error = null)
                    refresh()
                }
                is OmniResult.Err -> {
                    state = state.copy(loading = false, error = r.error)
                    publish()
                }
            }
        }
    }

    /**
     * Cancel active request when [PlaygroundUiAction.CANCEL] is legal.
     * Cancel identity is client-generated (ADR-004/005).
     */
    fun cancelActive(spec: CancelInferenceSpec) {
        val active = state.activeRequest
            ?: run {
                state = state.copy(
                    error = OmniError.INVALID_REQUEST(message = "no active request"),
                )
                publish()
                return
            }
        if (PlaygroundUiAction.CANCEL !in active.allowedActions) {
            state = state.copy(
                error = OmniError.STATE_CONFLICT(
                    message = "cancel not allowed in state ${active.state}",
                    details = mapOf("state" to active.state),
                ),
            )
            publish()
            return
        }
        if (active.requestId != spec.requestId) {
            state = state.copy(
                error = OmniError.INVALID_REQUEST(
                    message = "cancel requestId mismatch",
                    details = mapOf(
                        "active" to active.requestId,
                        "spec" to spec.requestId,
                    ),
                ),
            )
            publish()
            return
        }
        runBlocking {
            when (val r = api.cancelRequest(LocalUiPrincipal.ID, spec)) {
                is OmniResult.Ok -> {
                    state = state.copy(lastCancel = r.value, error = null)
                    // Reconcile strip via query (do not invent terminal).
                    queryActive(spec.requestId)
                }
                is OmniResult.Err -> {
                    state = state.copy(error = r.error)
                    publish()
                }
            }
        }
    }

    /** Reply-loss reconciliation — query only (ADR-004/005). */
    fun queryActive(requestId: String) {
        runBlocking {
            when (val r = api.queryRequest(LocalUiPrincipal.ID, requestId)) {
                is OmniResult.Ok -> {
                    state = state.copy(activeRequest = r.value, error = null)
                }
                is OmniResult.Err -> {
                    state = state.copy(error = r.error)
                }
            }
        }
        publish()
    }

    /**
     * Stream/cancel/query path: pull bounded events after exclusive [afterSeq]
     * (FEAT-PLAYGROUND §2 / CORE-INTERFACE §4). Never re-executes.
     */
    fun streamActive(requestId: String, afterSeq: Long = 0L):
        OmniResult<com.omnillm.features.playground.api.PlaygroundStreamBatch> {
        val result = runBlocking {
            api.streamEvents(LocalUiPrincipal.ID, requestId, afterSeq)
        }
        when (result) {
            is OmniResult.Ok -> {
                val batch = result.value
                // Refresh strip from latest state disclosed in the batch.
                state = state.copy(
                    activeRequest = state.activeRequest?.copy(
                        state = batch.requestState,
                        isTerminal = batch.isTerminal,
                        cancelPhase = batch.cancelPhase
                            ?: state.activeRequest?.cancelPhase,
                    ),
                    error = null,
                )
            }
            is OmniResult.Err -> {
                state = state.copy(error = result.error)
            }
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
