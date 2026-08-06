package com.omnillm.features.autosetup

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId as StateInstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.ModelCandidateInput
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.features.autosetup.ports.AutoSetupJobPort
import com.omnillm.features.autosetup.ports.AutoSetupModelPort
import com.omnillm.features.autosetup.ports.AutoSetupOrchestratorPort
import com.omnillm.features.autosetup.ports.AutoSetupRuntimePorts
import com.omnillm.features.autosetup.ports.CatalogCandidatePort
import com.omnillm.features.autosetup.ports.DeviceProbePort
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult
import com.omnillm.runtime.orchestrator.ClaimKind
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal fun digest(seed: Char = 'a'): Sha256Digest {
    require(seed in '0'..'9' || seed in 'a'..'f')
    return Sha256Digest.parse(seed.toString().repeat(64))
}

internal fun revision(seed: Char = '1'): ModelRevisionId {
    require(seed in '0'..'9' || seed in 'a'..'f')
    return ModelRevisionId.parse(seed.toString().repeat(64))
}

internal fun artifact(seed: Char = '2'): ArtifactPackageId {
    require(seed in '0'..'9' || seed in 'a'..'f')
    return ArtifactPackageId.parse(seed.toString().repeat(64))
}

internal fun device(
    ramBytes: Long? = 8L * 1024 * 1024 * 1024,
    freeStorage: Long? = 32L * 1024 * 1024 * 1024,
    gpuEvidence: EvidenceLabel = EvidenceLabel.UNKNOWN,
    npuEvidence: EvidenceLabel = EvidenceLabel.UNKNOWN,
): DeviceDiscoverySnapshot =
    DeviceDiscoverySnapshot(
        fingerprint = DeviceExecutionFingerprint.parse("device-fp-test-auto-setup-1"),
        schemaVersion = "1",
        platform = "android",
        osBuild = "test-build",
        abiList = listOf("arm64-v8a"),
        ramClassBytes = ramBytes,
        freeStorageBytes = freeStorage,
        batteryPercent = 80,
        thermalOk = true,
        gpuEvidenceLabel = gpuEvidence,
        npuEvidenceLabel = npuEvidence,
        discoveredAtEpochMs = 1_000L,
    )

internal fun prefs(
    op: CapabilityId = CapabilityId.TEXT_GENERATION,
    maxStorage: Long? = null,
    preferTrusted: Boolean = true,
): UserSetupPreferences =
    UserSetupPreferences(
        targetOperation = op,
        qualityPreference = 0.6,
        speedPreference = 0.4,
        maxStorageBytes = maxStorage,
        preferLowPower = false,
        preferTrustedOnly = preferTrusted,
    )

internal fun candidate(
    id: String = "cand-small",
    name: String = "Small Chat",
    rev: ModelRevisionId = revision('1'),
    backend: String = "cpu",
    placement: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    capState: CapabilityState = CapabilityState.SUPPORTED,
    op: CapabilityId = CapabilityId.TEXT_GENERATION,
    peak: Long = 512L * 1024 * 1024,
    packageBytes: Long = 200L * 1024 * 1024,
    licenseOk: Boolean = true,
    authOk: Boolean = true,
    stability: EvidenceLabel = EvidenceLabel.MEASURED,
    quality: Double = 0.4,
    speed: Double = 0.8,
    readyInstall: String? = null,
): ModelCandidateInput =
    ModelCandidateInput(
        candidateId = id,
        displayName = name,
        modelRevisionId = rev,
        artifactPackageId = artifact('2'),
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = backend,
        placementClass = placement,
        operationCapabilityState = capState,
        targetOperation = op,
        estimatedPeakBytes = peak,
        packageBytes = packageBytes,
        licenseOk = licenseOk,
        authenticityOk = authOk,
        stabilityEvidence = stability,
        qualityScore = quality,
        speedScore = speed,
        readyInstallationId = readyInstall,
    )

internal class FakeDeviceProbe(
    var snapshot: DeviceDiscoverySnapshot = device(),
    var fail: Boolean = false,
) : DeviceProbePort {
    override suspend fun discover(): OmniResult<DeviceDiscoverySnapshot> =
        if (fail) {
            OmniResult.err(OmniError.INTERNAL(message = "probe failed"))
        } else {
            OmniResult.ok(snapshot)
        }
}

internal class FakeCatalog(
    var candidates: List<ModelCandidateInput> = emptyList(),
) : CatalogCandidatePort {
    override suspend fun listCandidates(targetOperationId: String): OmniResult<List<ModelCandidateInput>> =
        OmniResult.ok(candidates.filter { it.targetOperation.id == targetOperationId })
}

internal class FakeJobPort(
    private val manager: JobManager = JobManager(clock = { 1_000L }),
) : AutoSetupJobPort {
    val underlying: JobManager get() = manager

    override fun create(identity: JobIdentity, parameters: JobParameters): OmniResult<JobRecord> =
        when (val r = manager.create(identity, parameters)) {
            is OmniResult.Ok -> OmniResult.ok(r.value.record)
            is OmniResult.Err -> OmniResult.err(r.error)
        }

    override fun query(jobId: JobId): OmniResult<JobRecord> = manager.query(jobId)

    override fun cancel(jobId: JobId, requestOnly: Boolean): OmniResult<JobRecord> =
        manager.cancel(jobId, requestOnly)
}

internal class FakeModelPort : AutoSetupModelPort {
    private val installs = linkedMapOf<String, InstallationSnapshot>()

    fun put(snapshot: InstallationSnapshot) {
        installs[snapshot.installationId.value] = snapshot
    }

    override suspend fun getInstallation(installationId: StateInstallationId): InstallationSnapshot? =
        installs[installationId.value]

    override suspend fun discoverInstallation(
        installationId: StateInstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<InstallationSnapshot> {
        val snap = InstallationSnapshot.discovered(
            installationId,
            modelRevisionId,
            artifactPackageId,
        )
        installs[installationId.value] = snap
        return OmniResult.ok(snap)
    }
}

internal class FakeOrchestratorPort(
    var planResult: OmniResult<PlanningResult> = OmniResult.ok(
        PlanningResult(viable = emptyList(), rejections = emptyList()),
    ),
    var submitState: String = "QUEUED",
    var cancelOk: Boolean = true,
) : AutoSetupOrchestratorPort {
    val submitted = mutableListOf<OrchestrationRequest>()
    val cancelled = mutableListOf<RequestId>()
    private val states = linkedMapOf<String, String>()

    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> = planResult

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> {
        submitted += request
        states[request.requestId.value] = submitState
        return OmniResult.ok(
            SubmitResult(
                requestId = request.requestId,
                claim = ClaimKind.NEW,
                state = submitState,
                earliestStart = null,
                planning = null,
                actualRouting = null,
            ),
        )
    }

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> {
        cancelled += requestId
        if (!cancelOk) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "cannot cancel"))
        }
        states[requestId.value] = "CANCELLED"
        return OmniResult.ok(Unit)
    }

    override suspend fun queryRequestState(requestId: RequestId): OmniResult<String> {
        val s = states[requestId.value]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "missing"))
        return OmniResult.ok(s)
    }

    fun setState(requestId: String, state: String) {
        states[requestId] = state
    }
}

internal fun ports(
    device: FakeDeviceProbe = FakeDeviceProbe(),
    catalog: FakeCatalog = FakeCatalog(),
    jobs: FakeJobPort = FakeJobPort(),
    models: FakeModelPort = FakeModelPort(),
    orch: FakeOrchestratorPort = FakeOrchestratorPort(),
    clock: AtomicLong = AtomicLong(1_000L),
): AutoSetupRuntimePorts =
    AutoSetupRuntimePorts(
        deviceProbe = device,
        catalog = catalog,
        jobs = jobs,
        models = models,
        orchestrator = orch,
        clockMs = { clock.get() },
    )

internal fun uuid(): String = UUID.randomUUID().toString()

internal fun principal(): PrincipalId = PrincipalId.parse("local-ui")

internal fun requestId(): RequestId = RequestId.parse(uuid())

internal fun idem(key: String = "idem-${uuid()}"): IdempotencyKey = IdempotencyKey.parse(key)

internal fun jobId(): JobId = JobId(uuid())
