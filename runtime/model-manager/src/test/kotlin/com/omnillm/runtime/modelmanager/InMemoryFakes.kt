package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.identity.InstallationId as IdentityInstallationId
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.data.modelstore.ContentIdentityCheck
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.PromoteResult
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.modelstore.QuarantineSnapshot
import com.omnillm.data.modelstore.ReadOnlyContentFd
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import com.omnillm.runtime.modelmanager.ports.LoadedModelRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort

internal class InMemoryInstallationRepository : InstallationRepository {
    private val map = linkedMapOf<String, InstallationSnapshot>()

    override suspend fun get(installationId: InstallationId): InstallationSnapshot? =
        map[installationId.value]

    override suspend fun findByRevision(modelRevisionId: ModelRevisionId): List<InstallationSnapshot> =
        map.values.filter { it.modelRevisionId.hex == modelRevisionId.hex }

    override suspend fun listAll(): List<InstallationSnapshot> = map.values.toList()

    override suspend fun save(snapshot: InstallationSnapshot): OmniResult<Unit> {
        map[snapshot.installationId.value] = snapshot
        return OmniResult.ok(Unit)
    }

    override suspend fun delete(installationId: InstallationId): OmniResult<Unit> {
        map.remove(installationId.value)
        return OmniResult.ok(Unit)
    }
}

internal class InMemoryLoadedModelRepository : LoadedModelRepository {
    private val map = linkedMapOf<String, LoadedModelSnapshot>()

    override suspend fun get(loadedModelId: LoadedModelId): LoadedModelSnapshot? =
        map[loadedModelId.value]

    override suspend fun findByInstallation(installationId: InstallationId): List<LoadedModelSnapshot> =
        map.values.filter { it.installationId.value == installationId.value }

    override suspend fun save(snapshot: LoadedModelSnapshot): OmniResult<Unit> {
        map[snapshot.loadedModelId.value] = snapshot
        return OmniResult.ok(Unit)
    }

    override suspend fun delete(loadedModelId: LoadedModelId): OmniResult<Unit> {
        map.remove(loadedModelId.value)
        return OmniResult.ok(Unit)
    }
}

internal class InMemoryRevisionLeaseRepository : RevisionLeaseRepository {
    private val map = linkedMapOf<String, RevisionLeaseSnapshot>()

    override suspend fun get(leaseId: RevisionLeaseId): RevisionLeaseSnapshot? =
        map[leaseId.value]

    override suspend fun findActiveByRevision(modelRevisionId: ModelRevisionId): List<RevisionLeaseSnapshot> =
        map.values.filter { it.modelRevisionId.hex == modelRevisionId.hex && it.blocksDelete() }

    override suspend fun save(snapshot: RevisionLeaseSnapshot): OmniResult<Unit> {
        map[snapshot.leaseId.value] = snapshot
        return OmniResult.ok(Unit)
    }

    override suspend fun delete(leaseId: RevisionLeaseId): OmniResult<Unit> {
        map.remove(leaseId.value)
        return OmniResult.ok(Unit)
    }
}

internal class MutableReferenceSnapshotPort : ReferenceSnapshotPort {
    var installationRefs: LiveReferences = LiveReferences()
    var loadedModelRefs: LiveReferences = LiveReferences()
    var leaseRefs: LiveReferences = LiveReferences()

    override suspend fun installationReferences(installationId: InstallationId): LiveReferences =
        installationRefs

    override suspend fun loadedModelReferences(loadedModelId: LoadedModelId): LiveReferences =
        loadedModelRefs

    override suspend fun leaseReferences(leaseId: RevisionLeaseId): LiveReferences = leaseRefs
}

internal class FixedTrustEvaluationPort(
    var dimensions: EvaluationDimensions = EvaluationDimensions(
        authenticityOk = true,
        licenseOk = true,
        compatibilityOk = true,
        performanceRecorded = false,
        placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
        trustEpoch = 1L,
    ),
) : TrustEvaluationPort {
    override suspend fun evaluate(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        currentTrustEpoch: Long,
    ): OmniResult<EvaluationDimensions> =
        OmniResult.ok(dimensions.copy(trustEpoch = currentTrustEpoch + 1))
}

internal class InMemoryModelStore : ModelStorePort {
    private val quarantines = linkedMapOf<String, QuarantineSnapshot>()
    private val ready = linkedMapOf<String, PromoteResult>()
    var identityOk: Boolean = true

    private fun keyOf(k: QuarantineKey) = "${k.jobId}/${k.attemptId}"

    override suspend fun openQuarantine(
        key: QuarantineKey,
        installationId: IdentityInstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
        declared: List<DeclaredArtifactFile>,
        deadlineMonotonic: Long,
    ): OmniResult<QuarantineSnapshot> {
        val snap = QuarantineSnapshot(
            key = key,
            installationId = installationId,
            modelRevisionId = modelRevisionId,
            artifactPackageId = artifactPackageId,
            files = emptyList(),
            deadlineMonotonic = deadlineMonotonic,
        )
        quarantines[keyOf(key)] = snap
        return OmniResult.ok(snap)
    }

    override suspend fun recordMaterialized(
        key: QuarantineKey,
        file: QuarantineFileRecord,
    ): OmniResult<QuarantineSnapshot> {
        val cur = quarantines[keyOf(key)]
            ?: return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.NOT_FOUND(message = "quarantine missing"),
            )
        val next = cur.copy(files = cur.files + file)
        quarantines[keyOf(key)] = next
        return OmniResult.ok(next)
    }

    override suspend fun getQuarantine(key: QuarantineKey): OmniResult<QuarantineSnapshot> {
        val cur = quarantines[keyOf(key)]
            ?: return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.NOT_FOUND(message = "quarantine missing"),
            )
        return OmniResult.ok(cur)
    }

    override suspend fun verifyQuarantineIdentity(key: QuarantineKey): OmniResult<ContentIdentityCheck> =
        OmniResult.ok(ContentIdentityCheck(ok = identityOk, failureReason = if (identityOk) null else "digest"))

    override suspend fun cleanupQuarantine(key: QuarantineKey): OmniResult<Unit> {
        quarantines.remove(keyOf(key))
        return OmniResult.ok(Unit)
    }

    override suspend fun atomicPromote(
        key: QuarantineKey,
        installationId: IdentityInstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<PromoteResult> {
        val result = PromoteResult(
            installationId = installationId,
            storageRootKey = "ready/${installationId.value}",
            promotedBlobIds = listOf(BlobId.parse("a".repeat(64))),
        )
        ready[installationId.value] = result
        quarantines.remove(keyOf(key))
        return OmniResult.ok(result)
    }

    override suspend fun openReadOnly(
        installationId: IdentityInstallationId,
        storageRootKey: String,
    ): OmniResult<List<ReadOnlyContentFd>> =
        OmniResult.ok(
            listOf(
                ReadOnlyContentFd(
                    role = "weights",
                    blobId = BlobId.parse("a".repeat(64)),
                    byteLength = 1L,
                    fdToken = "fd-1",
                ),
            ),
        )

    override suspend fun verifyOpenFds(fds: List<ReadOnlyContentFd>): OmniResult<ContentIdentityCheck> =
        OmniResult.ok(ContentIdentityCheck(ok = identityOk))

    override suspend fun closeFds(fds: List<ReadOnlyContentFd>): OmniResult<Unit> = OmniResult.ok(Unit)
}

internal class FakeEngineLoadPort : EngineLoadPort {
    override val engineBuildId: EngineBuildId = EngineBuildId.parse("engine-build-test-1")
    var commitSucceeds: Boolean = true
    var lastCommit: CommitContext? = null

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        val digest = Sha256Digest.parse("b".repeat(64))
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("plan-1"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadKey = input.loadKey,
                installationId = input.installationId,
                modelRevisionId = input.modelRevisionId,
                resourceEnvelope = ResourceEnvelope(
                    steady = ResourceVector(cpuAnonBytes = 1024),
                    peak = ResourceVector(cpuAnonBytes = 2048),
                ),
                proposedPlacementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                phaseCapabilityDigest = digest,
                canonicalInputDigest = digest,
                expiryMonotonic = 9999L,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    override suspend fun commitLoad(
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<LoadedModelHandle> {
        lastCommit = commit
        if (!commitSucceeds) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.INTERNAL(message = "engine commit failed"),
            )
        }
        return OmniResult.ok(
            LoadedModelHandle(
                loadedModelId = LoadedModelId("lm-from-engine"),
                installationId = plan.installationId,
                engineBuildId = plan.engineBuildId,
                loadKey = plan.loadKey,
                allocationHandleId = AllocationHandleId.parse("alloc-1"),
                placementClass = plan.proposedPlacementClass,
                runtimeEpoch = plan.runtimeEpoch,
            ),
        )
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        OmniResult.ok(CommitQueryState(commitId = commitId, state = "COMMITTED"))
}

internal class FakePrivilegedReverifyPort(
    var allOk: Boolean = true,
    var contentOk: Boolean = true,
    var revocationOk: Boolean = true,
) : PrivilegedLoadReverifyPort {
    var lastRequest: PrivilegedReverifyRequest? = null
    private var seq = 0

    override suspend fun reverify(request: PrivilegedReverifyRequest): OmniResult<PrivilegedLoadTicket> {
        lastRequest = request
        seq++
        return OmniResult.ok(
            PrivilegedLoadTicket(
                ticketId = "ticket-$seq",
                installationId = request.installationId,
                modelRevisionId = request.modelRevisionId,
                placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                contentIdentityOk = contentOk && allOk,
                signatureChainOk = allOk,
                revocationOk = revocationOk && allOk,
                installationStateOk = allOk,
                epochsOk = allOk,
                issuedMonotonic = 1L,
                expiryMonotonic = 1000L,
            ),
        )
    }
}

internal object TestDigests {
    fun rev(hexChar: Char = 'c'): ModelRevisionId = ModelRevisionId.parse(hexChar.toString().repeat(64))
    fun pkg(hexChar: Char = 'd'): ArtifactPackageId = ArtifactPackageId.parse(hexChar.toString().repeat(64))
    fun blob(hexChar: Char = 'a'): BlobId = BlobId.parse(hexChar.toString().repeat(64))
    fun sha(hexChar: Char = 'e'): Sha256Digest = Sha256Digest.parse(hexChar.toString().repeat(64))
    fun device(): DeviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1")
}
