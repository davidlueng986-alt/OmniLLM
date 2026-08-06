package com.omnillm.ui.admin

import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniDownloadJobParameters
import ai.omnillm.api.OmniImportJobParameters
import ai.omnillm.api.OmniJobSpec
import android.os.RemoteException
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.autosetup.AutoSetupModule
import com.omnillm.features.autosetup.catalog.FixtureCatalogCandidates
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.ports.AutoSetupJobPort
import com.omnillm.features.autosetup.ports.AutoSetupRuntimePorts
import com.omnillm.features.autosetup.ports.DeviceProbePort
import com.omnillm.features.autosetup.ports.FailClosedAutoSetupModelPort
import com.omnillm.features.autosetup.ports.FailClosedAutoSetupOrchestratorPort
import com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel
import com.omnillm.features.benchmark.BenchmarkFeatureModule
import com.omnillm.features.benchmark.viewmodel.BenchmarkViewModel
import com.omnillm.features.dashboard.DashboardFeatureModule
import com.omnillm.features.dashboard.viewmodel.DashboardViewModel
import com.omnillm.features.lan.LanFeatureModule
import com.omnillm.features.lan.viewmodel.LanAccessViewModel
import com.omnillm.features.modelhub.ModelhubModule
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
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog
import com.omnillm.features.modelhub.projection.ModelCardProjector
import com.omnillm.features.modelhub.viewmodel.ModelHubViewModel
import com.omnillm.features.playground.PlaygroundModule
import com.omnillm.features.playground.viewmodel.PlaygroundViewModel
import com.omnillm.features.server.ServerFeatureModule
import com.omnillm.features.server.viewmodel.DeveloperServerViewModel
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.state.domain.JobId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires feature ViewModels to the live non-exported [IOmniAdmin]
 * binder (INV-001: UI never opens DB / loads native).
 *
 * ModelHub suggested catalog is the shared offline fixture (read-only projection).
 * Mutations (download / import / cancel / benchmark jobs) go through Admin job APIs.
 * Playground / server smoke go through IOmniAdmin → plane Orchestrator (exploratory CONDITIONAL).
 * Capability / qualification cells stay UNKNOWN / UNQUALIFIED without device evidence.
 */
object AdminLiveFeatureFactory {

    fun createModelHubViewModel(admin: IOmniAdmin): ModelHubViewModel =
        ModelhubModule.createViewModel(AdminProjectedModelHubApi(admin))

    fun createAutoSetupViewModel(admin: IOmniAdmin): AutoSetupViewModel {
        val ports = AutoSetupRuntimePorts(
            deviceProbe = AndroidUiDeviceProbe(),
            catalog = FixtureCatalogCandidates(),
            jobs = AdminAutoSetupJobPort(admin),
            models = FailClosedAutoSetupModelPort,
            orchestrator = FailClosedAutoSetupOrchestratorPort,
        )
        return AutoSetupModule.createViewModel(AutoSetupModule.createApi(ports))
    }

    fun createPlaygroundViewModel(admin: IOmniAdmin): PlaygroundViewModel =
        PlaygroundModule.createViewModel(
            PlaygroundModule.createApi(AdminFeatureProjections.playgroundPorts(admin)),
        )

    fun createDashboardViewModel(admin: IOmniAdmin): DashboardViewModel =
        DashboardFeatureModule.createViewModel(
            AdminFeatureProjections.dashboardApi(admin),
        )

    fun createBenchmarkViewModel(admin: IOmniAdmin): BenchmarkViewModel =
        BenchmarkFeatureModule.createViewModel(
            AdminFeatureProjections.benchmarkApi(admin),
        )

    fun createServerViewModel(admin: IOmniAdmin): DeveloperServerViewModel =
        ServerFeatureModule.createViewModel(
            ServerFeatureModule.createApi(AdminFeatureProjections.serverPorts(admin)),
        )

    fun createLanViewModel(admin: IOmniAdmin): LanAccessViewModel =
        LanFeatureModule.createViewModel(
            LanFeatureModule.createApi(AdminFeatureProjections.lanPorts(admin)),
        )
}

/**
 * ModelHubApi projection over Admin binder + offline fixture catalog.
 * Control-plane pipeline executes when DOWNLOAD sourceUrl matches the fixture pin.
 */
class AdminProjectedModelHubApi(
    private val admin: IOmniAdmin,
    private val catalog: OfflineFixtureCatalog = OfflineFixtureCatalog.DEFAULT,
) : ModelHubApi {

    private val snapshotSeq = AtomicLong(0L)

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<ModelHubSnapshot> {
        requireLocalUi(principal)
        return try {
            val snap = admin.snapshot
            val installed = snap.models.orEmpty().mapNotNull { m ->
                if (m == null || m.modelRevisionId.isNullOrBlank()) return@mapNotNull null
                val entry = catalog.findByRevision(m.modelRevisionId)
                ModelCard(
                    modelRevisionId = m.modelRevisionId,
                    artifactPackageId = entry?.artifactPackageId ?: FixtureArtifact.packageIdHex(),
                    installationId = null,
                    displayName = m.displayName.orEmpty().ifBlank { entry?.displayName ?: m.modelRevisionId.take(12) },
                    installationState = m.installationState,
                    loadedModelState = null,
                    acquisitionChannel = entry?.acquisitionChannel ?: AcquisitionChannel.PINNED_DOWNLOAD,
                    byteLength = entry?.byteLength,
                    licenseStatus = com.omnillm.features.modelhub.api.LicenseStatus.ACCEPTANCE_REQUIRED,
                    authenticityOk = null,
                    compatibilityStatus = com.omnillm.features.modelhub.api.CompatibilityStatus.NOT_CHECKED,
                    placementClass = null,
                    performanceRecorded = false,
                    pinned = false,
                    liveReferenceCount = 0,
                    allowedActions = listOf(
                        com.omnillm.features.modelhub.api.ModelHubAction.VIEW_EVIDENCE,
                        com.omnillm.features.modelhub.api.ModelHubAction.DELETE,
                    ),
                )
            }
            val installedRevs = installed.map { it.modelRevisionId }.toSet()
            val suggested = catalog.listSuggested()
                .filter { it.modelRevisionId !in installedRevs }
                .map { ModelCardProjector.fromCatalog(it) }
            val jobs = snap.activeJobs.orEmpty().mapNotNull { j ->
                if (j == null || j.jobId.isNullOrBlank()) return@mapNotNull null
                AcquisitionJobView(
                    jobId = j.jobId,
                    kind = "DOWNLOAD",
                    state = j.state.orEmpty(),
                    resourceVersion = j.resourceVersion,
                    progress = JobProgress(),
                    cancelRequested = false,
                    allowedActions = if (j.state in setOf("QUEUED", "RUNNING", "PAUSED")) {
                        listOf(com.omnillm.features.modelhub.api.ModelHubAction.CANCEL)
                    } else {
                        emptyList()
                    },
                )
            }
            OmniResult.ok(
                ModelHubSnapshot(
                    snapshotVersion = snapshotSeq.incrementAndGet(),
                    suggested = suggested,
                    installed = installed,
                    downloadsAndImports = jobs,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "snapshot remote failure"))
        }
    }

    override suspend fun getModelCard(
        principal: PrincipalId,
        installationId: String?,
        modelRevisionId: String?,
    ): OmniResult<ModelCard> {
        requireLocalUi(principal)
        val snap = getSnapshot(principal)
        if (snap is OmniResult.Err) return snap
        val s = (snap as OmniResult.Ok).value
        if (modelRevisionId != null) {
            s.installed.firstOrNull { it.modelRevisionId == modelRevisionId }?.let { return OmniResult.ok(it) }
            s.suggested.firstOrNull { it.modelRevisionId == modelRevisionId }?.let { return OmniResult.ok(it) }
            catalog.findByRevision(modelRevisionId)?.let {
                return OmniResult.ok(ModelCardProjector.fromCatalog(it))
            }
        }
        return OmniResult.err(OmniError.NOT_FOUND(message = "model not found"))
    }

    override suspend fun listSuggested(principal: PrincipalId): OmniResult<List<ModelCard>> {
        val snap = getSnapshot(principal)
        return when (snap) {
            is OmniResult.Ok -> OmniResult.ok(snap.value.suggested)
            is OmniResult.Err -> snap
        }
    }

    override suspend fun listInstalled(principal: PrincipalId): OmniResult<List<ModelCard>> {
        val snap = getSnapshot(principal)
        return when (snap) {
            is OmniResult.Ok -> OmniResult.ok(snap.value.installed)
            is OmniResult.Err -> snap
        }
    }

    override suspend fun listAcquisitionJobs(principal: PrincipalId): OmniResult<List<AcquisitionJobView>> {
        val snap = getSnapshot(principal)
        return when (snap) {
            is OmniResult.Ok -> OmniResult.ok(snap.value.downloadsAndImports)
            is OmniResult.Err -> snap
        }
    }

    override suspend fun startDownload(
        principal: PrincipalId,
        spec: StartDownloadSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        return try {
            val aidl = OmniJobSpec()
            aidl.jobId = spec.jobId
            aidl.kind = "DOWNLOAD"
            aidl.command = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            aidl.download = OmniDownloadJobParameters().apply {
                sourceUrl = spec.sourceUrl
                expectedSha256 = spec.expectedSha256
                if (spec.expectedBytes != null) {
                    hasExpectedBytes = true
                    expectedBytes = spec.expectedBytes!!
                }
                targetName = spec.targetName
            }
            val info = admin.startJob(aidl)
            OmniResult.ok(
                ModelHubJobHandle(
                    jobId = info.jobId.orEmpty().ifBlank { spec.jobId },
                    kind = "DOWNLOAD",
                    state = info.state.orEmpty(),
                    resourceVersion = info.resourceVersion,
                    createdNew = true,
                    installationId = spec.installationId,
                    modelRevisionId = spec.modelRevisionId,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "startDownload remote failure"))
        }
    }

    override suspend fun startImport(
        principal: PrincipalId,
        spec: StartImportSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        return try {
            val aidl = OmniJobSpec()
            aidl.jobId = spec.jobId
            aidl.kind = "IMPORT"
            aidl.command = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            aidl.importSpec = OmniImportJobParameters().apply {
                assetId = spec.assetId
                expectedFormat = spec.expectedFormat
                expectedSha256 = spec.expectedSha256
            }
            val info = admin.startJob(aidl)
            OmniResult.ok(
                ModelHubJobHandle(
                    jobId = info.jobId.orEmpty().ifBlank { spec.jobId },
                    kind = "IMPORT",
                    state = info.state.orEmpty(),
                    resourceVersion = info.resourceVersion,
                    createdNew = true,
                    installationId = spec.installationId,
                    modelRevisionId = spec.modelRevisionId,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "startImport remote failure"))
        }
    }

    override suspend fun startDelete(
        principal: PrincipalId,
        spec: StartDeleteSpec,
    ): OmniResult<ModelHubJobHandle> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "delete via Admin job requires DELETE kind + resource version CAS",
            ),
        )

    override suspend fun cancelAcquisition(
        principal: PrincipalId,
        spec: CancelAcquisitionSpec,
    ): OmniResult<ModelHubJobHandle> {
        requireLocalUi(principal)
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            val result = admin.cancelJob(spec.jobId, cmd)
            if (result.error != null) {
                OmniResult.err(
                    OmniError.ofCode(
                        result.error.code.orEmpty().ifBlank { "INTERNAL" },
                        message = result.error.message,
                    ),
                )
            } else {
                val info = admin.getJob(spec.jobId)
                OmniResult.ok(
                    ModelHubJobHandle(
                        jobId = info.jobId.orEmpty().ifBlank { spec.jobId },
                        kind = "DOWNLOAD",
                        state = info.state.orEmpty().ifBlank { "CANCELLED" },
                        resourceVersion = info.resourceVersion,
                        createdNew = false,
                    ),
                )
            }
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "cancel remote failure"))
        }
    }

    override suspend fun setPinned(principal: PrincipalId, spec: SetPinSpec): OmniResult<ModelCard> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "pin requires ModelHub control-plane API"))

    override suspend fun beginAcquisitionAttempt(
        jobId: String,
        declaredRoles: List<AcquisitionDeclaredFile>,
        deadlineMonotonic: Long,
    ): OmniResult<ModelHubJobHandle> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "worker pipeline is control-plane only"))

    override suspend fun updateAcquisitionProgress(update: AcquisitionProgressUpdate): OmniResult<ModelHubJobHandle> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "worker pipeline is control-plane only"))

    override suspend fun completeAcquisitionMaterialize(
        jobId: String,
        files: List<AcquisitionMaterializedFile>,
    ): OmniResult<ModelCard> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "worker pipeline is control-plane only"))

    override suspend fun failAcquisition(jobId: String, reason: String): OmniResult<ModelHubJobHandle> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "worker pipeline is control-plane only"))

    override suspend fun completeDeleteWhenQuiescent(jobId: String): OmniResult<ModelHubJobHandle> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "delete complete is control-plane only"))

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value)
    }
}

private class AdminAutoSetupJobPort(private val admin: IOmniAdmin) : AutoSetupJobPort {
    override fun create(identity: JobIdentity, parameters: JobParameters): OmniResult<JobRecord> {
        return try {
            val aidl = OmniJobSpec()
            aidl.jobId = identity.jobId.value
            aidl.kind = identity.kind.name
            aidl.command = OmniCommandRequest().apply {
                commandId = UUID.randomUUID().toString()
                idempotencyKey = identity.idempotencyKey.value
                canonicalInputDigest = identity.canonicalSpecDigest
            }
            when (parameters) {
                is JobParameters.Download -> {
                    aidl.download = OmniDownloadJobParameters().apply {
                        sourceUrl = parameters.sourceUrl
                        expectedSha256 = parameters.expectedSha256
                        if (parameters.expectedBytes != null) {
                            hasExpectedBytes = true
                            expectedBytes = parameters.expectedBytes!!
                        }
                        targetName = parameters.targetName
                    }
                }
                is JobParameters.Import -> {
                    aidl.importSpec = OmniImportJobParameters().apply {
                        assetId = parameters.assetId
                        expectedFormat = parameters.expectedFormat
                        expectedSha256 = parameters.expectedSha256
                    }
                }
                else -> return OmniResult.err(
                    OmniError.INVALID_REQUEST(message = "unsupported job parameters for auto-setup"),
                )
            }
            val info = admin.startJob(aidl)
            val now = System.currentTimeMillis()
            OmniResult.ok(
                JobRecord(
                    identity = identity.copy(
                        jobId = JobId(info.jobId.orEmpty().ifBlank { identity.jobId.value }),
                    ),
                    parameters = parameters,
                    state = info.state.orEmpty().ifBlank { "QUEUED" },
                    resourceVersion = info.resourceVersion,
                    currentAttemptNo = null,
                    attempts = emptyList(),
                    checkpoint = null,
                    progress = JobProgress(),
                    pauseReason = null,
                    error = null,
                    events = emptyList(),
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "create job remote failure"))
        } catch (e: IllegalArgumentException) {
            OmniResult.err(OmniError.INVALID_REQUEST(message = e.message ?: "invalid job"))
        }
    }

    override fun query(jobId: JobId): OmniResult<JobRecord> {
        return try {
            val info = admin.getJob(jobId.value)
            val now = System.currentTimeMillis()
            val identity = JobIdentity(
                jobId = jobId,
                principalId = LocalUiPrincipal.ID,
                kind = JobKind.DOWNLOAD,
                idempotencyKey = IdempotencyKey.parse("query-${jobId.value}"),
                canonicalSpecDigest = "e".repeat(64),
            )
            OmniResult.ok(
                JobRecord(
                    identity = identity,
                    parameters = JobParameters.Download(sourceUrl = FixtureArtifact.PINNED_HTTPS_URL),
                    state = info.state.orEmpty(),
                    resourceVersion = info.resourceVersion,
                    currentAttemptNo = null,
                    attempts = emptyList(),
                    checkpoint = null,
                    progress = JobProgress(),
                    pauseReason = null,
                    error = null,
                    events = emptyList(),
                    createdAtEpochMs = 0L,
                    updatedAtEpochMs = now,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "query job remote failure"))
        }
    }

    override fun cancel(jobId: JobId, requestOnly: Boolean): OmniResult<JobRecord> {
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = UUID.randomUUID().toString()
                idempotencyKey = "cancel-${jobId.value}"
                canonicalInputDigest = "e".repeat(64)
            }
            admin.cancelJob(jobId.value, cmd)
            query(jobId)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "cancel job remote failure"))
        }
    }
}

/** Lightweight device probe for UI onboarding (no privileged sensors). */
private class AndroidUiDeviceProbe : DeviceProbePort {
    override suspend fun discover(): OmniResult<DeviceDiscoverySnapshot> =
        OmniResult.ok(
            DeviceDiscoverySnapshot(
                fingerprint = DeviceExecutionFingerprint.parse(
                    "android-ui-${android.os.Build.FINGERPRINT.take(48).replace(Regex("[^a-zA-Z0-9._-]"), "_")}",
                ),
                schemaVersion = "1",
                platform = "android",
                osBuild = android.os.Build.DISPLAY ?: "unknown",
                abiList = android.os.Build.SUPPORTED_ABIS?.toList().orEmpty(),
                ramClassBytes = null,
                freeStorageBytes = null,
                batteryPercent = null,
                thermalOk = true,
                gpuEvidenceLabel = EvidenceLabel.UNKNOWN,
                npuEvidenceLabel = EvidenceLabel.UNKNOWN,
                discoveredAtEpochMs = System.currentTimeMillis(),
            ),
        )
}
