package com.omnillm.features.autosetup.usecase

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.state.domain.InstallationId as StateInstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.autosetup.api.AutoSetupApi
import com.omnillm.features.autosetup.config.AutomatedConfigurationBuilder
import com.omnillm.features.autosetup.domain.AutomatedConfiguration
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.ModelSourceKinds
import com.omnillm.features.autosetup.domain.RecommendationResult
import com.omnillm.features.autosetup.domain.SetupJourneyPhases
import com.omnillm.features.autosetup.domain.SetupJourneySnapshot
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.features.autosetup.ports.AcquisitionClaim
import com.omnillm.features.autosetup.ports.AutoSetupRuntimePorts
import com.omnillm.features.autosetup.ports.FirstInferenceClaim
import com.omnillm.features.autosetup.projection.AutoSetupStateProjection
import com.omnillm.features.autosetup.ranking.RecommendationRanker
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Default [AutoSetupApi] implementation (FEAT-AUTOSETUP use-cases).
 *
 * Holds journey projection state only — durable mutations go through ports
 * (Job / Model / Orchestrator) owned by the runtime control plane (ADR-010).
 */
class AutoSetupService(
    private val ports: AutoSetupRuntimePorts,
) : AutoSetupApi {

    private val lock = Any()

    private var phase: String = SetupJourneyPhases.IDLE
    private var device: DeviceDiscoverySnapshot? = null
    private var recommendation: RecommendationResult? = null
    private var selectedCandidateId: String? = null
    private var configuration: AutomatedConfiguration? = null
    private var job: JobRecord? = null
    private var installationId: String? = null
    private var installationState: String? = null
    private var requestId: String? = null
    private var requestState: String? = null
    private var lastError: OmniError? = null
    private var cancelRequested: Boolean = false

    override fun journey(): SetupJourneySnapshot = synchronized(lock) { projectLocked() }

    override suspend fun discoverDevice(): OmniResult<DeviceDiscoverySnapshot> {
        setPhase(SetupJourneyPhases.DISCOVERING_DEVICE, clearError = true)
        return when (val r = ports.deviceProbe.discover()) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    device = r.value
                    lastError = null
                    reprojectLocked()
                }
                OmniResult.ok(r.value)
            }
            is OmniResult.Err -> {
                failJourney(r.error)
                r
            }
        }
    }

    override suspend fun recommend(
        preferences: UserSetupPreferences,
    ): OmniResult<RecommendationResult> {
        val dev = synchronized(lock) { device }
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "discover device before recommend"),
            )
        setPhase(SetupJourneyPhases.RECOMMENDING, clearError = true)
        return when (val listed = ports.catalog.listCandidates(preferences.targetOperation.id)) {
            is OmniResult.Err -> {
                failJourney(listed.error)
                listed
            }
            is OmniResult.Ok -> {
                val result = RecommendationRanker.rank(
                    device = dev,
                    preferences = preferences,
                    candidates = listed.value,
                    producedAtEpochMs = ports.clockMs(),
                )
                synchronized(lock) {
                    recommendation = result
                    selectedCandidateId = null
                    configuration = null
                    lastError = if (!result.hasViable) {
                        OmniError.ADMISSION_REJECTED(
                            message = "no viable recommendation candidate",
                            details = mapOf(
                                "rejectedCount" to result.rejected.size.toString(),
                                "adjustments" to result.minimalViableAdjustments.joinToString(","),
                            ),
                        )
                    } else {
                        null
                    }
                    reprojectLocked()
                }
                if (!result.hasViable) {
                    OmniResult.err(
                        OmniError.ADMISSION_REJECTED(
                            message = "no viable recommendation candidate",
                            details = mapOf(
                                "rejectedCount" to result.rejected.size.toString(),
                            ),
                        ),
                    )
                } else {
                    OmniResult.ok(result)
                }
            }
        }
    }

    override fun selectCandidate(candidateId: String): OmniResult<AutomatedConfiguration> {
        val rec = synchronized(lock) { recommendation }
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "recommend before select"),
            )
        val dev = synchronized(lock) { device }
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "device fingerprint required"),
            )
        val ranked = rec.ranked.firstOrNull { it.candidate.candidateId == candidateId }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "candidate not in recommendation set",
                    details = mapOf("candidateId" to candidateId),
                ),
            )
        val config = AutomatedConfigurationBuilder.build(
            candidate = ranked.candidate,
            device = dev,
            preferences = rec.preferences,
        )
        synchronized(lock) {
            selectedCandidateId = candidateId
            configuration = config
            lastError = null
            reprojectLocked()
        }
        return OmniResult.ok(config)
    }

    override fun startAcquisition(
        claim: AcquisitionClaim,
        sourceKind: String,
        parameters: JobParameters,
    ): OmniResult<JobRecord> {
        if (!ModelSourceKinds.isKnown(sourceKind)) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown model source kind (fail closed)",
                    details = mapOf("sourceKind" to sourceKind),
                ),
            )
        }
        val selected = synchronized(lock) { selectedCandidateId }
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "select candidate before acquisition"),
            )
        val kind = when (parameters) {
            is JobParameters.Download -> JobKind.DOWNLOAD
            is JobParameters.Import -> JobKind.IMPORT
            else -> return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "auto-setup acquisition accepts DOWNLOAD or IMPORT only",
                ),
            )
        }
        // Catalog / pinned download → DOWNLOAD; SAF import → IMPORT
        if (sourceKind == ModelSourceKinds.SAF_IMPORT && kind != JobKind.IMPORT) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "SAF_IMPORT requires IMPORT job parameters"),
            )
        }
        if (sourceKind != ModelSourceKinds.SAF_IMPORT && kind != JobKind.DOWNLOAD) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "CATALOG/PINNED_DOWNLOAD require DOWNLOAD job parameters",
                ),
            )
        }

        val identity = JobIdentity(
            jobId = claim.jobId,
            principalId = claim.principalId,
            kind = kind,
            idempotencyKey = claim.idempotencyKey,
            canonicalSpecDigest = claim.canonicalSpecDigest,
        )
        return when (val created = ports.jobs.create(identity, parameters)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    job = created.value
                    cancelRequested = false
                    lastError = null
                    // Keep selectedCandidateId for journey context
                    @Suppress("UNUSED_VARIABLE")
                    val _sel = selected
                    reprojectLocked()
                }
                OmniResult.ok(created.value)
            }
            is OmniResult.Err -> {
                failJourney(created.error)
                created
            }
        }
    }

    override fun refreshJob(): OmniResult<JobRecord> {
        val id = synchronized(lock) { job?.jobId }
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "no active acquisition job"))
        return when (val q = ports.jobs.query(id)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    job = q.value
                    if (q.value.state == "FAILED") {
                        lastError = q.value.error
                            ?: OmniError.INTERNAL(message = "job failed")
                    }
                    reprojectLocked()
                }
                OmniResult.ok(q.value)
            }
            is OmniResult.Err -> q
        }
    }

    override suspend fun refreshInstallation(installationId: InstallationId): OmniResult<String> {
        val stateId = try {
            StateInstallationId(installationId.value)
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = e.message ?: "invalid installationId"),
            )
        }
        val snap = ports.models.getInstallation(stateId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "installation not found",
                    details = mapOf("installationId" to installationId.value),
                ),
            )
        synchronized(lock) {
            this.installationId = snap.installationId.value
            installationState = snap.state
            reprojectLocked()
        }
        return OmniResult.ok(snap.state)
    }

    override suspend fun planFirstInference(
        request: OrchestrationRequest,
    ): OmniResult<PlanningResult> {
        setPhase(SetupJourneyPhases.PLANNING_LOAD, clearError = true)
        return when (val plan = ports.orchestrator.plan(request)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    requestId = request.requestId.value
                    // Pure plan does not claim REQUEST state; keep journey at PLANNING_LOAD
                    lastError = null
                    reprojectLocked()
                }
                OmniResult.ok(plan.value)
            }
            is OmniResult.Err -> {
                failJourney(plan.error)
                plan
            }
        }
    }

    override suspend fun submitFirstInference(
        claim: FirstInferenceClaim,
        request: OrchestrationRequest,
    ): OmniResult<SubmitResult> {
        // Claim ids on request must match client envelope (ADR-004/005).
        if (request.requestId.value != claim.requestId.value ||
            request.idempotencyKey.value != claim.idempotencyKey.value ||
            request.principalId.value != claim.principalId.value
        ) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "first-inference claim does not match OrchestrationRequest",
                ),
            )
        }
        setPhase(SetupJourneyPhases.FIRST_INFERENCE, clearError = true)
        return when (val sub = ports.orchestrator.submit(request)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    requestId = sub.value.requestId.value
                    requestState = sub.value.state
                    lastError = null
                    reprojectLocked()
                }
                OmniResult.ok(sub.value)
            }
            is OmniResult.Err -> {
                failJourney(sub.error)
                sub
            }
        }
    }

    override suspend fun refreshRequest(): OmniResult<String> {
        val id = synchronized(lock) { requestId }
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "no active request"))
        return when (
            val q = ports.orchestrator.queryRequestState(
                com.omnillm.core.contracts.RequestId.parse(id),
            )
        ) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    requestState = q.value
                    reprojectLocked()
                }
                OmniResult.ok(q.value)
            }
            is OmniResult.Err -> q
        }
    }

    override suspend fun cancel(principalId: PrincipalId): OmniResult<SetupJourneySnapshot> {
        cancelRequested = true
        var any = false
        val jobId = synchronized(lock) { job?.jobId }
        if (jobId != null) {
            when (val c = ports.jobs.cancel(jobId)) {
                is OmniResult.Ok -> {
                    synchronized(lock) { job = c.value }
                    any = true
                }
                is OmniResult.Err -> {
                    // Already terminal CANCELLED is ok; other errors surface if nothing else.
                    if (c.error.code.code != "STATE_CONFLICT") {
                        // still try request cancel
                    }
                }
            }
        }
        val reqId = synchronized(lock) { requestId }
        if (reqId != null) {
            when (
                val c = ports.orchestrator.cancel(
                    com.omnillm.core.contracts.RequestId.parse(reqId),
                )
            ) {
                is OmniResult.Ok -> {
                    synchronized(lock) { requestState = "CANCELLED" }
                    any = true
                }
                is OmniResult.Err -> Unit
            }
        }
        if (!any && jobId == null && reqId == null) {
            // Soft cancel of local journey when no durable work.
            synchronized(lock) {
                phase = SetupJourneyPhases.CANCELLED
                lastError = OmniError.CANCELLED(message = "setup journey cancelled")
                reprojectLocked()
            }
            return OmniResult.ok(journey())
        }
        synchronized(lock) {
            reprojectLocked()
        }
        return OmniResult.ok(journey())
    }

    override fun resetJourney() {
        synchronized(lock) {
            phase = SetupJourneyPhases.IDLE
            device = null
            recommendation = null
            selectedCandidateId = null
            configuration = null
            job = null
            installationId = null
            installationState = null
            requestId = null
            requestState = null
            lastError = null
            cancelRequested = false
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun setPhase(next: String, clearError: Boolean) {
        synchronized(lock) {
            phase = next
            if (clearError) lastError = null
            reprojectLocked()
        }
    }

    private fun failJourney(error: OmniError) {
        synchronized(lock) {
            lastError = error
            if (error.code.code == "CANCELLED") {
                phase = SetupJourneyPhases.CANCELLED
            } else {
                phase = SetupJourneyPhases.FAILED
            }
            reprojectLocked()
        }
    }

    private fun reprojectLocked() {
        val snap = projectLocked()
        phase = snap.phase
    }

    private fun projectLocked(): SetupJourneySnapshot =
        AutoSetupStateProjection.project(
            previousPhase = phase,
            device = device,
            recommendation = recommendation,
            selectedCandidateId = selectedCandidateId,
            configuration = configuration,
            job = job,
            installationState = installationState,
            installationId = installationId,
            requestId = requestId,
            requestState = requestState,
            cancelRequested = cancelRequested,
            error = lastError,
            reasonCodes = recommendation?.top?.reasonCodes.orEmpty(),
            nowEpochMs = ports.clockMs(),
        )
}
