package com.omnillm.features.autosetup.wiring

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId as StateInstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.autosetup.ports.AutoSetupJobPort
import com.omnillm.features.autosetup.ports.AutoSetupModelPort
import com.omnillm.features.autosetup.ports.AutoSetupOrchestratorPort
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Thin adapters from runtime control-plane types to auto-setup ports.
 * Host wiring (runtime-service) constructs these; feature stays process-agnostic.
 */

class JobManagerAutoSetupPort(
    private val jobManager: JobManager,
) : AutoSetupJobPort {
    override fun create(identity: JobIdentity, parameters: JobParameters): OmniResult<JobRecord> =
        when (val r = jobManager.create(identity, parameters)) {
            is OmniResult.Ok -> OmniResult.ok(r.value.record)
            is OmniResult.Err -> OmniResult.err(r.error)
        }

    override fun query(jobId: JobId): OmniResult<JobRecord> = jobManager.query(jobId)

    override fun cancel(jobId: JobId, requestOnly: Boolean): OmniResult<JobRecord> =
        jobManager.cancel(jobId, requestOnly)
}

class ModelManagerAutoSetupPort(
    private val modelManager: ModelManager,
) : AutoSetupModelPort {
    override suspend fun getInstallation(installationId: StateInstallationId): InstallationSnapshot? =
        modelManager.getInstallation(installationId)

    override suspend fun discoverInstallation(
        installationId: StateInstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<InstallationSnapshot> =
        modelManager.discoverInstallation(installationId, modelRevisionId, artifactPackageId)
}

class OrchestratorAutoSetupPort(
    private val orchestrator: Orchestrator,
    private val planner: suspend (OrchestrationRequest) -> OmniResult<PlanningResult>,
) : AutoSetupOrchestratorPort {
    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> =
        planner(request)

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> =
        orchestrator.submit(request)

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> =
        orchestrator.cancel(requestId)

    override suspend fun queryRequestState(requestId: RequestId): OmniResult<String> {
        val view = orchestrator.query(requestId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to requestId.value),
                ),
            )
        return OmniResult.ok(view.terminalState ?: view.state)
    }

    companion object {
        /**
         * Wire Orchestrator + its [com.omnillm.runtime.orchestrator.CandidatePlanner]
         * for pure plan without enqueue.
         */
        fun from(
            orchestrator: Orchestrator,
            planOnly: suspend (OrchestrationRequest) -> OmniResult<PlanningResult>,
        ): OrchestratorAutoSetupPort = OrchestratorAutoSetupPort(orchestrator, planOnly)
    }
}
