package com.omnillm.features.autosetup.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.identity.InstallationId
import com.omnillm.features.autosetup.domain.AutomatedConfiguration
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.RecommendationResult
import com.omnillm.features.autosetup.domain.SetupJourneySnapshot
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.features.autosetup.ports.AcquisitionClaim
import com.omnillm.features.autosetup.ports.FirstInferenceClaim
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Public API for UI / Admin (FEAT-AUTOSETUP).
 *
 * Semantics:
 * - Discovery / recommend / configure are **plan-pure** (no domain mutation beyond
 *   optional installation DISCOVERED under Model port single-writer).
 * - Acquisition uses Job claim-or-return (DOWNLOAD / IMPORT).
 * - First inference uses Orchestrator submit (Plan → Reserve → Commit → Execute).
 * - Client generates requestId / jobId / idempotencyKey (ADR-004/005).
 * - Cancel / query on reply loss; never blind replay.
 */
interface AutoSetupApi {

    /** Current journey projection for UI binding. */
    fun journey(): SetupJourneySnapshot

    /**
     * DEVICE_DISCOVERY: build DeviceExecutionFingerprint + resource snapshots.
     * Updates journey to DEVICE_READY or FAILED.
     */
    suspend fun discoverDevice(): OmniResult<DeviceDiscoverySnapshot>

    /**
     * RECOMMENDATION: rank catalog candidates against device + preferences.
     * Pure ranking over catalog port results (ADR-002).
     */
    suspend fun recommend(preferences: UserSetupPreferences): OmniResult<RecommendationResult>

    /**
     * Select a ranked candidate (by candidateId) and produce automated configuration.
     * Does not download or load.
     */
    fun selectCandidate(candidateId: String): OmniResult<AutomatedConfiguration>

    /**
     * Start DOWNLOAD or IMPORT job for the selected candidate (SAFE_INSTALLATION path).
     * [sourceKind] is CATALOG / PINNED_DOWNLOAD / SAF_IMPORT (ModelSourceKinds).
     * [parameters] must match JobKind DOWNLOAD or IMPORT.
     */
    fun startAcquisition(
        claim: AcquisitionClaim,
        sourceKind: String,
        parameters: JobParameters,
    ): OmniResult<JobRecord>

    /** Poll acquisition job; refreshes journey projection from JOB FSM. */
    fun refreshJob(): OmniResult<JobRecord>

    /**
     * Optional: record installation state observed from Model port
     * (MODEL_INSTALLATION FSM projection).
     */
    suspend fun refreshInstallation(installationId: InstallationId): OmniResult<String>

    /**
     * Pure plan for first inference candidates (no execute).
     * Caller builds [OrchestrationRequest] with client-generated ids.
     */
    suspend fun planFirstInference(request: OrchestrationRequest): OmniResult<PlanningResult>

    /**
     * Submit first inference through Orchestrator (full claim → plan → queue).
     * Does not special-case the path — same scheduler / stream / error (FEAT-AUTOSETUP §3.7).
     */
    suspend fun submitFirstInference(
        claim: FirstInferenceClaim,
        request: OrchestrationRequest,
    ): OmniResult<SubmitResult>

    /** Refresh request state for journey projection (reply-loss safe). */
    suspend fun refreshRequest(): OmniResult<String>

    /**
     * Cancel in-flight job and/or request. Fail closed when nothing active.
     * Prefer query after cancel for durable terminal (ADR-004/005).
     */
    suspend fun cancel(principalId: PrincipalId = claimPrincipalOrLocal()): OmniResult<SetupJourneySnapshot>

    /** Reset journey to IDLE (local UI projection only — does not delete durable jobs). */
    fun resetJourney()

    companion object {
        /** Default principal used when cancel has no active claim context. */
        fun claimPrincipalOrLocal(): PrincipalId = PrincipalId.parse("local-ui")
    }
}
