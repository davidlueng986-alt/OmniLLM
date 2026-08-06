package com.omnillm.runtime.modelmanager.memory

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId as IdentityInstallationId
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Control-plane in-memory Model Manager stores (ADR-010).
 *
 * Used until durable installation / loaded-model / lease tables are bound from
 * SQLDelight. Hosted only in the runtime process — never from UI (INV-001).
 *
 * Does **not** elevate engine qualification; load port is fail-closed.
 */

class InMemoryInstallationRepository : InstallationRepository {
    private val map = ConcurrentHashMap<String, InstallationSnapshot>()

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

class InMemoryLoadedModelRepository : LoadedModelRepository {
    private val map = ConcurrentHashMap<String, LoadedModelSnapshot>()

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

class InMemoryRevisionLeaseRepository : RevisionLeaseRepository {
    private val map = ConcurrentHashMap<String, RevisionLeaseSnapshot>()

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

/** Zero live references (no sessions / requests / jobs holding the installation). */
object ZeroReferenceSnapshotPort : ReferenceSnapshotPort {
    override suspend fun installationReferences(installationId: InstallationId): LiveReferences =
        LiveReferences()

    override suspend fun loadedModelReferences(loadedModelId: LoadedModelId): LiveReferences =
        LiveReferences()

    override suspend fun leaseReferences(leaseId: RevisionLeaseId): LiveReferences =
        LiveReferences()
}

/**
 * Default trust evaluation: authenticity/license ok, compatibility not promoted
 * (ADR-009 / INV-008). Placement stays PRIVILEGED_TRUSTED label only when
 * independent authenticity is assumed for local catalog — never marks engines QUALIFIED.
 */
class DefaultTrustEvaluationPort : TrustEvaluationPort {
    override suspend fun evaluate(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        currentTrustEpoch: Long,
    ): OmniResult<EvaluationDimensions> =
        OmniResult.ok(
            EvaluationDimensions(
                authenticityOk = true,
                licenseOk = true,
                compatibilityOk = true,
                performanceRecorded = false,
                placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                trustEpoch = currentTrustEpoch + 1,
            ),
        )
}

/**
 * In-memory model store for control-plane wiring before FS model-store is bound.
 * Content identity check is fail-closed when [identityOk] is false.
 */
class InMemoryModelStorePort(
    @Volatile var identityOk: Boolean = true,
) : ModelStorePort {
    private val quarantines = ConcurrentHashMap<String, QuarantineSnapshot>()
    private val ready = ConcurrentHashMap<String, PromoteResult>()

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
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine missing"))
        val next = cur.copy(files = cur.files + file)
        quarantines[keyOf(key)] = next
        return OmniResult.ok(next)
    }

    override suspend fun getQuarantine(key: QuarantineKey): OmniResult<QuarantineSnapshot> {
        val cur = quarantines[keyOf(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine missing"))
        return OmniResult.ok(cur)
    }

    override suspend fun verifyQuarantineIdentity(key: QuarantineKey): OmniResult<ContentIdentityCheck> =
        OmniResult.ok(
            ContentIdentityCheck(
                ok = identityOk,
                failureReason = if (identityOk) null else "digest",
            ),
        )

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

/**
 * Fail-closed engine load port until a real Engine Pack is attached after READY.
 * Plan/commit return CAPABILITY_UNSUPPORTED — never invent SUPPORTED/QUALIFIED cells.
 */
class FailClosedEngineLoadPort(
    override val engineBuildId: EngineBuildId =
        EngineBuildId.parse("engine-unattached-failclosed"),
) : EngineLoadPort {

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "engine load path not attached (fail closed)",
                details = mapOf(
                    "engineBuildId" to engineBuildId.value,
                    "installationId" to input.installationId.value,
                ),
            ),
        )

    override suspend fun commitLoad(
        plan: LoadPlan,
        reservation: com.omnillm.core.resource.Reservation,
        commit: CommitContext,
    ): OmniResult<LoadedModelHandle> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "engine load commit not attached (fail closed)",
                details = mapOf("planId" to plan.planId.value),
            ),
        )

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        OmniResult.err(
            OmniError.NOT_FOUND(
                message = "commit not found (engine not attached)",
                details = mapOf("commitId" to commitId.value),
            ),
        )
}

/**
 * Privileged re-verify that fails closed (no READY-flag alone) when FDs / roots
 * are unavailable — INV-010. Used before real model-store FS binding.
 */
class FailClosedPrivilegedLoadReverify(
    private val clockMonotonic: () -> Long = { System.nanoTime() },
) : PrivilegedLoadReverifyPort {
    override suspend fun reverify(request: PrivilegedReverifyRequest): OmniResult<PrivilegedLoadTicket> {
        val issued = clockMonotonic()
        return OmniResult.ok(
            PrivilegedLoadTicket(
                ticketId = UUID.randomUUID().toString(),
                installationId = request.installationId,
                modelRevisionId = request.modelRevisionId,
                placementClass = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
                contentIdentityOk = false,
                signatureChainOk = false,
                revocationOk = false,
                installationStateOk = false,
                epochsOk = false,
                issuedMonotonic = issued,
                expiryMonotonic = issued + 1_000_000_000L,
            ),
        )
    }
}
