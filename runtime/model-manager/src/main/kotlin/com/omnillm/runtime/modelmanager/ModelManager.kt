package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.LoadedModelRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort

/**
 * Model Manager facade (AGENTS.md `:runtime:model-manager`).
 *
 * Responsibilities:
 * - Acquisition, quarantine → verify → atomic promote → READY
 * - Trust evaluation dimensions (ADR-009) without compatibility promotion
 * - LoadedModel lifecycle coordination via [EngineLoadPort.planLoad]/[EngineLoadPort.commitLoad]
 * - Privileged load re-verify hooks (INV-010)
 * - RevisionLease grant/release for delete fencing
 *
 * Does **not** load native engines; engine work is ported.
 * Does **not** accept UI-process writes (INV-001 / ADR-010).
 */
class ModelManager(
    private val installationCoordinator: InstallationCoordinator,
    private val loadCoordinator: LoadCoordinator,
    private val revisionLeaseService: RevisionLeaseService,
    private val installationRepository: InstallationRepository,
    private val loadedModelRepository: LoadedModelRepository,
    private val revisionLeaseRepository: RevisionLeaseRepository,
) {

    // --- Installation ---

    suspend fun discoverInstallation(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.discover(installationId, modelRevisionId, artifactPackageId)

    suspend fun beginAcquire(
        installationId: InstallationId,
        quarantineKey: QuarantineKey,
        declared: List<DeclaredArtifactFile>,
        deadlineMonotonic: Long,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.beginAcquire(
            installationId,
            quarantineKey,
            declared,
            deadlineMonotonic,
        )

    suspend fun materializeComplete(
        installationId: InstallationId,
        files: List<QuarantineFileRecord>,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.materializeComplete(installationId, files)

    suspend fun beginVerify(installationId: InstallationId): OmniResult<InstallationSnapshot> =
        installationCoordinator.beginVerify(installationId)

    suspend fun completeIdentityVerify(installationId: InstallationId): OmniResult<InstallationSnapshot> =
        installationCoordinator.identityVerifiedOk(installationId)

    suspend fun promoteToReady(installationId: InstallationId): OmniResult<InstallationSnapshot> =
        installationCoordinator.promoteToReady(installationId)

    suspend fun requestInstallationDelete(installationId: InstallationId): OmniResult<InstallationSnapshot> =
        installationCoordinator.requestDelete(installationId)

    suspend fun completeInstallationDrain(
        installationId: InstallationId,
        outcomeEvent: String,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.completeDrain(installationId, outcomeEvent)

    suspend fun commitInstallationDelete(installationId: InstallationId): OmniResult<InstallationSnapshot> =
        installationCoordinator.commitDelete(installationId)

    suspend fun cancelInFlightInstallation(
        installationId: InstallationId,
        reason: String,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.cancelInFlight(installationId, reason)

    suspend fun markAcquireFailed(
        installationId: InstallationId,
        reason: String,
    ): OmniResult<InstallationSnapshot> =
        installationCoordinator.acquireFailed(installationId, reason)

    /**
     * Pin blocks automatic eviction only — not resource caps or explicit delete drain
     * (CORE-MODEL §9 / FEAT-MODELHUB).
     */
    suspend fun setInstallationPinned(
        installationId: InstallationId,
        pinned: Boolean,
    ): OmniResult<InstallationSnapshot> {
        val current = installationRepository.get(installationId)
            ?: return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                    message = "installation not found",
                    details = mapOf("installationId" to installationId.value),
                ),
            )
        val next = current.copy(pinned = pinned)
        return when (val saved = installationRepository.save(next)) {
            is OmniResult.Ok -> OmniResult.ok(next)
            is OmniResult.Err -> saved
        }
    }

    suspend fun getInstallation(installationId: InstallationId): InstallationSnapshot? =
        installationRepository.get(installationId)

    suspend fun listInstallations(): List<InstallationSnapshot> =
        installationRepository.listAll()

    suspend fun findInstallationsByRevision(modelRevisionId: ModelRevisionId): List<InstallationSnapshot> =
        installationRepository.findByRevision(modelRevisionId)

    // --- Load ---

    suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> =
        loadCoordinator.planLoad(input)

    suspend fun admitLoad(
        loadedModelId: LoadedModelId,
        installationId: InstallationId,
        loadKey: LoadKey,
        plan: LoadPlan,
        loadEnvelopeMatched: Boolean,
    ): OmniResult<LoadedModelSnapshot> =
        loadCoordinator.admit(
            loadedModelId,
            installationId,
            loadKey,
            plan,
            loadEnvelopeMatched,
        )

    suspend fun preparePrivilegedTicket(
        installationId: InstallationId,
        engineBuildId: String,
        revocationEpoch: Long,
    ): OmniResult<PrivilegedLoadTicket> =
        loadCoordinator.preparePrivilegedTicket(installationId, engineBuildId, revocationEpoch)

    suspend fun commitLoad(
        loadedModelId: LoadedModelId,
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
        placementQualified: Boolean,
        loadEnvelopeMatched: Boolean,
        revocationEpoch: Long,
    ): OmniResult<LoadedModelSnapshot> =
        loadCoordinator.commitLoad(
            loadedModelId,
            plan,
            reservation,
            commit,
            placementQualified,
            loadEnvelopeMatched,
            revocationEpoch,
        )

    suspend fun drainLoadedModel(loadedModelId: LoadedModelId): OmniResult<LoadedModelSnapshot> =
        loadCoordinator.requestDrain(loadedModelId)

    suspend fun unloadWhenQuiescent(loadedModelId: LoadedModelId): OmniResult<LoadedModelSnapshot> =
        loadCoordinator.quiesceToUnload(loadedModelId)

    /**
     * Installation entering DRAINING must fence new loads and drain LoadedModels.
     */
    suspend fun onInstallationDraining(installationId: InstallationId): OmniResult<List<LoadedModelSnapshot>> =
        loadCoordinator.drainForInstallation(installationId)

    suspend fun getLoadedModel(loadedModelId: LoadedModelId): LoadedModelSnapshot? =
        loadedModelRepository.get(loadedModelId)

    // --- Revision lease ---

    suspend fun grantRevisionLease(
        leaseId: RevisionLeaseId,
        requestId: RequestId,
        modelRevisionId: ModelRevisionId,
        principalId: String,
        runtimeEpoch: Long,
        installationId: InstallationId? = null,
        expiresAtMonotonic: Long? = null,
    ): OmniResult<RevisionLeaseSnapshot> =
        revisionLeaseService.grant(
            leaseId,
            requestId,
            modelRevisionId,
            principalId,
            runtimeEpoch,
            installationId,
            expiresAtMonotonic,
        )

    suspend fun beginLeaseRelease(leaseId: RevisionLeaseId): OmniResult<RevisionLeaseSnapshot> =
        revisionLeaseService.beginReleaseOrExpiry(leaseId)

    suspend fun completeLeaseRelease(leaseId: RevisionLeaseId): OmniResult<RevisionLeaseSnapshot> =
        revisionLeaseService.completeRelease(leaseId)

    suspend fun isRevisionPinnedByLease(modelRevisionId: ModelRevisionId): Boolean =
        revisionLeaseService.isRevisionPinned(modelRevisionId)

    suspend fun getRevisionLease(leaseId: RevisionLeaseId): RevisionLeaseSnapshot? =
        revisionLeaseRepository.get(leaseId)

    companion object {
        /**
         * Wire control-plane dependencies. Persistence + model store + engine
         * adapters are injected; no Android types here.
         */
        fun create(
            installationRepository: InstallationRepository,
            loadedModelRepository: LoadedModelRepository,
            revisionLeaseRepository: RevisionLeaseRepository,
            modelStore: ModelStorePort,
            trustEvaluation: TrustEvaluationPort,
            references: ReferenceSnapshotPort,
            engine: EngineLoadPort,
            privilegedReverify: PrivilegedLoadReverifyPort,
        ): ModelManager {
            val installations = InstallationCoordinator(
                repository = installationRepository,
                modelStore = modelStore,
                trustEvaluation = trustEvaluation,
                references = references,
            )
            val gate = PrivilegedLoadGate(
                installations = installationRepository,
                reverify = privilegedReverify,
            )
            val loads = LoadCoordinator(
                installations = installationRepository,
                loadedModels = loadedModelRepository,
                engine = engine,
                privilegedGate = gate,
                references = references,
            )
            val leases = RevisionLeaseService(
                repository = revisionLeaseRepository,
                references = references,
            )
            return ModelManager(
                installationCoordinator = installations,
                loadCoordinator = loads,
                revisionLeaseService = leases,
                installationRepository = installationRepository,
                loadedModelRepository = loadedModelRepository,
                revisionLeaseRepository = revisionLeaseRepository,
            )
        }
    }
}
