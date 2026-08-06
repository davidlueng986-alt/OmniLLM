package com.omnillm.features.modelhub.usecase

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.api.AcquisitionDeclaredFile
import com.omnillm.features.modelhub.api.AcquisitionJobView
import com.omnillm.features.modelhub.api.AcquisitionMaterializedFile
import com.omnillm.features.modelhub.api.AcquisitionProgressUpdate
import com.omnillm.features.modelhub.api.CancelAcquisitionSpec
import com.omnillm.features.modelhub.api.ModelCard
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.modelhub.api.ModelHubJobHandle
import com.omnillm.features.modelhub.api.ModelHubSnapshot
import com.omnillm.features.modelhub.api.SetPinSpec
import com.omnillm.features.modelhub.api.StartDeleteSpec
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.api.StartImportSpec
import com.omnillm.features.modelhub.ports.AcquisitionLinkStore
import com.omnillm.features.modelhub.ports.InMemoryAcquisitionLinkStore
import com.omnillm.features.modelhub.ports.InMemoryModelDisplayMetadataPort
import com.omnillm.features.modelhub.ports.LiveReferenceQueryPort
import com.omnillm.features.modelhub.ports.LoadedModelQueryPort
import com.omnillm.features.modelhub.ports.ModelDisplayMetadataPort
import com.omnillm.features.modelhub.ports.SuggestedCatalogPort
import com.omnillm.features.modelhub.projection.ModelCardProjector
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.DeleteResourceKind
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import java.util.concurrent.atomic.AtomicLong

/**
 * FEAT-MODELHUB control-plane use-case facade.
 *
 * - Catalog / import / install / delete wiring via [JobManager] + [ModelManager]
 * - State projections from canonical FSMs only
 * - Client-generated jobId / idempotencyKey (ADR-004/005)
 * - No direct DB writes; single writer remains runtime host (ADR-010)
 */
class ModelHubService(
    private val jobManager: JobManager,
    private val modelManager: ModelManager,
    private val catalog: SuggestedCatalogPort,
    private val display: ModelDisplayMetadataPort = InMemoryModelDisplayMetadataPort(),
    private val links: AcquisitionLinkStore = InMemoryAcquisitionLinkStore(),
    private val loadedModels: LoadedModelQueryPort = EmptyLoadedModelQueryPort,
    private val references: LiveReferenceQueryPort = ZeroReferenceQueryPort,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ModelHubApi {

    private val snapshotSeq = AtomicLong(0L)
    private val channelByInstallation = linkedMapOf<String, String>()
    private val resourceVersionByInstallation = linkedMapOf<String, Long>()

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<ModelHubSnapshot> {
        requireLocalUi(principal)
        if (!allowsRead()) return forbiddenRead()
        val installedCards = listInstalledCards()
        val installedRevisionIds = installedCards.map { it.modelRevisionId }.toSet()
        val suggested = catalog.listSuggested()
            .filter { it.modelRevisionId !in installedRevisionIds }
            .map { ModelCardProjector.fromCatalog(it) }
        val jobs = listAcquisitionJobViews()
        val blocking = installedCards
            .filter { it.installationState in setOf("REVOKED", "CORRUPT") }
            .map { "installation ${it.installationId}: ${it.installationState}" }
        return OmniResult.ok(
            ModelHubSnapshot(
                snapshotVersion = snapshotSeq.incrementAndGet(),
                suggested = suggested,
                installed = installedCards,
                downloadsAndImports = jobs,
                blockingIssues = blocking,
            ),
        )
    }

    override suspend fun getModelCard(
        principal: PrincipalId,
        installationId: String?,
        modelRevisionId: String?,
    ): OmniResult<ModelCard> {
        requireLocalUi(principal)
        if (!allowsRead()) return forbiddenRead()
        if (installationId != null) {
            val id = parseInstallationId(installationId) ?: return invalidId("installationId")
            val snap = modelManager.getInstallation(id)
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "installation not found",
                        details = mapOf("installationId" to installationId),
                    ),
                )
            return OmniResult.ok(toCard(snap))
        }
        if (modelRevisionId != null) {
            val rev = parseRevision(modelRevisionId) ?: return invalidId("modelRevisionId")
            val found = modelManager.findInstallationsByRevision(rev).firstOrNull()
            if (found != null) return OmniResult.ok(toCard(found))
            val entry = catalog.findByRevision(modelRevisionId)
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "model revision not found",
                        details = mapOf("modelRevisionId" to modelRevisionId),
                    ),
                )
            return OmniResult.ok(ModelCardProjector.fromCatalog(entry))
        }
        return OmniResult.err(
            OmniError.INVALID_REQUEST(message = "installationId or modelRevisionId required"),
        )
    }

    override suspend fun listSuggested(principal: PrincipalId): OmniResult<List<ModelCard>> {
        requireLocalUi(principal)
        if (!allowsRead()) return forbiddenRead()
        val installed = modelManager.listInstallations().map { it.modelRevisionId.hex }.toSet()
        return OmniResult.ok(
            catalog.listSuggested()
                .filter { it.modelRevisionId !in installed }
                .map { ModelCardProjector.fromCatalog(it) },
        )
    }

    override suspend fun listInstalled(principal: PrincipalId): OmniResult<List<ModelCard>> {
        requireLocalUi(principal)
        if (!allowsRead()) return forbiddenRead()
        return OmniResult.ok(listInstalledCards())
    }

    override suspend fun listAcquisitionJobs(principal: PrincipalId): OmniResult<List<AcquisitionJobView>> {
        requireLocalUi(principal)
        if (!allowsRead()) return forbiddenRead()
        return OmniResult.ok(listAcquisitionJobViews())
    }

    override suspend fun startDownload(
        principal: PrincipalId,
        spec: StartDownloadSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        if (!allowsManage()) return forbiddenManage()
        return startAcquisition(
            principal = principal,
            jobId = spec.jobId,
            installationIdRaw = spec.installationId,
            modelRevisionIdRaw = spec.modelRevisionId,
            artifactPackageIdRaw = spec.artifactPackageId,
            displayName = spec.displayName,
            channel = AcquisitionChannel.PINNED_DOWNLOAD,
            digest = spec.command.canonicalInputDigest,
            idempotencyKey = spec.command.idempotencyKey,
            kind = JobKind.DOWNLOAD,
            parameters = JobParameters.Download(
                sourceUrl = spec.sourceUrl,
                expectedSha256 = spec.expectedSha256,
                expectedBytes = spec.expectedBytes,
                targetName = spec.targetName,
            ),
        )
    }

    override suspend fun startImport(
        principal: PrincipalId,
        spec: StartImportSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        if (!allowsManage()) return forbiddenManage()
        return startAcquisition(
            principal = principal,
            jobId = spec.jobId,
            installationIdRaw = spec.installationId,
            modelRevisionIdRaw = spec.modelRevisionId,
            artifactPackageIdRaw = spec.artifactPackageId,
            displayName = spec.displayName,
            channel = AcquisitionChannel.LOCAL_IMPORT,
            digest = spec.command.canonicalInputDigest,
            idempotencyKey = spec.command.idempotencyKey,
            kind = JobKind.IMPORT,
            parameters = JobParameters.Import(
                assetId = spec.assetId,
                expectedFormat = spec.expectedFormat,
                expectedSha256 = spec.expectedSha256,
            ),
        )
    }

    override suspend fun startDelete(
        principal: PrincipalId,
        spec: StartDeleteSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        if (!allowsManage()) return forbiddenManage()

        val installationId = parseInstallationId(spec.installationId)
            ?: return invalidId("installationId")
        val snap = modelManager.getInstallation(installationId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "installation not found",
                    details = mapOf("installationId" to spec.installationId),
                ),
            )
        val currentRv = resourceVersionByInstallation[spec.installationId] ?: 0L
        if (spec.expectedResourceVersion != currentRv) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "installation resourceVersion mismatch",
                    details = mapOf(
                        "expected" to spec.expectedResourceVersion.toString(),
                        "actual" to currentRv.toString(),
                    ),
                ),
            )
        }

        val identity = JobIdentity(
            jobId = JobId(spec.jobId),
            principalId = principal,
            kind = JobKind.DELETE,
            idempotencyKey = IdempotencyKey.parse(spec.command.idempotencyKey),
            canonicalSpecDigest = spec.command.canonicalInputDigest.lowercase(),
        )
        val params = JobParameters.Delete(
            resourceKind = DeleteResourceKind.INSTALLATION,
            resourceId = spec.installationId,
            expectedResourceVersion = spec.expectedResourceVersion,
            forceAfterDrain = spec.forceAfterDrain,
        )
        val claim = when (val created = jobManager.create(identity, params)) {
            is OmniResult.Ok -> created.value
            is OmniResult.Err -> return created
        }

        links.link(spec.jobId, spec.installationId, snap.modelRevisionId.hex)

        // Enter DRAINING when applicable (READY / REVOKED / …).
        if (claim.createdNew) {
            when (val drain = modelManager.requestInstallationDelete(installationId)) {
                is OmniResult.Err -> {
                    // If delete not applicable (e.g. mid-acquire), still keep job for reconcile.
                    if (drain.error.code != OmniErrorCode.STATE_CONFLICT) {
                        jobManager.fail(JobId(spec.jobId), drain.error)
                        return drain
                    }
                }
                is OmniResult.Ok -> bumpResourceVersion(spec.installationId)
            }
            jobManager.start(JobId(spec.jobId))
        }

        val record = jobManager.query(JobId(spec.jobId)).getOrNull() ?: claim.record
        return OmniResult.ok(toHandle(record, claim.createdNew, spec.installationId, snap.modelRevisionId.hex))
    }

    override suspend fun cancelAcquisition(
        principal: PrincipalId,
        spec: CancelAcquisitionSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        if (!allowsManage()) return forbiddenManage()

        val jobId = try {
            JobId(spec.jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val cancelled = when (val c = jobManager.cancel(jobId, requestOnly = spec.requestOnly)) {
            is OmniResult.Ok -> c.value
            is OmniResult.Err -> return c
        }

        val installationIdRaw = links.installationIdForJob(spec.jobId)
        if (installationIdRaw != null && !spec.requestOnly) {
            val installationId = parseInstallationId(installationIdRaw)
            if (installationId != null) {
                val snap = modelManager.getInstallation(installationId)
                if (snap != null && snap.state in setOf(
                        "ACQUIRING",
                        "VERIFYING",
                        "COMPATIBILITY_CHECK",
                    )
                ) {
                    modelManager.cancelInFlightInstallation(installationId, "job cancelled")
                    bumpResourceVersion(installationIdRaw)
                }
            }
        }
        return OmniResult.ok(
            toHandle(
                cancelled,
                createdNew = false,
                installationId = installationIdRaw,
                modelRevisionId = links.modelRevisionIdForJob(spec.jobId),
            ),
        )
    }

    override suspend fun setPinned(
        principal: PrincipalId,
        spec: SetPinSpec,
    ): OmniResult<ModelCard> {
        requireLocalUi(principal)
        if (!allowsManage()) return forbiddenManage()
        val installationId = parseInstallationId(spec.installationId)
            ?: return invalidId("installationId")
        val updated = when (
            val r = modelManager.setInstallationPinned(installationId, spec.pinned)
        ) {
            is OmniResult.Ok -> r.value
            is OmniResult.Err -> return r
        }
        bumpResourceVersion(spec.installationId)
        return OmniResult.ok(toCard(updated))
    }

    override suspend fun beginAcquisitionAttempt(
        jobId: String,
        declaredRoles: List<AcquisitionDeclaredFile>,
        deadlineMonotonic: Long,
    ): OmniResult<ModelHubJobHandle> {
        val id = try {
            JobId(jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val started = when (val s = jobManager.start(id)) {
            is OmniResult.Ok -> s.value
            is OmniResult.Err -> return s
        }

        val installationIdRaw = links.installationIdForJob(jobId)
            ?: return OmniResult.ok(toHandle(started, false, null, links.modelRevisionIdForJob(jobId)))

        val installationId = parseInstallationId(installationIdRaw)
            ?: return invalidId("installationId")

        if (declaredRoles.isNotEmpty()) {
            val qKey = QuarantineKey(jobId = jobId, attemptId = "a${started.currentAttemptNo ?: 1}")
            val declared = declaredRoles.map {
                DeclaredArtifactFile(
                    role = it.role,
                    blobId = BlobId.parse(it.blobId),
                    byteLength = it.byteLength,
                    shardIndex = it.shardIndex,
                )
            }
            when (
                val acq = modelManager.beginAcquire(
                    installationId = installationId,
                    quarantineKey = qKey,
                    declared = declared,
                    deadlineMonotonic = deadlineMonotonic,
                )
            ) {
                is OmniResult.Err -> {
                    jobManager.fail(id, acq.error)
                    return acq
                }
                is OmniResult.Ok -> bumpResourceVersion(installationIdRaw)
            }
        }
        val record = jobManager.query(id).getOrNull() ?: started
        return OmniResult.ok(
            toHandle(record, false, installationIdRaw, links.modelRevisionIdForJob(jobId)),
        )
    }

    override suspend fun updateAcquisitionProgress(
        update: AcquisitionProgressUpdate,
    ): OmniResult<ModelHubJobHandle> {
        val id = try {
            JobId(update.jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val progress = JobProgress(
            networkBytes = update.networkBytes,
            materializedBytes = update.materializedBytes,
            verifiedBytes = update.verifiedBytes,
            totalBytesKnown = update.totalBytesKnown,
            currentPhase = update.currentPhase,
        )
        val updated = when (val u = jobManager.updateProgress(id, progress)) {
            is OmniResult.Ok -> u.value
            is OmniResult.Err -> return u
        }
        return OmniResult.ok(
            toHandle(
                updated,
                false,
                links.installationIdForJob(update.jobId),
                links.modelRevisionIdForJob(update.jobId),
            ),
        )
    }

    override suspend fun completeAcquisitionMaterialize(
        jobId: String,
        files: List<AcquisitionMaterializedFile>,
    ): OmniResult<ModelCard> {
        val id = try {
            JobId(jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val installationIdRaw = links.installationIdForJob(jobId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "no installation linked to job", details = mapOf("jobId" to jobId)),
            )
        val installationId = parseInstallationId(installationIdRaw)
            ?: return invalidId("installationId")

        val qFiles = files.map {
            QuarantineFileRecord(
                role = it.role,
                expectedBlobId = BlobId.parse(it.expectedBlobId),
                expectedByteLength = it.expectedByteLength,
                shardIndex = it.shardIndex,
                materializeHandle = it.materializeHandle,
            )
        }

        when (val m = modelManager.materializeComplete(installationId, qFiles)) {
            is OmniResult.Err -> {
                jobManager.fail(id, m.error)
                return m
            }
            is OmniResult.Ok -> Unit
        }
        when (val v = modelManager.beginVerify(installationId)) {
            is OmniResult.Err -> {
                jobManager.fail(id, v.error)
                return v
            }
            is OmniResult.Ok -> Unit
        }
        when (val i = modelManager.completeIdentityVerify(installationId)) {
            is OmniResult.Err -> {
                jobManager.fail(id, i.error)
                return i
            }
            is OmniResult.Ok -> Unit
        }
        val ready = when (val p = modelManager.promoteToReady(installationId)) {
            is OmniResult.Err -> {
                jobManager.fail(id, p.error)
                return p
            }
            is OmniResult.Ok -> p.value
        }

        jobManager.updateProgress(
            id,
            JobProgress(
                networkBytes = files.sumOf { it.expectedByteLength },
                materializedBytes = files.sumOf { it.expectedByteLength },
                verifiedBytes = files.sumOf { it.expectedByteLength },
                totalBytesKnown = files.sumOf { it.expectedByteLength },
                currentPhase = "READY",
            ),
        )
        jobManager.succeed(id)
        bumpResourceVersion(installationIdRaw)
        return OmniResult.ok(toCard(ready))
    }

    override suspend fun failAcquisition(
        jobId: String,
        reason: String,
    ): OmniResult<ModelHubJobHandle> {
        val id = try {
            JobId(jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val error = OmniError.INTERNAL(message = reason)
        val installationIdRaw = links.installationIdForJob(jobId)
        if (installationIdRaw != null) {
            parseInstallationId(installationIdRaw)?.let { installationId ->
                val snap = modelManager.getInstallation(installationId)
                if (snap != null && snap.state == "ACQUIRING") {
                    modelManager.markAcquireFailed(installationId, reason)
                    bumpResourceVersion(installationIdRaw)
                }
            }
        }
        val failed = when (val f = jobManager.fail(id, error)) {
            is OmniResult.Ok -> f.value
            is OmniResult.Err -> return f
        }
        return OmniResult.ok(
            toHandle(failed, false, installationIdRaw, links.modelRevisionIdForJob(jobId)),
        )
    }

    override suspend fun completeDeleteWhenQuiescent(jobId: String): OmniResult<ModelHubJobHandle> {
        val id = try {
            JobId(jobId)
        } catch (e: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val installationIdRaw = links.installationIdForJob(jobId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "no installation linked to delete job"),
            )
        val installationId = parseInstallationId(installationIdRaw)
            ?: return invalidId("installationId")
        val modelRevisionId = links.modelRevisionIdForJob(jobId)

        val refs = references.installationReferences(installationIdRaw)
        if (!refs.isZero) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "installation still has live references",
                    details = mapOf(
                        "requestCount" to refs.requestCount.toString(),
                        "sessionCount" to refs.sessionCount.toString(),
                        "loadedModelCount" to refs.loadedModelCount.toString(),
                        "jobCount" to refs.jobCount.toString(),
                        "leaseCount" to refs.leaseCount.toString(),
                    ),
                ),
            )
        }

        // Advance installation: any non-terminal → DRAINING/DELETING → DELETED
        // (CORE-MODEL §9: drain only when refs zero).
        var snap = modelManager.getInstallation(installationId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "installation not found"))

        if (snap.state in setOf("READY", "REVOKED", "CORRUPT", "REJECTED", "DISCOVERED", "QUARANTINED")) {
            when (val del = modelManager.requestInstallationDelete(installationId)) {
                is OmniResult.Err -> {
                    jobManager.fail(id, del.error)
                    return del
                }
                is OmniResult.Ok -> snap = del.value
            }
        }

        if (snap.state == "DRAINING") {
            when (val d = modelManager.completeInstallationDrain(installationId, "DRAINED_DELETE")) {
                is OmniResult.Err -> {
                    jobManager.fail(id, d.error)
                    return d
                }
                is OmniResult.Ok -> snap = d.value
            }
        }

        if (snap.state == "DELETING") {
            when (val c = modelManager.commitInstallationDelete(installationId)) {
                is OmniResult.Err -> {
                    jobManager.fail(id, c.error)
                    return c
                }
                is OmniResult.Ok -> Unit
            }
        } else if (snap.state != "DELETED") {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "delete not completable from state",
                    details = mapOf("state" to snap.state),
                ),
            )
        }

        val done = when (val s = jobManager.succeed(id)) {
            is OmniResult.Ok -> s.value
            is OmniResult.Err -> return s
        }
        links.unlink(jobId)
        channelByInstallation.remove(installationIdRaw)
        resourceVersionByInstallation.remove(installationIdRaw)
        return OmniResult.ok(toHandle(done, false, installationIdRaw, modelRevisionId))
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun startAcquisition(
        principal: PrincipalId,
        jobId: String,
        installationIdRaw: String,
        modelRevisionIdRaw: String,
        artifactPackageIdRaw: String,
        displayName: String,
        channel: String,
        digest: String,
        idempotencyKey: String,
        kind: JobKind,
        parameters: JobParameters,
    ): OmniResult<ModelHubJobHandle> {
        val installationId = parseInstallationId(installationIdRaw)
            ?: return invalidId("installationId")
        val revisionId = parseRevision(modelRevisionIdRaw) ?: return invalidId("modelRevisionId")
        val packageId = parsePackage(artifactPackageIdRaw) ?: return invalidId("artifactPackageId")

        val identity = try {
            JobIdentity(
                jobId = JobId(jobId),
                principalId = principal,
                kind = kind,
                idempotencyKey = IdempotencyKey.parse(idempotencyKey),
                canonicalSpecDigest = digest.lowercase(),
            )
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = e.message ?: "invalid identity"))
        }

        val claim = when (val created = jobManager.create(identity, parameters)) {
            is OmniResult.Ok -> created.value
            is OmniResult.Err -> return created
        }

        if (claim.createdNew) {
            when (
                val disc = modelManager.discoverInstallation(
                    installationId,
                    revisionId,
                    packageId,
                )
            ) {
                is OmniResult.Err -> {
                    // Idempotent re-discover race: if already exists for same id, continue.
                    if (disc.error.code != OmniErrorCode.STATE_CONFLICT) {
                        jobManager.fail(JobId(jobId), disc.error)
                        return disc
                    }
                }
                is OmniResult.Ok -> Unit
            }
            channelByInstallation[installationIdRaw] = channel
            display.putDisplayName(modelRevisionIdRaw, displayName)
            resourceVersionByInstallation.putIfAbsent(installationIdRaw, 0L)
            links.link(jobId, installationIdRaw, modelRevisionIdRaw)
        }

        return OmniResult.ok(
            toHandle(
                claim.record,
                claim.createdNew,
                installationIdRaw,
                modelRevisionIdRaw,
            ),
        )
    }

    private suspend fun listInstalledCards(): List<ModelCard> =
        modelManager.listInstallations()
            .filter { it.state != "DELETED" }
            .map { toCard(it) }

    private fun listAcquisitionJobViews(): List<AcquisitionJobView> {
        val active = jobManager.listActive()
            .filter { ModelCardProjector.isAcquisitionJob(it.kind) }
        val own = jobManager.listOwn(LocalUiPrincipal.ID)
            .filter { ModelCardProjector.isAcquisitionJob(it.kind) && it.isTerminal }
            .sortedByDescending { it.updatedAtEpochMs }
            .take(20)
        return (active + own)
            .distinctBy { it.jobId.value }
            .map { record ->
                ModelCardProjector.jobView(
                    record = record,
                    installationId = links.installationIdForJob(record.jobId.value),
                    modelRevisionId = links.modelRevisionIdForJob(record.jobId.value),
                )
            }
    }

    private suspend fun toCard(snap: InstallationSnapshot): ModelCard {
        val installationId = snap.installationId.value
        val loaded = loadedModels.findByInstallation(installationId).firstOrNull {
            !it.isTerminal()
        }
        val refs = references.installationReferences(installationId)
        val jobId = links.jobIdForInstallation(installationId)
        val job = jobId?.let { jobManager.query(JobId(it)).getOrNull() }
        val catalogEntry = catalog.findByRevision(snap.modelRevisionId.hex)
        val channel = channelByInstallation[installationId]
            ?: catalogEntry?.acquisitionChannel
            ?: AcquisitionChannel.UNKNOWN
        val displayName = display.getDisplayName(snap.modelRevisionId.hex, installationId)
            ?: catalogEntry?.displayName
            ?: snap.modelRevisionId.hex.take(12)
        return ModelCardProjector.fromInstallation(
            snap = snap,
            displayName = displayName,
            alias = display.getAlias(installationId),
            acquisitionChannel = channel,
            byteLength = catalogEntry?.byteLength,
            quantizationDescriptorJson = catalogEntry?.quantizationDescriptorJson,
            licenseDigest = catalogEntry?.licenseDigest,
            loaded = loaded,
            refs = refs,
            activeJob = job,
        )
    }

    private fun toHandle(
        record: JobRecord,
        createdNew: Boolean,
        installationId: String?,
        modelRevisionId: String?,
    ): ModelHubJobHandle =
        ModelHubJobHandle(
            jobId = record.jobId.value,
            kind = record.kind.name,
            state = record.state,
            resourceVersion = record.resourceVersion,
            createdNew = createdNew,
            installationId = installationId,
            modelRevisionId = modelRevisionId,
        )

    private fun bumpResourceVersion(installationId: String) {
        val cur = resourceVersionByInstallation[installationId] ?: 0L
        resourceVersionByInstallation[installationId] = cur + 1L
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "ModelHubApi accepts LOCAL_UI principal only (got ${principal.value})"
        }
    }

    private fun allowsRead(): Boolean =
        LocalUiPrincipal.allows(AccessScope.models_read)

    private fun allowsManage(): Boolean =
        LocalUiPrincipal.allows(AccessScope.models_manage) &&
            LocalUiPrincipal.allows(AccessScope.jobs_manage)

    private fun <T> forbiddenRead(): OmniResult<T> =
        OmniResult.err(OmniError.FORBIDDEN(message = "models.read not allowed"))

    private fun <T> forbiddenManage(): OmniResult<T> =
        OmniResult.err(OmniError.FORBIDDEN(message = "models.manage / jobs.manage not allowed"))

    private fun <T> invalidId(field: String): OmniResult<T> =
        OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid $field"))

    private fun parseInstallationId(raw: String): InstallationId? =
        try {
            InstallationId(raw.lowercase())
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseRevision(raw: String): ModelRevisionId? =
        try {
            ModelRevisionId.parse(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parsePackage(raw: String): ArtifactPackageId? =
        try {
            ArtifactPackageId.parse(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

    private object EmptyLoadedModelQueryPort : LoadedModelQueryPort {
        override suspend fun findByInstallation(installationId: String) = emptyList<com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot>()
    }

    private object ZeroReferenceQueryPort : LiveReferenceQueryPort {
        override suspend fun installationReferences(installationId: String): LiveReferences =
            LiveReferences()
    }
}
