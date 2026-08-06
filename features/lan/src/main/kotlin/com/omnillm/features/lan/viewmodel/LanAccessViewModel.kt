package com.omnillm.features.lan.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanAccessApi
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.domain.LanAccessSnapshot
import com.omnillm.features.lan.domain.LanScreenPhase
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UI-facing state holder for FEAT-LAN (INV-001: no engines / no DB writes).
 *
 * Pure Kotlin — not AndroidX ViewModel — so `:features:lan` stays JVM-only.
 */
class LanAccessViewModel(
    private val api: LanAccessApi,
) {
    private val listeners = CopyOnWriteArrayList<(LanAccessUiState) -> Unit>()

    @Volatile
    private var state: LanAccessUiState = LanAccessUiState.from(api.snapshot())

    fun uiState(): LanAccessUiState = state

    fun observe(listener: (LanAccessUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun onRefresh(): OmniResult<LanAccessSnapshot> {
        publishBusy()
        val result = api.refresh()
        publishFromApi()
        return result
    }

    suspend fun onEnable(spec: EnableLanSpec): OmniResult<*> {
        publishBusy()
        val result = api.enableLan(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onDisable(spec: DisableLanSpec): OmniResult<*> {
        publishBusy()
        val result = api.disableLan(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onCreateChallenge(spec: CreatePairingChallengeSpec): OmniResult<*> {
        publishBusy()
        val result = api.createPairingChallenge(spec = spec)
        publishFromApi()
        return result
    }

    suspend fun onApproveChallenge(spec: ApprovePairingChallengeSpec): OmniResult<*> {
        publishBusy()
        val result = api.approvePairingChallenge(spec = spec)
        publishFromApi()
        return result
    }

    fun onAcknowledgeTokenReceipt() {
        api.acknowledgeTokenReceipt()
        publishFromApi()
    }

    suspend fun onRevokeClient(spec: RevokeLanClientSpec): OmniResult<*> {
        publishBusy()
        val result = api.revokeClient(spec = spec)
        publishFromApi()
        return result
    }

    private fun publishBusy() {
        val snap = api.snapshot()
        publish(
            LanAccessUiState.from(snap).copy(
                phase = LanScreenPhase.LOADING,
                busy = true,
            ),
        )
    }

    private fun publishFromApi() {
        publish(LanAccessUiState.from(api.snapshot()))
    }

    private fun publish(next: LanAccessUiState) {
        state = next
        listeners.forEach { it(next) }
    }
}

data class LanAccessUiState(
    val phase: LanScreenPhase,
    val busy: Boolean,
    val lanEnabled: Boolean,
    val serviceState: String?,
    val connectionEpoch: Long?,
    val serverSpkiSha256: String?,
    val serverLocator: String?,
    val clientCount: Int,
    val hasPendingChallenge: Boolean,
    val hasPendingToken: Boolean,
    val qrPayload: String?,
    val pendingChallengeId: String?,
    val pendingChallengeState: String?,
    val pendingChallengeScopes: Set<String>,
    val clients: List<com.omnillm.features.lan.api.LanClientView>,
    val error: OmniError?,
    /** Always false per product default — for UI disclosure. */
    val productDefaultLanEnabled: Boolean,
) {
    companion object {
        fun from(snap: LanAccessSnapshot): LanAccessUiState =
            LanAccessUiState(
                phase = snap.presentation,
                busy = snap.isLoading,
                lanEnabled = snap.service?.enabled == true,
                serviceState = snap.service?.state,
                connectionEpoch = snap.service?.connectionEpoch,
                serverSpkiSha256 = snap.service?.serverSpkiSha256,
                serverLocator = snap.service?.serverLocator,
                clientCount = snap.clients.size,
                hasPendingChallenge = snap.pendingChallenge != null,
                hasPendingToken = snap.pendingTokenReceipt != null,
                qrPayload = snap.pendingChallenge?.qrPayload,
                pendingChallengeId = snap.pendingChallenge?.challengeId,
                pendingChallengeState = snap.pendingChallenge?.state,
                pendingChallengeScopes = snap.pendingChallenge?.requestedScopes.orEmpty(),
                clients = snap.clients,
                error = snap.error,
                productDefaultLanEnabled = snap.defaultLanEnabled,
            )
    }
}
