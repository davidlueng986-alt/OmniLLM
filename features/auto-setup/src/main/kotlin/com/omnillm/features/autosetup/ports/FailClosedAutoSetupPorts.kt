package com.omnillm.features.autosetup.ports

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * UI-side fail-closed model / orchestrator ports (INV-001).
 *
 * Installation discover and first-inference execute stay on the control plane.
 * Admin DOWNLOAD job + offline fixture catalog cover software acquisition E2E.
 */
object FailClosedAutoSetupModelPort : AutoSetupModelPort {
    override suspend fun getInstallation(installationId: InstallationId): InstallationSnapshot? = null

    override suspend fun discoverInstallation(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<InstallationSnapshot> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "installation discover is control-plane only; use Admin DOWNLOAD job",
            ),
        )
}

object FailClosedAutoSetupOrchestratorPort : AutoSetupOrchestratorPort {
    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "first inference plan requires control-plane Orchestrator",
            ),
        )

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "first inference submit requires control-plane Orchestrator",
            ),
        )

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> =
        OmniResult.err(OmniError.NOT_FOUND(message = "no active request"))

    override suspend fun queryRequestState(requestId: RequestId): OmniResult<String> =
        OmniResult.err(OmniError.NOT_FOUND(message = "no active request"))
}
