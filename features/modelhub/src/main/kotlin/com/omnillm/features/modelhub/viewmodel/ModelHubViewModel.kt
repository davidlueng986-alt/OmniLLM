package com.omnillm.features.modelhub.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.modelhub.api.AcquisitionJobView
import com.omnillm.features.modelhub.api.CancelAcquisitionSpec
import com.omnillm.features.modelhub.api.ModelCard
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.modelhub.api.ModelHubJobHandle
import com.omnillm.features.modelhub.api.ModelHubSnapshot
import com.omnillm.features.modelhub.api.SetPinSpec
import com.omnillm.features.modelhub.api.StartDeleteSpec
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.api.StartImportSpec
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure JVM UI state holder for ModelHub (UX-IA).
 *
 * Android Compose screens in `:android:app-ui` collect [state] and never touch
 * ModelManager / JobManager / DB directly (INV-001).
 */
class ModelHubViewModel(
    private val api: ModelHubApi,
    private val principal: PrincipalId = LocalUiPrincipal.ID,
) {
    private val _state = MutableStateFlow(ModelHubUiState())
    val state: StateFlow<ModelHubUiState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.value = _state.value.copy(loading = true, lastError = null)
        when (val snap = api.getSnapshot(principal)) {
            is OmniResult.Ok -> {
                val prev = _state.value
                _state.value = prev.copy(
                    loading = false,
                    snapshot = snap.value,
                    selectedCard = prev.selectedCard?.let { selected ->
                        snap.value.installed.find { it.installationId == selected.installationId }
                            ?: snap.value.suggested.find { it.modelRevisionId == selected.modelRevisionId }
                    },
                    lastError = null,
                )
            }
            is OmniResult.Err -> {
                _state.value = _state.value.copy(loading = false, lastError = snap.error)
            }
        }
    }

    suspend fun selectInstallation(installationId: String) {
        when (val card = api.getModelCard(principal, installationId = installationId)) {
            is OmniResult.Ok -> _state.value = _state.value.copy(selectedCard = card.value, lastError = null)
            is OmniResult.Err -> _state.value = _state.value.copy(lastError = card.error)
        }
    }

    suspend fun selectRevision(modelRevisionId: String) {
        when (val card = api.getModelCard(principal, modelRevisionId = modelRevisionId)) {
            is OmniResult.Ok -> _state.value = _state.value.copy(selectedCard = card.value, lastError = null)
            is OmniResult.Err -> _state.value = _state.value.copy(lastError = card.error)
        }
    }

    suspend fun download(spec: StartDownloadSpec): OmniResult<ModelHubJobHandle> {
        val result = api.startDownload(principal, spec)
        applyMutation(result)
        refresh()
        return result
    }

    suspend fun import(spec: StartImportSpec): OmniResult<ModelHubJobHandle> {
        val result = api.startImport(principal, spec)
        applyMutation(result)
        refresh()
        return result
    }

    suspend fun delete(spec: StartDeleteSpec): OmniResult<ModelHubJobHandle> {
        val result = api.startDelete(principal, spec)
        applyMutation(result)
        refresh()
        return result
    }

    suspend fun cancel(spec: CancelAcquisitionSpec): OmniResult<ModelHubJobHandle> {
        val result = api.cancelAcquisition(principal, spec)
        applyMutation(result)
        refresh()
        return result
    }

    suspend fun setPinned(spec: SetPinSpec): OmniResult<ModelCard> {
        val result = api.setPinned(principal, spec)
        when (result) {
            is OmniResult.Ok -> {
                _state.value = _state.value.copy(selectedCard = result.value, lastError = null)
                refresh()
            }
            is OmniResult.Err -> _state.value = _state.value.copy(lastError = result.error)
        }
        return result
    }

    private fun applyMutation(result: OmniResult<ModelHubJobHandle>) {
        when (result) {
            is OmniResult.Ok -> _state.value = _state.value.copy(
                lastJob = result.value,
                lastError = null,
            )
            is OmniResult.Err -> _state.value = _state.value.copy(lastError = result.error)
        }
    }
}

data class ModelHubUiState(
    val loading: Boolean = false,
    val snapshot: ModelHubSnapshot? = null,
    val selectedCard: ModelCard? = null,
    val lastJob: ModelHubJobHandle? = null,
    val lastError: OmniError? = null,
) {
    val suggested: List<ModelCard> get() = snapshot?.suggested.orEmpty()
    val installed: List<ModelCard> get() = snapshot?.installed.orEmpty()
    val downloadsAndImports: List<AcquisitionJobView>
        get() = snapshot?.downloadsAndImports.orEmpty()
    val blockingIssues: List<String> get() = snapshot?.blockingIssues.orEmpty()
}
