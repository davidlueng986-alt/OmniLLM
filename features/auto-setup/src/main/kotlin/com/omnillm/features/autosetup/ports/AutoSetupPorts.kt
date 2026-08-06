package com.omnillm.features.autosetup.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.ModelCandidateInput
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Runtime ports for FEAT-AUTOSETUP.
 *
 * Implementations live in the control plane (ADR-010). The feature module
 * never opens DB or loads native engines — it only composes these ports
 * (AGENTS.md Feature Pack rules).
 */
data class AutoSetupRuntimePorts(
    val deviceProbe: DeviceProbePort,
    val catalog: CatalogCandidatePort,
    val jobs: AutoSetupJobPort,
    val models: AutoSetupModelPort,
    val orchestrator: AutoSetupOrchestratorPort,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/** DEVICE_DISCOVERY capability port (platform adapter supplies facts). */
fun interface DeviceProbePort {
    suspend fun discover(): OmniResult<DeviceDiscoverySnapshot>
}

/** Catalog / local inventory of recommendation candidates (read-only). */
fun interface CatalogCandidatePort {
    suspend fun listCandidates(
        targetOperationId: String,
    ): OmniResult<List<ModelCandidateInput>>
}

/**
 * Job Manager surface used by auto-setup (DOWNLOAD / IMPORT only).
 * claim-or-return create + query + cancel (ADR-004/005).
 */
interface AutoSetupJobPort {
    fun create(identity: JobIdentity, parameters: JobParameters): OmniResult<JobRecord>

    fun query(jobId: JobId): OmniResult<JobRecord>

    fun cancel(jobId: JobId, requestOnly: Boolean = false): OmniResult<JobRecord>
}

/**
 * Model Manager surface for installation discovery / readiness queries.
 * Mutations that acquire still flow via Job workers + ModelManager in runtime.
 */
interface AutoSetupModelPort {
    suspend fun getInstallation(installationId: InstallationId): InstallationSnapshot?

    /**
     * Discover installation row for a revision (control-plane only).
     * Plan purity: discovery may create DISCOVERED state under single writer.
     */
    suspend fun discoverInstallation(
        installationId: InstallationId,
        modelRevisionId: com.omnillm.core.canonical.generated.ModelRevisionId,
        artifactPackageId: com.omnillm.core.canonical.generated.ArtifactPackageId,
    ): OmniResult<InstallationSnapshot>
}

/**
 * Orchestrator surface for pure plan + first-inference submit.
 * Plan has no domain mutation beyond claim ledger (ADR-002 for planner;
 * submit claims request ledger via registry).
 */
interface AutoSetupOrchestratorPort {
    /** Pure candidate plan (resource envelope); no execute. */
    suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult>

    /** Claim → plan → queue path for first inference (full pipeline). */
    suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult>

    /** Cancel in-flight request (fail closed if unknown). */
    suspend fun cancel(requestId: RequestId): OmniResult<Unit>

    /** Query request state for reply-loss / journey projection. */
    suspend fun queryRequestState(requestId: RequestId): OmniResult<String>
}

/**
 * Client-generated acquisition claim envelope (ADR-004/005).
 * UI / Admin generate [jobId] and [idempotencyKey] before send.
 */
data class AcquisitionClaim(
    val jobId: JobId,
    val principalId: PrincipalId,
    val idempotencyKey: IdempotencyKey,
    /** Canonical digest of acquisition parameters (hex SHA-256). */
    val canonicalSpecDigest: String,
)

/**
 * Client-generated first-inference claim (ADR-004/005).
 */
data class FirstInferenceClaim(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val idempotencyKey: IdempotencyKey,
    val canonicalRequestDigest: com.omnillm.core.canonical.generated.Sha256Digest,
    val deadlineMonotonic: Long,
    val runtimeEpoch: Long = 1L,
    val revocationEpoch: Long = 0L,
)
