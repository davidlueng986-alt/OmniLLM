package com.omnillm.android.runtimeservice.controlplane

import android.content.Context
import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.omnillm.android.runtimeservice.BuildConfig
import com.omnillm.android.runtimeservice.binder.AssetHandleBroker
import com.omnillm.android.runtimeservice.binder.ClientRegistrationStore
import com.omnillm.android.runtimeservice.binder.StreamSessionRegistry
import com.omnillm.android.runtimeservice.featurehost.FeaturePackHost
import com.omnillm.android.runtimeservice.featurehost.WaveAFeaturePacks
import com.omnillm.android.runtimeservice.featurehost.WaveAWiring
import com.omnillm.android.runtimeservice.http.ControlPlaneLanTlsEndpoint
import com.omnillm.android.runtimeservice.http.GatewayLifecycle
import com.omnillm.android.runtimeservice.process.ProcessIdentity
import com.omnillm.android.runtimeservice.security.ControlPlaneSecurityFactory
import com.omnillm.android.runtimeservice.storage.AndroidStorageRoots
import com.omnillm.data.modelstore.ModelStoreModule
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.StorageLayout
import com.omnillm.data.persistence.CommitReconcileResult
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.ports.ledger.ControlPlaneWriter
import com.omnillm.data.persistence.OmniLlmDatabase
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.runtime.modelmanager.DefaultPrivilegedLoadReverify
import com.omnillm.features.admin.usecase.AdminFeatureApi
import com.omnillm.features.autosetup.api.AutoSetupApi
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.lan.api.LanAccessApi
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.playground.api.PlaygroundApi
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.server.api.DeveloperServerApi
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.interfaces.admin.AdminApiService
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.SessionModule
import com.omnillm.runtime.governor.ResourceGovernor
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.observability.ObservabilityFacade
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.requestregistry.CommandLedger
import com.omnillm.runtime.requestregistry.CommitLedger
import com.omnillm.runtime.requestregistry.RequestRegistry
import com.omnillm.runtime.session.SessionManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single control-plane host for the `:runtime` process (ADR-010 / INV-001).
 *
 * Constructed only from the runtime process Application / service path.
 * UI process must never call [attach].
 *
 * Holds:
 * - RUNTIME lifecycle + bootId/runtimeEpoch
 * - Single-writer role assertion
 * - Durable claim + commit + session + job + installation/lease ledgers
 *   (SQLDelight / SQLite under [ControlPlaneDatabase])
 * - Filesystem model-store quarantine + atomic promote (DATA-STORAGE / CORE-MODEL)
 * - [SessionManager] with durable control-plane Session records (CORE-SESSION / INV-007)
 * - [JobManager] with durable job/attempt/event/checkpoint ledger (FEAT-ADMIN / REL-RECOVERY)
 * - Durable [ModelManager] (installations / revision leases / catalog projections)
 * - [AdminApiService] for non-exported LOCAL_UI Admin binder
 * - Wave-A Feature Packs: admin, auto-setup, modelhub, playground, server, dashboard
 * - Wave-B Feature Pack host: lan, benchmark, diagnostics, routing, tools, content-report
 * - Orchestrator (inference port fail-closed until cells SUPPORTED)
 * - Engine Registry for **all** catalog engines + llama-cpp native adapter only
 *   (peer engines stub/UNKNOWN; see [EngineSelectionPolicy])
 * - AIDL ClientRegistration / stream sessions / AssetHandle broker
 *
 * Does **not** load engines in the UI process; engine packs attach only
 * after this plane reaches READY/DEGRADED via [ensureEnginePacksAttached].
 * Unknown capabilities and unattached engines fail closed (INV-018).
 * Do **not** mark engines QUALIFIED/SUPPORTED without real device evidence.
 */
class RuntimeControlPlane private constructor(
    val appContext: Context,
    val lifecycle: RuntimeLifecycleController,
    val controlPlaneDb: ControlPlaneDatabase,
    val requestRegistry: RequestRegistry,
    val commandLedger: CommandLedger,
    val commitLedger: CommitLedger,
    val sessionManager: SessionManager,
    val jobManager: JobManager,
    val policyManager: PolicyManager,
    /**
     * Durable Secret Broker + TokenService + pairing + ACL (SEC-AUTH-NET).
     * Production: Keystore-wrapped keys + SQLite HMAC verifiers (never plaintext).
     */
    val securityStack: PolicyModule.SecurityStack,
    val observability: ObservabilityFacade,
    val adminApi: AdminApiService,
    val featurePacks: FeaturePackHost,
    val modelManager: ModelManager,
    /** Shared quarantine / promote store (same instance as [modelManager]). */
    val modelStore: ModelStorePort,
    val orchestrator: Orchestrator,
    val resourceGovernor: ResourceGovernor,
    val registrations: ClientRegistrationStore,
    val streamSessions: StreamSessionRegistry,
    val assetBroker: AssetHandleBroker,
    /**
     * Variant-scoped build posture (BLD-02): debug ⇒ dev mode ON, release ⇒ OFF.
     * Wired at [attach] from BuildConfig.OMNILLM_DEV_SHIP_MODE; never mutable.
     */
    private val buildMode: com.omnillm.core.contracts.ProductBuildMode =
        com.omnillm.core.contracts.ProductBuildMode.FAIL_CLOSED,
) : ControlPlaneWriter {

    private val enginePacksRef = AtomicReference<EnginePackAttachment?>(null)

    /**
     * Shared Orchestrator inference binding (updated by [ensureEnginePacksAttached]).
     * Wave-A feature ports close over the same instance.
     */
    val engineExecute: com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
        get() = featurePacks.waveA?.engineExecute
            ?: error("Wave-A engine execute binding not attached on control plane")

    /**
     * Engine Registry for all catalog engines + optional llama-cpp native adapter
     * (null until READY/DEGRADED attach). Peers remain stub/UNKNOWN.
     */
    val enginePacks: EnginePackAttachment?
        get() = enginePacksRef.get()

    private val waveA: WaveAFeaturePacks
        get() = featurePacks.waveA
            ?: error("Wave-A Feature Packs not attached on control plane")

    // ---- Wave-A convenience accessors (smoke: each non-null) ----
    val adminFeatureApi: AdminFeatureApi get() = waveA.admin
    val autoSetupApi: AutoSetupApi get() = waveA.autoSetup
    val modelHubApi: ModelHubApi get() = waveA.modelHub
    val playgroundApi: PlaygroundApi get() = waveA.playground
    val developerServerApi: DeveloperServerApi get() = waveA.server
    val dashboardApi: DashboardApi get() = waveA.dashboard

    // ---- Wave-B convenience accessors (each pack reachable from plane) ----
    val contentReportApi: ContentReportApi get() = featurePacks.contentReportApi
    val lanApi: LanAccessApi get() = featurePacks.lanApi
    val benchmarkApi: BenchmarkApi get() = featurePacks.benchmarkApi
    val diagnosticsApi: DiagnosticsApi get() = featurePacks.diagnosticsApi
    val routingApi: RoutingApi get() = featurePacks.routingApi
    val toolsApi: ToolsApi get() = featurePacks.toolsApi

    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE

    val identity: RuntimeIdentity
        get() = lifecycle.identity

    val runtimeState: String
        get() = lifecycle.state

    fun acceptsWork(): Boolean = lifecycle.acceptsWork()

    /**
     * Start lifecycle: epoch advance → STARTING → RECOVERING → READY|DEGRADED.
     * Safe to call multiple times; subsequent calls are no-ops when not STOPPED.
     *
     * When claim/commit ledgers are SQLite-durable, recovery prefers **READY**
     * after fencing open commits to RECONCILING (REL-RECOVERY). DEGRADED only
     * when reconcile throws or returns an explicit incomplete reason.
     */
    fun ensureStarted(foregroundLegal: Boolean): LifecycleStepResult {
        if (lifecycle.state != "STOPPED" && lifecycle.state != "WAITING_FOR_USER_FOREGROUND") {
            // Re-entrant: still ensure engine packs after READY/DEGRADED.
            ensureEnginePacksAttached()
            return LifecycleStepResult.Accepted(
                from = lifecycle.state,
                event = "NOOP",
                to = lifecycle.state,
                transitionId = "noop",
                actions = emptyList(),
                identity = lifecycle.identity,
            )
        }
        if (lifecycle.state == "WAITING_FOR_USER_FOREGROUND") {
            val user = lifecycle.userStart(foregroundLegal)
            if (user is LifecycleStepResult.Rejected) return user
            val recover = lifecycle.beginRecovery()
            if (recover is LifecycleStepResult.Rejected) return recover
            return finishRecovery()
        }
        val start = lifecycle.legalStart(foregroundLegal)
        if (start is LifecycleStepResult.Rejected) return start
        val recover = lifecycle.beginRecovery()
        if (recover is LifecycleStepResult.Rejected) return recover
        return finishRecovery()
    }

    /**
     * Attach Engine Registry for all catalog engines when RUNTIME is READY or DEGRADED.
     * Loads `libomnillm_llama` for llama-cpp only when packaged; fail closed if missing.
     * Peer engines register as stub/UNKNOWN. Idempotent.
     * Never elevates qualification to SUPPORTED without evidence
     * ([EngineSelectionPolicy]).
     *
     * COR-14: this is the single synchronization point for engine-pack attach.
     * It is safe to call concurrently from transport threads (HTTP handler /
     * AIDL facades call it before probing): the double-checked
     * `enginePacksRef` guard + `synchronized(this)` make it idempotent, and
     * attach only happens once per plane (lifecycle-triggered on READY/DEGRADED).
     * The residual transport-side call-site cleanup is tracked for the
     * orchestrator (ADR-011); no further control-plane change is required.
     */
    fun ensureEnginePacksAttached(): EnginePackAttachment? {
        enginePacksRef.get()?.let { return it }
        val state = lifecycle.state
        if (state != "READY" && state != "DEGRADED") {
            Log.i(TAG, "engine pack attach deferred (state=$state)")
            return null
        }
        synchronized(this) {
            enginePacksRef.get()?.let { return it }
            val attached = EnginePackAttachment.attachAfterReady(buildMode = buildMode)
            enginePacksRef.set(attached)
            // Wire Orchestrator + feature inference ports to real engine execute path
            // (SW-ENG-06). Capability cells stay UNKNOWN/UNQUALIFIED; exploratory
            // generate only when runtime.exploratoryExecuteEnabled is true.
            val bindResult = runCatching {
                featurePacks.waveA?.engineExecute?.applyAttachment(attached)
            }.getOrNull()
            Log.i(
                TAG,
                "engine packs attached native=${attached.nativeLibraryPresent} " +
                    "llamaCpp=${attached.llamaCppAttached} " +
                    "inferenceBound=${bindResult?.bound == true} " +
                    "engines=${attached.registeredEngineIds.joinToString()} " +
                    "registrations=${attached.registry.listRegistrations().size} " +
                    "cells=${attached.registry.listCells().size} " +
                    "anyExecutable=${EngineSelectionPolicy.anyExecutableCell(attached.registry, buildMode)} " +
                    "note=${bindResult?.message ?: "wave-A missing"}",
            )
            return attached
        }
    }

    /**
     * REL-RECOVERY: mark unfinished commits RECONCILING, then READY when durable.
     * Exposed for FGS path that already entered RECOVERING.
     */
    fun finishRecovery(): LifecycleStepResult {
        val reconcile = runCatching { controlPlaneDb.reconcileUnfinishedCommits() }
            .getOrElse { t ->
                Log.e(TAG, "commit reconcile failed", t)
                return lifecycle.recoveryPartial().also {
                    Log.w(
                        TAG,
                        "RECOVERY_PARTIAL reason=reconcile_failed: ${t.message}",
                    )
                }
            }
        return applyReconcileResult(reconcile)
    }

    private fun applyReconcileResult(reconcile: CommitReconcileResult): LifecycleStepResult {
        Log.i(
            TAG,
            "recovery durable=${reconcile.durable} openBefore=${reconcile.openBefore} " +
                "markedReconciling=${reconcile.markedReconciling} reason=${reconcile.reason}",
        )
        val result = when {
            !reconcile.recoveryComplete -> {
                Log.w(TAG, "RECOVERY_PARTIAL reason=${reconcile.reason}")
                lifecycle.recoveryPartial()
            }
            else -> lifecycle.recoveryOk()
        }
        // Attach native engine packs only after READY/DEGRADED (not during RECOVERING).
        if (result is LifecycleStepResult.Accepted) {
            val to = result.to
            if (to == "READY" || to == "DEGRADED") {
                ensureEnginePacksAttached()
            }
        }
        return result
    }

    fun requestDrain(): LifecycleStepResult {
        val stop = lifecycle.stopRequested()
        if (stop is LifecycleStepResult.Rejected) return stop
        // COR-23h: drain LIVE sessions before declaring drain complete — never
        // report immediate success while sessions are still open (the previous
        // scaffold reported DRAIN_COMPLETE with zero drain work).
        val failures = drainLiveSessions(sessionManager)
        if (failures.isNotEmpty()) {
            Log.w(TAG, "drain incomplete: $failures")
            return LifecycleStepResult.Rejected(
                "drain incomplete: ${failures.size} session(s) failed to drain " +
                    "(failures=${failures.joinToString()})",
            )
        }
        return lifecycle.drainComplete()
    }

    companion object {
        private const val TAG = "OmniControlPlane"

        /**
         * COR-23h: drain live sessions via the SESSION FSM before a runtime
         * drain can be declared complete. ACTIVE → DRAIN_REQUESTED (blocks new
         * use); NEW → CLOSE (never admitted to the pool). DRAINING / CLOSING /
         * POISONED / ORPHANED / CLOSED sessions are already out of usable work
         * and do not block drain. Returns per-session failure reasons — empty
         * means every usable session was successfully drained.
         */
        fun drainLiveSessions(sessionManager: SessionManager): List<String> {
            val failures = mutableListOf<String>()
            for (rec in sessionManager.allSessions()) {
                when (rec.aggregateState) {
                    "ACTIVE" -> when (val d = sessionManager.requestDrain(rec.sessionId)) {
                        is OmniResult.Err ->
                            failures += "${rec.sessionId.value}:${d.error.code.code}"
                        is OmniResult.Ok -> Unit
                    }
                    "NEW" -> when (val c = sessionManager.closeNew(rec.sessionId)) {
                        is OmniResult.Err ->
                            failures += "${rec.sessionId.value}:${c.error.code.code}"
                        is OmniResult.Ok -> Unit
                    }
                    else -> Unit
                }
            }
            return failures
        }

        private val attached = AtomicBoolean(false)
        private val instance = AtomicReference<RuntimeControlPlane?>(null)

        /** Whether the control plane is live in this process. */
        fun isAttached(): Boolean = attached.get() && instance.get() != null

        /**
         * Process-global service locator (ARC-05 documented debt).
         *
         * Kept **only** for the FGS/service bootstrap lifecycle: the plane is
         * attached synchronously in [RuntimeForegroundService] start, which runs
         * after bind/HTTP facades are constructed (they cannot receive the plane
         * via constructor at onCreate time).
         *
         * New call sites MUST prefer constructor / method injection of the plane
         * (or a provider lambda). Binder/HTTP facade conversions are tracked in
         * ARC-05; WaveAWiring and the facades still route through this locator
         * until the plane-bootstrap refactor lands.
         */
        @Deprecated(
            message = "Prefer constructor injection of RuntimeControlPlane; locator is FGS-lifecycle only (ARC-05)",
        )
        fun require(): RuntimeControlPlane =
            instance.get()
                ?: error("RuntimeControlPlane not attached (must run in :runtime process)")

        /** See [require]. Returns null when the plane is not attached. */
        @Deprecated(
            message = "Prefer constructor injection of RuntimeControlPlane; locator is FGS-lifecycle only (ARC-05)",
        )
        fun get(): RuntimeControlPlane? = instance.get()

        /**
         * Attach control plane. **Must** run only in `:runtime` process.
         * Asserts process identity (INV-001) + single-writer role (ADR-010).
         * Role string alone is insufficient — UI/worker processes must never attach.
         */
        fun attach(context: Context): RuntimeControlPlane {
            check(ProcessIdentity.isRuntimeProcess()) {
                "RuntimeControlPlane.attach refused outside :runtime process " +
                    "(current=${ProcessIdentity.currentProcessName()}) — INV-001 / ADR-010"
            }
            SingleWriterPolicy.assertWriterAllowed(SingleWriterPolicy.WRITER_ROLE)

            instance.get()?.let { return it }

            synchronized(this) {
                instance.get()?.let { return it }

                check(ProcessIdentity.isRuntimeProcess()) {
                    "RuntimeControlPlane.attach refused outside :runtime process " +
                        "(current=${ProcessIdentity.currentProcessName()}) — INV-001 / ADR-010"
                }

                val appContext = context.applicationContext
                AndroidStorageRoots.ensureLayout(appContext)

                val lifecycle = RuntimeLifecycleController()
                val clock = { java.time.Instant.now().toString() }

                val driver = AndroidSqliteDriver(
                    schema = OmniLlmDatabase.Schema,
                    context = appContext,
                    name = StorageLayout.DATABASE_NAME,
                )
                val controlDb = ControlPlaneDatabase.open(
                    driver = driver,
                    applySchema = false,
                    clock = clock,
                )
                val ledgers = RequestRegistryModule.createWithCommits(
                    claims = controlDb.claims,
                    commits = controlDb.commits,
                    clock = clock,
                )
                val registry = ledgers.requestRegistry
                val commands = ledgers.commandLedger
                val commits = ledgers.commitLedger
                val sessionManager = SessionModule.createDurableManager(
                    ports = controlDb.sessions,
                    clock = clock,
                )
                val jobs = JobManagerModule.createDurableManager(
                    ports = controlDb.jobs,
                    clock = { System.currentTimeMillis() },
                )
                // REL-RECOVERY: fence RUNNING → RECOVERING after process restart
                // (checkpoint validation required before resume; no blind replay).
                jobs.reconcileAfterRestart()
                // Durable secrets: Keystore-wrapped vault + SQLite HMAC verifiers (SEC-AUTH-NET).
                val securityStack = ControlPlaneSecurityFactory.createSecurityStack(
                    appContext = appContext,
                    controlPlaneDb = controlDb,
                    clockMs = { System.currentTimeMillis() },
                )
                val policy = securityStack.policyManager
                // BLD-02: variant-scoped build posture — debug ⇒ development ship
                // mode ON, release ⇒ OFF (fail-closed). Value comes from the
                // per-buildType BuildConfig field (android/runtime-service
                // build.gradle.kts); override auditable via
                // -Pomnillm.developmentShipMode. Seeding the exploratory
                // product-default keeps debug/dev "works out of the box" while the
                // static ConfigurationCatalog stays fail-closed (COR-10).
                val buildMode = com.omnillm.core.contracts.ProductBuildMode(
                    developmentShipMode = BuildConfig.OMNILLM_DEV_SHIP_MODE,
                )
                policy.putSourceValue(
                    com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
                        .SETTING_EXPLORATORY_EXECUTE,
                    "product-default",
                    com.omnillm.runtime.policy.SettingValue.BoolValue(
                        buildMode.defaultExploratoryExecuteEnabled(),
                    ),
                )
                val observability = ObservabilityModule.createFacade()
                // Durable ModelManager: SQL installations/leases + FS quarantine (CORE-MODEL).
                val modelStore = ModelStoreModule.createFilesystemPort(
                    filesRoot = AndroidStorageRoots.filesRootPath(appContext),
                    monotonicNowMs = { android.os.SystemClock.elapsedRealtime() },
                )
                val modelManager = ModelManagerModule.createDurableControlPlane(
                    installationPorts = controlDb.installations,
                    leasePorts = controlDb.revisionLeases,
                    modelStore = modelStore,
                    privilegedReverify = DefaultPrivilegedLoadReverify.failClosedUntilSupplyWired(
                        modelStore,
                    ),
                    clock = clock,
                )
                val assetQuarantine = File(appContext.cacheDir, "omnillm-asset-quarantine")
                val registrations = ClientRegistrationStore()
                val streamSessions = StreamSessionRegistry()
                val planeRef = AtomicReference<RuntimeControlPlane?>(null)

                // Single Admin service; lan label reads policy (same as ControlPlaneLanHost).
                val admin = AdminModule.createService(
                    commandLedger = commands,
                    jobManager = jobs,
                    policyManager = policy,
                    runtimeStateProvider = { lifecycle.state },
                    lanStateProvider = {
                        val enabled = policy.settingsSnapshot().values["server.lanEnabled"]
                        if (enabled is com.omnillm.runtime.policy.SettingValue.BoolValue &&
                            enabled.value
                        ) {
                            "ENABLED"
                        } else {
                            "DISABLED"
                        }
                    },
                )
                // Shared engine execute binding — updated by ensureEnginePacksAttached.
                // Real installed GGUF resolution for llama-cpp (privileged load,
                // INV-010): READY installation → content re-verify → in-process path.
                val ggufModelSourceResolver =
                    com.omnillm.android.runtimeservice.featurehost.RuntimeGgufModelSourceResolver(
                        filesRoot = AndroidStorageRoots.filesRootPath(appContext),
                        installations = com.omnillm.runtime.modelmanager.durable
                            .SqlInstallationRepository(
                                ports = controlDb.installations,
                                clock = clock,
                            ),
                        readyContent = modelStore,
                    )
                val engineExecuteBinding =
                    com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding(
                        buildMode = buildMode,
                        exploratoryEnabled = {
                            com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
                                .readExploratoryEnabled(policy.settingsSnapshot().values)
                        },
                        modelSourceResolver = ggufModelSourceResolver,
                        fallbackToFixtureOnUnresolved =
                            buildMode.allowExecuteWithoutQualification(),
                    )
                val waveA = WaveAWiring.wire(
                    WaveAWiring.Deps(
                        adminApi = admin,
                        jobManager = jobs,
                        modelManager = modelManager,
                        requestRegistry = registry,
                        observability = observability,
                        runtimeState = { lifecycle.state },
                        lanState = {
                            val enabled = policy.settingsSnapshot().values["server.lanEnabled"]
                            if (enabled is com.omnillm.runtime.policy.SettingValue.BoolValue &&
                                enabled.value
                            ) {
                                "ENABLED"
                            } else {
                                "DISABLED"
                            }
                        },
                        runtimeEpoch = { lifecycle.identity.runtimeEpoch },
                        bootId = { lifecycle.identity.bootId },
                        appContext = appContext,
                        registrations = registrations,
                        tokenService = { GatewayLifecycle.tokenService() },
                        ensureGatewayStarted = {
                            planeRef.get()?.let { GatewayLifecycle.ensureStarted(it) }
                        },
                        isGatewayRunning = { GatewayLifecycle.isRunning() },
                        engineExecute = engineExecuteBinding,
                        // ARC-10: governor capacities follow the effective
                        // configuration catalog (defaults match historical values).
                        settings = { policy.settingsSnapshot() },
                        // ARC-05: inject the plane's own registry instead of the
                        // process-global locator.
                        streamSessions = { streamSessions },
                        // C-01: orchestrator INTENT must be durable — the same
                        // SQLite-backed CommitLedger already wired to the registry.
                        commitLedger = commits,
                    ),
                )

                // Wave-B + Wave-A Feature Packs (ADR-010 / INV-001).
                // Content-report drafts/queue/receipts + tool proposals are SQLite-durable.
                // LAN: Secret Broker TLS identity + durable pairing/tokens; network bind on enable.
                val lanTlsEndpoint = ControlPlaneLanTlsEndpoint(
                    secretBroker = securityStack.secretBroker,
                    handlerProvider = {
                        planeRef.get()?.let { GatewayLifecycle.controlPlaneHandler(it) }
                    },
                    authenticatorProvider = { GatewayLifecycle.tokenService() },
                    clockMs = { System.currentTimeMillis() },
                )
                val featurePacks = FeaturePackHost.bootstrap(
                    jobManager = jobs,
                    policyManager = policy,
                    observability = observability,
                    clockMs = { System.currentTimeMillis() },
                    contentReportLedger = controlDb.contentReports,
                    toolProposalLedger = controlDb.toolProposals,
                    waveA = waveA,
                    securityStack = securityStack,
                    lanTlsEndpoint = lanTlsEndpoint,
                    lanBindNetwork = true,
                )
                val assetBroker = AssetHandleBroker(
                    commandLedger = commands,
                    quarantineDir = assetQuarantine,
                )

                val plane = RuntimeControlPlane(
                    appContext = appContext,
                    lifecycle = lifecycle,
                    controlPlaneDb = controlDb,
                    requestRegistry = registry,
                    commandLedger = commands,
                    commitLedger = commits,
                    sessionManager = sessionManager,
                    jobManager = jobs,
                    policyManager = policy,
                    securityStack = securityStack,
                    observability = observability,
                    adminApi = admin,
                    featurePacks = featurePacks,
                    modelManager = modelManager,
                    modelStore = modelStore,
                    orchestrator = waveA.orchestrator,
                    resourceGovernor = waveA.resourceGovernor,
                    registrations = registrations,
                    streamSessions = streamSessions,
                    assetBroker = assetBroker,
                    buildMode = buildMode,
                )
                planeRef.set(plane)
                instance.set(plane)
                attached.set(true)
                Log.i(
                    TAG,
                    "Control plane attached pid=${android.os.Process.myPid()} " +
                        "role=${SingleWriterPolicy.WRITER_ROLE} admin=LOCAL_UI " +
                        "db=${StorageLayout.DATABASE_NAME} durable=true " +
                        "sessions=${sessionManager.allSessions().size} " +
                        "featurePacks=${featurePacks.featureIds.joinToString(",")} " +
                        "orchestrator=delegating-engine engines=after-READY " +
                        "exploratorySetting=${com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE}",
                )
                return plane
            }
        }

        /**
         * Test-only / process death cleanup. Production code should not detach
         * while services are bound.
         */
        fun detachForTest() {
            synchronized(this) {
                instance.get()?.adminApi?.jobObservers?.closeAll()
                instance.get()?.controlPlaneDb?.close()
                instance.get()?.enginePacksRef?.set(null)
                instance.set(null)
                attached.set(false)
            }
        }
    }
}
