package com.omnillm.android.runtimeservice.featurehost

import android.content.Context
import com.omnillm.android.runtimeservice.binder.ClientRegistrationStore
import com.omnillm.android.runtimeservice.binder.StreamSessionRegistry
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.http.GatewayLifecycle
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.features.admin.AdminFeatureModule
import com.omnillm.features.admin.ports.AdminModelPort
import com.omnillm.features.admin.ports.AdminRuntimeStatusPort
import com.omnillm.features.admin.ports.ModelRevisionSummary
import com.omnillm.features.autosetup.AutoSetupModule
import com.omnillm.features.autosetup.catalog.FixtureCatalogCandidates
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.ports.AutoSetupRuntimePorts
import com.omnillm.features.autosetup.ports.DeviceProbePort
import com.omnillm.features.autosetup.wiring.JobManagerAutoSetupPort
import com.omnillm.features.autosetup.wiring.ModelManagerAutoSetupPort
import com.omnillm.features.autosetup.wiring.OrchestratorAutoSetupPort
import com.omnillm.features.dashboard.DashboardFeatureModule
import com.omnillm.features.dashboard.ports.GovernorResourceAdapter
import com.omnillm.features.dashboard.ports.RegistryBackedCapabilityPort
import com.omnillm.features.modelhub.ModelhubModule
import com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog
import com.omnillm.features.modelhub.ports.AcquisitionLinkStore
import com.omnillm.features.modelhub.ports.InMemoryAcquisitionLinkStore
import com.omnillm.features.modelhub.ports.InMemoryInstallationResourceVersionPort
import com.omnillm.features.modelhub.ports.InMemoryModelDisplayMetadataPort
import com.omnillm.features.modelhub.ports.LiveReferenceQueryPort
import com.omnillm.features.modelhub.ports.LoadedModelLifecyclePort
import com.omnillm.features.modelhub.ports.LoadedModelQueryPort
import com.omnillm.features.playground.PlaygroundModule
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.ports.PlaygroundModelCatalogPort
import com.omnillm.features.playground.ports.PlaygroundModelRow
import com.omnillm.features.playground.ports.PlaygroundRuntimeStatusPort
import com.omnillm.features.server.ServerFeatureModule
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.ports.ClientAdminPort
import com.omnillm.features.server.ports.LoopbackServerPort
import com.omnillm.features.server.ports.ServerMetricsPort
import com.omnillm.features.server.ports.ServerRuntimePorts
import com.omnillm.features.server.ports.TokenAdminPort
import com.omnillm.interfaces.admin.AdminApiService
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.governor.ResourceGovernor
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import com.omnillm.runtime.observability.ObservabilityFacade
import com.omnillm.runtime.orchestrator.CandidatePlanner
import com.omnillm.runtime.orchestrator.CapabilityLookup
import com.omnillm.runtime.orchestrator.HealthLookup
import com.omnillm.runtime.orchestrator.HealthSnapshot
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.requestregistry.RequestRegistry
import com.omnillm.runtime.requestregistry.CommitLedger
import kotlinx.coroutines.runBlocking

/**
 * Wave-A Feature Pack wiring (admin, auto-setup, modelhub, playground, server, dashboard).
 *
 * Inference path:
 * - Uses [EngineExecuteBinding] (delegating port + honest capability lookup).
 * - After [com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane.ensureEnginePacksAttached],
 *   the plane binds llama-cpp via [EngineExecuteBinding.applyAttachment].
 * - Permanent FailClosed only when native missing / unbound.
 * - Experimental generate requires `runtime.exploratoryExecuteEnabled` (CONDITIONAL, never SUPPORTED).
 */
object WaveAWiring {
    val WAVE_A_FEATURE_IDS: Set<String> = setOf(
        AdminFeatureModule.FEATURE_ID,
        AutoSetupModule.FEATURE_ID,
        ModelhubModule.FEATURE_ID,
        PlaygroundModule.FEATURE_ID,
        ServerFeatureModule.FEATURE_ID,
        DashboardFeatureModule.FEATURE_ID,
    )

    data class Deps(
        val adminApi: AdminApiService,
        val jobManager: JobManager,
        val modelManager: ModelManager,
        val requestRegistry: RequestRegistry,
        val observability: ObservabilityFacade,
        val runtimeState: () -> String,
        val lanState: () -> String,
        val runtimeEpoch: () -> Long,
        val bootId: () -> String,
        val appContext: Context? = null,
        val registrations: ClientRegistrationStore = ClientRegistrationStore(),
        val tokenService: () -> LoopbackTokenService? = { GatewayLifecycle.tokenService() },
        val ensureGatewayStarted: () -> Int? = { null },
        val isGatewayRunning: () -> Boolean = { GatewayLifecycle.isRunning() },
        val clockMs: () -> Long = { System.currentTimeMillis() },
        /**
         * Engine execute binding (shared with control plane attach).
         * When null, a local binding is created (tests / hermetic bootstrap).
         */
        val engineExecute: EngineExecuteBinding? = null,
        /** Optional override for unit tests with FakeInferenceEngine. */
        val inferenceEngineOverride: InferenceEnginePort? = null,
        val capabilityLookupOverride: CapabilityLookup? = null,
        /**
         * Late-bound ToolsApi for STRUCTURED_TOOLS tab (set after FeaturePackHost tools attach).
         */
        val toolsApiHolder: ToolsApiHolder = ToolsApiHolder(),
        /** Late-bound BenchmarkApi for dashboard MEASUREMENTS last-run projection. */
        val benchmarkApiHolder: BenchmarkApiHolder = BenchmarkApiHolder(),
        /**
         * Live binder stream sessions for COR-06 reference counting. Defaults to
         * the control-plane registry; overridable in tests. Process-local: after
         * restart the registry is empty (sessions are non-durable by design).
         */
        val streamSessions: () -> StreamSessionRegistry = {
            RuntimeControlPlane.get()?.streamSessions ?: StreamSessionRegistry()
        },
        /**
         * Effective policy settings snapshot for governor capacities (ARC-10).
         * Null (default) resolves catalog defaults, which equal the historical
         * hardcoded capacities (512MiB anon / 8GiB file / 64 threads / 1024 FDs).
         */
        val settings: () -> com.omnillm.runtime.policy.SettingsSnapshot? = { null },
        /**
         * Durable commit recovery ledger (C-01). Production control plane
         * injects the SQLite-backed [CommitLedger] so orchestrator
         * INTENT_RECORDED / outcome rows survive restart (REL-RECOVERY).
         * Null in unit scaffolds — orchestrator then runs without intent
         * persistence (existing test behavior unchanged).
         */
        val commitLedger: CommitLedger? = null,
    )

    fun bootstrapForTest(
        adminApi: AdminApiService,
        jobManager: JobManager,
        requestRegistry: RequestRegistry,
        observability: ObservabilityFacade,
        runtimeState: () -> String = { "READY" },
        lanState: () -> String = { "DISABLED" },
        runtimeEpoch: () -> Long = { 1L },
        bootId: () -> String = { "boot-wave-a-test" },
        clockMs: () -> Long = { 1_700_000_000_000L },
        modelManager: ModelManager = ModelManagerModule.createInMemoryControlPlane(),
        engineExecute: EngineExecuteBinding? = null,
        inferenceEngineOverride: InferenceEnginePort? = null,
        capabilityLookupOverride: CapabilityLookup? = null,
    ): WaveAFeaturePacks = wire(
        Deps(
            adminApi = adminApi,
            jobManager = jobManager,
            modelManager = modelManager,
            requestRegistry = requestRegistry,
            observability = observability,
            runtimeState = runtimeState,
            lanState = lanState,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            clockMs = clockMs,
            engineExecute = engineExecute,
            inferenceEngineOverride = inferenceEngineOverride,
            capabilityLookupOverride = capabilityLookupOverride,
        ),
    )

    fun wire(deps: Deps): WaveAFeaturePacks {
        // Governor capacities come from the configuration catalog (ARC-10):
        // effective settings when provided, catalog defaults otherwise. Defaults
        // preserve the historical hardcoded values exactly (512MiB anon / 8GiB
        // file / 64 threads / 1024 FDs), so admission math is unchanged.
        val caps = com.omnillm.runtime.policy.ConfigurationCatalog
            .governorCapacities(deps.settings?.invoke())
        val governor = ResourceGovernor(
            // Include FDs / file dimensions so inference envelopes can admit
            // (ResourceVector.dominates is multi-dimensional).
            capacity = ResourceVector(
                cpuAnonBytes = caps.anonMemoryBytes,
                cpuFileBytes = caps.fileCacheBytes,
                nativeThreads = caps.threads,
                fileDescriptors = caps.fileDescriptors,
                temporaryDiskBytes = caps.temporaryDiskBytes,
            ),
            safetyMargin = ResourceVector(
                cpuAnonBytes = 16L * 1024L * 1024L,
                nativeThreads = 4L,
                fileDescriptors = 16L,
            ),
            issuerBootId = deps.bootId(),
            runtimeEpoch = deps.runtimeEpoch(),
        )

        val binding = deps.engineExecute ?: EngineExecuteBinding()
        val enginePort: InferenceEnginePort =
            deps.inferenceEngineOverride ?: binding.inferenceEngine
        val capabilities: CapabilityLookup =
            deps.capabilityLookupOverride ?: binding.capabilityLookup

        val orchestrator = OrchestratorModule.create(
            registry = deps.requestRegistry,
            governor = governor,
            engine = enginePort,
            capabilities = capabilities,
            health = HealthLookup { HealthSnapshot() },
            issuerBootId = deps.bootId(),
            runtimeEpoch = deps.runtimeEpoch(),
            commitLedger = deps.commitLedger,
        )

        val planner = CandidatePlanner(
            capabilities = capabilities,
            health = HealthLookup { HealthSnapshot() },
            engine = enginePort,
        )

        val admin = AdminFeatureModule.createApi(
            adminApi = deps.adminApi,
            models = object : AdminModelPort {
                override fun listRevisionSummaries(): List<ModelRevisionSummary> = runBlocking {
                    deps.modelManager.listInstallations().map {
                        ModelRevisionSummary(it.modelRevisionId.hex, null, it.state, null)
                    }
                }
            },
            runtimeStatus = object : AdminRuntimeStatusPort {
                override fun runtimeState(): String = deps.runtimeState()
                override fun lanState(): String = deps.lanState()
                override fun resourcePressureLabel(): String? = null
            },
        )
        // Pure plan via CandidatePlanner (no domain mutation — ADR-002).
        val planOnly: suspend (OrchestrationRequest) -> OmniResult<PlanningResult> = { req ->
            planner.plan(req)
        }
        // Offline fixture catalog (software E2E): pin download + SAF hint.
        // Honest capability: TEXT_GENERATION remains UNKNOWN without device evidence.
        val offlineCatalog = OfflineFixtureCatalog.DEFAULT
        val autoSetup = AutoSetupModule.createApi(
            AutoSetupRuntimePorts(
                deviceProbe = JvmDeviceProbe(deps.clockMs),
                catalog = FixtureCatalogCandidates(),
                jobs = JobManagerAutoSetupPort(deps.jobManager),
                models = ModelManagerAutoSetupPort(deps.modelManager),
                orchestrator = OrchestratorAutoSetupPort.from(orchestrator, planOnly),
                clockMs = deps.clockMs,
            ),
        )
        // COR-06/COR-07: real loaded-model + live-reference wiring. LoadedModel
        // rows are process-local (DATA-OWNERSHIP) — the tracker observes the
        // modelhub lifecycle and resolves authoritative FSM state via ModelManager.
        val loadedModelTracker = ControlPlaneLoadedModelTracker(deps.modelManager)
        val linkStore = InMemoryAcquisitionLinkStore()
        val modelHub = ModelhubModule.createApi(
            jobManager = deps.jobManager,
            modelManager = deps.modelManager,
            catalog = offlineCatalog,
            display = InMemoryModelDisplayMetadataPort(),
            links = linkStore,
            loadedModels = ControlPlaneLoadedModelQueryPort(
                tracker = loadedModelTracker,
                modelManager = deps.modelManager,
            ),
            references = ControlPlaneLiveReferenceQueryPort(
                tracker = loadedModelTracker,
                modelManager = deps.modelManager,
                jobManager = deps.jobManager,
                links = linkStore,
                sessions = deps.streamSessions,
            ),
            loadRuntime = ControlPlaneModelLoadRuntimePort(deps.engineExecute),
            lifecycle = loadedModelTracker,
            resourceVersions = InMemoryInstallationResourceVersionPort(),
            clockMs = deps.clockMs,
        )
        val playground = PlaygroundModule.createApi(
            ports = PlaygroundFeaturePorts(
                inference = ControlPlaneFeaturePorts.playgroundInference(
                    orchestrator = orchestrator,
                    binding = binding,
                    modelManager = deps.modelManager,
                    clockMs = deps.clockMs,
                    runtimeEpoch = deps.runtimeEpoch,
                ),
                capabilities = ControlPlaneFeaturePorts.playgroundCapabilities(
                    binding,
                    runBlocking { deps.modelManager.listInstallations() },
                    // FTR-03: honest mode projection from the effective settings
                    // snapshot (fail-closed default when settings absent).
                    productModes = {
                        com.omnillm.runtime.policy.ProductModePolicy
                            .projectedModes(deps.settings?.invoke())
                    },
                ),
                models = object : PlaygroundModelCatalogPort {
                    override fun listModels(): List<PlaygroundModelRow> = runBlocking {
                        deps.modelManager.listInstallations().map {
                            PlaygroundModelRow(it.modelRevisionId.hex, it.modelRevisionId.hex.take(12), it.state)
                        }
                    }
                },
                runtimeStatus = object : PlaygroundRuntimeStatusPort {
                    override fun runtimeState(): String = deps.runtimeState()
                },
                structured = ToolsApiPlaygroundStructuredAdapter(deps.toolsApiHolder),
            ),
            clockMs = deps.clockMs,
        )
        val server = ServerFeatureModule.createApi(
            ServerRuntimePorts(
                loopback = object : LoopbackServerPort {
                    override suspend fun status() = OmniResult.ok(
                        LoopbackServerStatus(
                            enabled = deps.isGatewayRunning(),
                            running = deps.isGatewayRunning(),
                            host = GatewayConfig.DEFAULT_HOST,
                            port = GatewayConfig.DEFAULT_PORT,
                            runtimeState = deps.runtimeState(),
                            resourceVersion = deps.runtimeEpoch(),
                        ),
                    )
                    override suspend fun ensureStarted(principal: PrincipalId): OmniResult<LoopbackServerStatus> {
                        deps.ensureGatewayStarted()
                        return status()
                    }
                },
                tokens = object : TokenAdminPort {
                    override suspend fun listTokens(principal: PrincipalId): OmniResult<List<DeveloperTokenView>> {
                        val svc = deps.tokenService() ?: return OmniResult.ok(emptyList())
                        return OmniResult.ok(svc.listMetadata().map {
                            DeveloperTokenView(
                                tokenId = it.tokenId,
                                clientId = it.clientId ?: "unknown",
                                state = if (it.revoked) "REVOKED" else "ACTIVE",
                                scopes = it.scopes,
                                expiresAtEpochMs = it.expiresAt.toEpochMilli(),
                                // Epoch fence: TOKEN-subject epoch bumps on revoke; list surfaces
                                // durable principal epoch bound at issue (authenticate re-checks).
                                revocationEpoch = it.revocationEpoch,
                                loopbackOnly = it.loopbackOnly,
                                label = it.label,
                            )
                        })
                    }
                    override suspend fun issue(principal: PrincipalId, clientId: String, displayName: String, scopes: Set<String>, expiresInSeconds: Long, label: String?, command: ServerCommandIdentity): OmniResult<TokenIssuanceReceipt> {
                        val svc = deps.tokenService() ?: return OmniResult.err(OmniError.STATE_CONFLICT(message = "token service not started"))
                        val issued = svc.issue(principal.value, scopes, expiresInSeconds, true, label ?: displayName, clientId)
                        val now = deps.clockMs()
                        // Plaintext once + issuanceKey for Secret Broker one-time re-display.
                        return OmniResult.ok(
                            TokenIssuanceReceipt(
                                tokenId = issued.tokenId,
                                clientId = clientId,
                                tokenPlaintext = issued.plaintext,
                                scopes = issued.scopes,
                                expiresAtEpochMs = issued.expiresAt.toEpochMilli(),
                                receiptExpiresAtEpochMs = now + 60_000L,
                                revocationEpoch = issued.revocationEpoch,
                                loopbackOnly = issued.loopbackOnly,
                                issuanceKey = issued.issuanceKey,
                            ),
                        )
                    }
                    override suspend fun revoke(principal: PrincipalId, tokenId: String, command: ServerCommandIdentity): OmniResult<DeveloperTokenView> {
                        val svc = deps.tokenService() ?: return OmniResult.err(OmniError.STATE_CONFLICT(message = "token service not started"))
                        val existing = svc.get(tokenId) ?: return OmniResult.err(OmniError.NOT_FOUND(message = "token not found"))
                        // TokenService.revoke durable-bumps TOKEN-subject epoch (fence streams/auth).
                        val revoked = svc.revoke(tokenId)
                        if (!revoked) {
                            return OmniResult.err(
                                OmniError.STATE_CONFLICT(
                                    message = "token revoke failed or already terminal",
                                    details = mapOf("tokenId" to tokenId),
                                ),
                            )
                        }
                        val after = svc.get(tokenId) ?: existing
                        // Surface fenced epoch: post-revoke TOKEN epoch is > 0 (authenticate rejects).
                        val fencedEpoch = after.revocationEpoch.coerceAtLeast(existing.revocationEpoch)
                        return OmniResult.ok(
                            DeveloperTokenView(
                                tokenId = after.tokenId,
                                clientId = after.clientId ?: "unknown",
                                state = "REVOKED",
                                scopes = after.scopes,
                                expiresAtEpochMs = after.expiresAt.toEpochMilli(),
                                revocationEpoch = fencedEpoch,
                                loopbackOnly = after.loopbackOnly,
                                label = after.label,
                            ),
                        )
                    }
                },
                clients = object : ClientAdminPort {
                    override suspend fun listClients(principal: PrincipalId) = OmniResult.ok(emptyList<ClientSummaryView>())
                    override suspend fun createClient(principal: PrincipalId, clientId: String, displayName: String, scopes: Set<String>, command: ServerCommandIdentity) =
                        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "client create requires runtime host with context"))
                    override suspend fun revoke(principal: PrincipalId, clientId: String, command: ServerCommandIdentity) =
                        OmniResult.err(OmniError.NOT_FOUND(message = "client not found"))
                },
                capabilities = ControlPlaneFeaturePorts.serverCapabilities(
                    binding = binding,
                    modelManager = deps.modelManager,
                    clockMs = deps.clockMs,
                    // FTR-03: honest mode projection from the effective settings
                    // snapshot (fail-closed default when settings absent).
                    productModes = {
                        com.omnillm.runtime.policy.ProductModePolicy
                            .projectedModes(deps.settings?.invoke())
                    },
                ),
                inference = ControlPlaneFeaturePorts.serverInference(
                    orchestrator = orchestrator,
                    binding = binding,
                    modelManager = deps.modelManager,
                    clockMs = deps.clockMs,
                    runtimeEpoch = deps.runtimeEpoch,
                ),
                metrics = object : ServerMetricsPort {
                    override suspend fun summary(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> {
                        val snap = deps.observability.metricSummary()
                        return OmniResult.ok(snap.samples.map { EvidencedMetricView(it.name, it.value, it.unit, it.evidenceLabel, it.sampledAtEpochMs, source = it.source) })
                    }
                },
                clockMs = deps.clockMs,
            ),
        )
        // Observability capabilities are software-side SUPPORTED when the control-plane
        // facade is attached (honest: no engine QUALIFIED claim; EVIDENCE_LABELING always on).
        // C-04: engine/orchestrator cells are projected from the plane's real
        // CapabilityLookup (never fabricated SUPPORTED) — bound+dev → CONDITIONAL,
        // release/unbound → UNKNOWN.
        val dashboard = DashboardFeatureModule.createApi(
            facade = deps.observability,
            resources = GovernorResourceAdapter(snapshot = { governor.snapshot() }, clockWallMs = deps.clockMs),
            capabilities = RegistryBackedCapabilityPort(
                engineState = { cap -> capabilities.state(cap, dashboardCapabilityProbe(binding)) },
            ),
            measurements = DeferredDashboardMeasurementPort(deps.benchmarkApiHolder),
            clockWallMs = deps.clockMs,
        )
        val packs = WaveAFeaturePacks(
            admin = admin,
            autoSetup = autoSetup,
            modelHub = modelHub,
            playground = playground,
            server = server,
            dashboard = dashboard,
            modelManager = deps.modelManager,
            orchestrator = orchestrator,
            resourceGovernor = governor,
            engineExecute = binding,
            candidatePlanner = planner,
            toolsApiHolder = deps.toolsApiHolder,
            benchmarkApiHolder = deps.benchmarkApiHolder,
        )
        packs.assertAllServicesNonNull()
        return packs
    }
}

/**
 * C-04: probe candidate for dashboard capability projection. Carries the
 * binding's own attached engineBuildId (or the unbound marker) so the plane's
 * real CapabilityLookup resolves honestly: unbound → UNKNOWN, bound+release →
 * UNKNOWN, bound+dev-override → CONDITIONAL. Never SUPPORTED without the
 * engine evidence path. The candidate is ephemeral — projection only, never
 * submitted to claim/commit ledgers.
 */
private fun dashboardCapabilityProbe(binding: EngineExecuteBinding): RoutingCandidate {
    val build = binding.attachment?.llamaCppEngine?.engineBuildId
        ?: binding.attachment?.llamaCppRegistration?.engineBuildId
        ?: com.omnillm.core.contracts.EngineBuildId.parse("engine-build-unbound")
    return RoutingCandidate(
        candidateId = "dashboard-probe",
        modelRevisionId = com.omnillm.core.canonical.generated.ModelRevisionId.parse("0".repeat(64)),
        installationId = com.omnillm.core.identity.InstallationId.parse(
            "550e8400-e29b-41d4-a716-446655440000",
        ),
        engineBuildId = build,
        backend = "cpu",
        placementClass = EngineExecuteBinding.EXPLORATORY_PLACEMENT,
        loadKeyDigest = com.omnillm.core.canonical.generated.Sha256Digest.parse("a".repeat(64)),
        isPrimary = true,
        deviceExecutionFingerprint = com.omnillm.core.contracts.DeviceExecutionFingerprint.parse(
            "device-fp-dashboard-probe",
        ),
    )
}

class JvmDeviceProbe(private val clockMs: () -> Long) : DeviceProbePort {
    override suspend fun discover(): OmniResult<DeviceDiscoverySnapshot> = OmniResult.ok(
        DeviceDiscoverySnapshot(
            fingerprint = DeviceExecutionFingerprint.parse("jvm-fp-control-plane-wave-a"),
            schemaVersion = "1",
            platform = "jvm",
            osBuild = System.getProperty("os.name") ?: "unknown",
            abiList = listOf(System.getProperty("os.arch") ?: "unknown"),
            thermalOk = true,
            gpuEvidenceLabel = EvidenceLabel.UNKNOWN,
            npuEvidenceLabel = EvidenceLabel.UNKNOWN,
            discoveredAtEpochMs = clockMs(),
        ),
    )
}

// ---------------------------------------------------------------------------
// COR-06 / COR-07: real loaded-model + live-reference wiring
// ---------------------------------------------------------------------------

/**
 * In-process installation↔loadedModel index (COR-06/COR-07).
 *
 * LoadedModel rows are process-local by design (DATA-OWNERSHIP: native handles
 * are non-durable), so a process-local tracker observes modelhub load/drain
 * events and the query port resolves authoritative FSM state through
 * [ModelManager.getLoadedModel]. Unknown entries count as live (fail closed).
 */
private class ControlPlaneLoadedModelTracker(
    private val modelManager: ModelManager,
) : LoadedModelLifecyclePort {

    private val byInstallation = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    override fun onLoadAdmitted(installationId: String, loadedModelId: String) {
        byInstallation.computeIfAbsent(installationId) {
            java.util.concurrent.ConcurrentHashMap.newKeySet()
        }.add(loadedModelId)
    }

    override fun onDrainRequested(installationId: String, loadedModelId: String) {
        // Keep the entry: authoritative state (DRAINING → UNLOADED) resolves
        // through ModelManager; terminal rows are pruned lazily on query.
    }

    fun loadedModelIdsFor(installationId: String): List<String> =
        byInstallation[installationId]?.toList().orEmpty()
}

/** Authoritative LoadedModel lookup for the modelhub projection ports. */
private class ControlPlaneLoadedModelQueryPort(
    private val tracker: ControlPlaneLoadedModelTracker,
    private val modelManager: ModelManager,
) : LoadedModelQueryPort {
    override suspend fun findByInstallation(installationId: String): List<LoadedModelSnapshot> =
        tracker.loadedModelIdsFor(installationId).mapNotNull { id ->
            modelManager.getLoadedModel(LoadedModelId(id))?.takeIf { !it.isTerminal() }
        }
}

/**
 * Real live-reference counting for delete/drain guards (COR-06):
 * - loadedModelCount: tracked LoadedModels with authoritative non-terminal FSM state
 * - jobCount: active jobs linked to the installation (delete jobs excluded —
 *   the delete pipeline must not self-block)
 * - sessionCount/requestCount: live binder streams bound to the installation
 *   revision (live inference requires a loaded model, so this is covered both
 *   ways; durable claims without live sessions are fenced/reconciling, not live work)
 * - leaseCount: revision lease pins (delete fencing)
 *
 * Unknown loaded-model rows count as live (fail closed — never zero).
 */
private class ControlPlaneLiveReferenceQueryPort(
    private val tracker: ControlPlaneLoadedModelTracker,
    private val modelManager: ModelManager,
    private val jobManager: JobManager,
    private val links: AcquisitionLinkStore,
    private val sessions: () -> StreamSessionRegistry,
) : LiveReferenceQueryPort {

    override suspend fun installationReferences(installationId: String): LiveReferences {
        val install = modelManager.getInstallation(InstallationId(installationId))
        val revision = install?.modelRevisionId?.hex

        val loaded = tracker.loadedModelIdsFor(installationId).count { id ->
            val snap = modelManager.getLoadedModel(LoadedModelId(id))
            snap == null || !snap.isTerminal()
        }
        val jobs = jobManager.listActive().count { record ->
            record.identity.kind != JobKind.DELETE &&
                links.installationIdForJob(record.identity.jobId.value) == installationId
        }
        val liveSessions = if (revision != null) {
            sessions().all().count { session ->
                !session.isClosed() && session.modelRevisionIdValue?.lowercase() == revision
            }
        } else {
            0
        }
        val leaseCount = if (revision != null) {
            val pinned = modelManager.isRevisionPinnedByLease(
                ModelRevisionId.parse(revision),
            )
            if (pinned) 1 else 0
        } else {
            0
        }
        return LiveReferences(
            requestCount = liveSessions,
            sessionCount = liveSessions,
            loadedModelCount = loaded,
            jobCount = jobs,
            leaseCount = leaseCount,
        )
    }
}
