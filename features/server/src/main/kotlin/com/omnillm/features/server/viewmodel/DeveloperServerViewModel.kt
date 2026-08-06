package com.omnillm.features.server.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.server.api.CreateDeveloperClientSpec
import com.omnillm.features.server.api.DeveloperServerApi
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.IssueTokenSpec
import com.omnillm.features.server.api.RevokeSpec
import com.omnillm.features.server.api.SdkSampleRecipe
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.domain.DeveloperServerSnapshot
import com.omnillm.features.server.domain.ServerScreenPhase
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UI-facing state holder for FEAT-SERVER (INV-001: no engines / no DB writes).
 *
 * Pure Kotlin — not AndroidX ViewModel — so `:features:server` stays JVM-only.
 * `:android:app-ui` can wrap this with lifecycle owners later.
 */
class DeveloperServerViewModel(
    private val api: DeveloperServerApi,
) {
    private val listeners = CopyOnWriteArrayList<(DeveloperServerUiState) -> Unit>()

    @Volatile
    private var state: DeveloperServerUiState = DeveloperServerUiState.from(api.snapshot())

    fun uiState(): DeveloperServerUiState = state

    fun observe(listener: (DeveloperServerUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun onRefresh(): OmniResult<DeveloperServerSnapshot> {
        publishBusy()
        val result = api.refresh()
        publishFromApi()
        return result
    }

    suspend fun onEnsureLoopback(): OmniResult<*> {
        publishBusy()
        val result = api.ensureLoopbackStarted()
        publishFromApi()
        return result
    }

    suspend fun onCreateClient(spec: CreateDeveloperClientSpec): OmniResult<TokenIssuanceReceipt> {
        publishBusy()
        val result = api.createClientAndIssueToken(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onIssueToken(spec: IssueTokenSpec): OmniResult<TokenIssuanceReceipt> {
        publishBusy()
        val result = api.issueToken(spec = spec)
        publishFromApi()
        return result
    }

    fun onAcknowledgeTokenReceipt() {
        api.acknowledgeTokenReceipt()
        publishFromApi()
    }

    suspend fun onRevokeClient(spec: RevokeSpec): OmniResult<*> {
        publishBusy()
        val result = api.revokeClient(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onRevokeToken(spec: RevokeSpec): OmniResult<*> {
        publishBusy()
        val result = api.revokeToken(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onNegotiate(
        modelId: String?,
        requiredCapabilities: Set<String>,
    ): OmniResult<*> {
        val result = api.negotiateCapabilities(
            modelId = modelId,
            requiredCapabilities = requiredCapabilities,
        )
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onSmoke(claim: InferenceClaimSpec): OmniResult<SmokeTestResult> {
        publishBusy()
        val result = api.runSmokeInference(claim = claim)
        publishFromApi()
        return result
    }

    suspend fun onCancel(
        requestId: String,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult> {
        publishBusy()
        val result = api.cancelRequest(requestId = requestId, command = command)
        publishFromApi()
        return result
    }

    suspend fun onQuery(requestId: String): OmniResult<SmokeTestResult> {
        val result = api.queryRequest(requestId = requestId)
        publishFromApi()
        return result
    }

    fun sdkSamples(): List<SdkSampleRecipe> = api.listSdkSamples()

    private fun publishBusy() {
        val snap = api.snapshot()
        state = DeveloperServerUiState.from(snap).copy(busy = true)
        listeners.forEach { it(state) }
    }

    private fun publishFromApi(overrideError: OmniError? = null) {
        val snap = api.snapshot()
        state = DeveloperServerUiState.from(snap).let {
            if (overrideError != null) it.copy(error = overrideError) else it
        }
        listeners.forEach { it(state) }
    }
}

data class DeveloperServerUiState(
    val phase: ServerScreenPhase,
    val snapshot: DeveloperServerSnapshot,
    val busy: Boolean,
    val error: OmniError?,
    val samples: List<SdkSampleRecipe>,
) {
    val isEmpty: Boolean get() = phase == ServerScreenPhase.EMPTY
    val isLoading: Boolean get() = phase == ServerScreenPhase.LOADING || busy
    val isDegraded: Boolean get() = phase == ServerScreenPhase.DEGRADED
    val isError: Boolean get() = phase == ServerScreenPhase.ERROR || error != null
    val isReady: Boolean get() = phase == ServerScreenPhase.READY && !busy

    companion object {
        fun from(snapshot: DeveloperServerSnapshot): DeveloperServerUiState =
            DeveloperServerUiState(
                phase = snapshot.presentation,
                snapshot = snapshot,
                busy = snapshot.presentation == ServerScreenPhase.LOADING,
                error = snapshot.error,
                samples = com.omnillm.features.server.api.SdkSampleCatalog.all(),
            )
    }
}
