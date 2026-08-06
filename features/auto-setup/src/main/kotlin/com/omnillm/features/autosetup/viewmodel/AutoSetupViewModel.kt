package com.omnillm.features.autosetup.viewmodel

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.autosetup.api.AutoSetupApi
import com.omnillm.features.autosetup.domain.AutomatedConfiguration
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.RecommendationResult
import com.omnillm.features.autosetup.domain.SetupJourneyPhases
import com.omnillm.features.autosetup.domain.SetupJourneySnapshot
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.features.autosetup.ports.AcquisitionClaim
import com.omnillm.features.autosetup.ports.FirstInferenceClaim
import com.omnillm.features.autosetup.projection.AutoSetupStateProjection
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UI-facing state holder for FEAT-AUTOSETUP (INV-001: no engines / no DB writes).
 *
 * Pure Kotlin — not AndroidX ViewModel — so `:features:auto-setup` stays JVM-only.
 * `:android:app-ui` can wrap this with lifecycle owners later.
 */
class AutoSetupViewModel(
    private val api: AutoSetupApi,
) {
    private val listeners = CopyOnWriteArrayList<(AutoSetupUiState) -> Unit>()

    @Volatile
    private var state: AutoSetupUiState = AutoSetupUiState.from(api.journey())

    fun uiState(): AutoSetupUiState = state

    fun observe(listener: (AutoSetupUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners.remove(listener) }
    }

    suspend fun onDiscoverDevice(): OmniResult<DeviceDiscoverySnapshot> {
        publishBusy("discovering device")
        val result = api.discoverDevice()
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onRecommend(preferences: UserSetupPreferences): OmniResult<RecommendationResult> {
        publishBusy("recommending models")
        val result = api.recommend(preferences)
        publishFromApi(result.errorOrNull())
        return result
    }

    fun onSelectCandidate(candidateId: String): OmniResult<AutomatedConfiguration> {
        val result = api.selectCandidate(candidateId)
        publishFromApi(result.errorOrNull())
        return result
    }

    fun onStartAcquisition(
        claim: AcquisitionClaim,
        sourceKind: String,
        parameters: JobParameters,
    ): OmniResult<*> {
        val result = api.startAcquisition(claim, sourceKind, parameters)
        publishFromApi(result.errorOrNull())
        return result
    }

    fun onRefreshJob(): OmniResult<*> {
        val result = api.refreshJob()
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onPlanFirstInference(request: OrchestrationRequest): OmniResult<PlanningResult> {
        publishBusy("planning first inference")
        val result = api.planFirstInference(request)
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onSubmitFirstInference(
        claim: FirstInferenceClaim,
        request: OrchestrationRequest,
    ): OmniResult<SubmitResult> {
        publishBusy("first inference")
        val result = api.submitFirstInference(claim, request)
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onRefreshRequest(): OmniResult<String> {
        val result = api.refreshRequest()
        publishFromApi(result.errorOrNull())
        return result
    }

    suspend fun onCancel(principalId: PrincipalId = AutoSetupApi.claimPrincipalOrLocal()): OmniResult<SetupJourneySnapshot> {
        val result = api.cancel(principalId)
        publishFromApi(result.errorOrNull())
        return result
    }

    fun onReset() {
        api.resetJourney()
        publishFromApi(null)
    }

    private fun publishBusy(label: String) {
        val journey = api.journey()
        publish(
            AutoSetupUiState.from(journey).copy(
                busy = true,
                busyLabel = label,
            ),
        )
    }

    private fun publishFromApi(error: OmniError?) {
        val journey = api.journey()
        publish(AutoSetupUiState.from(journey, error ?: journey.error))
    }

    private fun publish(next: AutoSetupUiState) {
        state = next
        for (l in listeners) l(next)
    }
}

/**
 * Stable UI projection: journey phase + human labels + actions.
 * Does not embed secrets, private paths, or raw prompts (FEAT-AUTOSETUP §7).
 */
data class AutoSetupUiState(
    val phase: String,
    val phaseLabelKey: String,
    val humanDeviceSummary: String?,
    val recommendationCount: Int,
    val rejectedCount: Int,
    val topCandidateId: String?,
    val topCandidateName: String?,
    val topReasonCodes: List<String>,
    val configurationSummary: String?,
    val jobState: String?,
    val jobStateLabelKey: String?,
    val installationState: String?,
    val installationStateLabelKey: String?,
    val requestState: String?,
    val requestStateLabelKey: String?,
    val progressRatio: Double?,
    val progressPhase: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val minimalAdjustments: List<String>,
    val actions: List<String>,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val isTerminal: Boolean,
) {
    companion object {
        fun from(journey: SetupJourneySnapshot, error: OmniError? = journey.error): AutoSetupUiState {
            val top = journey.recommendation?.top
            val cfg = journey.configuration
            val err = error ?: journey.error
            return AutoSetupUiState(
                phase = journey.phase,
                phaseLabelKey = phaseLabelKey(journey.phase),
                humanDeviceSummary = journey.device?.humanSummary(),
                recommendationCount = journey.recommendation?.ranked?.size ?: 0,
                rejectedCount = journey.recommendation?.rejected?.size ?: 0,
                topCandidateId = top?.candidate?.candidateId,
                topCandidateName = top?.candidate?.displayName,
                topReasonCodes = top?.reasonCodes?.map { it.code }.orEmpty(),
                configurationSummary = cfg?.let {
                    "${it.displayName} · ${it.backend.value} · ctx=${it.contextTokens.value}"
                },
                jobState = journey.jobState,
                jobStateLabelKey = journey.jobState?.let { AutoSetupStateProjection.jobStateLabelKey(it) },
                installationState = journey.installationState,
                installationStateLabelKey = journey.installationState?.let {
                    AutoSetupStateProjection.installationStateLabelKey(it)
                },
                requestState = journey.requestState,
                requestStateLabelKey = journey.requestState?.let {
                    AutoSetupStateProjection.requestStateLabelKey(it)
                },
                progressRatio = journey.progressRatio,
                progressPhase = journey.progressPhase,
                errorCode = err?.code?.code,
                errorMessage = err?.message,
                minimalAdjustments = journey.recommendation?.minimalViableAdjustments.orEmpty(),
                actions = actionsFor(journey),
                isTerminal = journey.isTerminal,
            )
        }

        private fun phaseLabelKey(phase: String): String = when (phase) {
            SetupJourneyPhases.IDLE -> "setup.idle"
            SetupJourneyPhases.DISCOVERING_DEVICE -> "setup.discovering-device"
            SetupJourneyPhases.DEVICE_READY -> "setup.device-ready"
            SetupJourneyPhases.RECOMMENDING -> "setup.recommending"
            SetupJourneyPhases.RECOMMENDATION_READY -> "setup.recommendation-ready"
            SetupJourneyPhases.AWAITING_SOURCE_SELECTION -> "setup.awaiting-source"
            SetupJourneyPhases.ACQUIRING -> "setup.acquiring"
            SetupJourneyPhases.INSTALLING -> "setup.installing"
            SetupJourneyPhases.CONFIGURING -> "setup.configuring"
            SetupJourneyPhases.PLANNING_LOAD -> "setup.planning-load"
            SetupJourneyPhases.FIRST_INFERENCE -> "setup.first-inference"
            SetupJourneyPhases.COMPLETED -> "setup.completed"
            SetupJourneyPhases.FAILED -> "setup.failed"
            SetupJourneyPhases.CANCELLED -> "setup.cancelled"
            else -> "setup.unknown"
        }

        private fun actionsFor(journey: SetupJourneySnapshot): List<String> = when (journey.phase) {
            SetupJourneyPhases.IDLE -> listOf("discover-device")
            SetupJourneyPhases.DEVICE_READY -> listOf("set-preferences", "recommend")
            SetupJourneyPhases.RECOMMENDATION_READY -> listOf("select-candidate", "recommend-again")
            SetupJourneyPhases.AWAITING_SOURCE_SELECTION,
            SetupJourneyPhases.CONFIGURING,
            -> listOf("start-download", "start-import", "select-another")
            SetupJourneyPhases.ACQUIRING, SetupJourneyPhases.INSTALLING -> listOf("cancel", "wait")
            SetupJourneyPhases.PLANNING_LOAD -> listOf("submit-first-inference", "cancel")
            SetupJourneyPhases.FIRST_INFERENCE -> listOf("cancel", "view-details")
            SetupJourneyPhases.FAILED -> listOf("view-reason", "adjust", "reset")
            SetupJourneyPhases.CANCELLED -> listOf("reset")
            SetupJourneyPhases.COMPLETED -> listOf("open-dashboard", "open-playground")
            else -> listOf("wait")
        }
    }
}
