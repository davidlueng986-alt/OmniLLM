package com.omnillm.ui.admin

import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniBenchmarkJobParameters
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniJobSpec
import android.os.RemoteException
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.api.BenchmarkJobHandle
import com.omnillm.features.benchmark.api.BenchmarkPlan
import com.omnillm.features.benchmark.api.BenchmarkSnapshot
import com.omnillm.features.benchmark.api.CancelBenchmarkSpec
import com.omnillm.features.benchmark.api.ExportReportSpec
import com.omnillm.features.benchmark.api.MeasurementReport
import com.omnillm.features.benchmark.api.PlanBenchmarkSpec
import com.omnillm.features.benchmark.api.ProfileComparisonView
import com.omnillm.features.benchmark.api.StartBenchmarkSpec
import com.omnillm.features.benchmark.domain.BenchmarkScenarioTemplate
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.projection.ProfileComparison
import com.omnillm.features.dashboard.api.ActionableItemUi
import com.omnillm.features.dashboard.api.CancelRequestResult
import com.omnillm.features.dashboard.api.CapabilityCellUi
import com.omnillm.features.dashboard.api.CapabilityNegotiationResult
import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.dashboard.api.DashboardSnapshot
import com.omnillm.features.dashboard.api.PerformanceStripUi
import com.omnillm.features.dashboard.api.RequestRowUi
import com.omnillm.features.dashboard.api.RuntimeHealthUi
import com.omnillm.features.dashboard.api.TraceSummaryUi
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.features.playground.ports.FailClosedPlaygroundStructuredPort
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.NoOpPlaygroundMetricsPort
import com.omnillm.features.playground.ports.PlaygroundCapabilityPort
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.ports.PlaygroundInferencePort
import com.omnillm.features.playground.ports.PlaygroundModelCatalogPort
import com.omnillm.features.playground.ports.PlaygroundModelRow
import com.omnillm.features.playground.ports.PlaygroundRuntimeStatusPort
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.ports.CapabilityQueryPort
import com.omnillm.features.server.ports.ClientAdminPort
import com.omnillm.features.server.ports.LoopbackServerPort
import com.omnillm.features.server.ports.ServerInferencePort
import com.omnillm.features.server.ports.ServerMetricsPort
import com.omnillm.features.server.ports.ServerRuntimePorts
import com.omnillm.features.server.ports.TokenAdminPort
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.HealthLevel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Admin-binder projections for feature ViewModels (INV-001).
 *
 * UI never opens domain DB or loads native engines. Mutations go through
 * [IOmniAdmin] only; capability-unknown paths fail closed (INV-018).
 * Engine cells stay UNQUALIFIED / UNKNOWN — never invent SUPPORTED evidence.
 */
object AdminFeatureProjections {

    fun playgroundPorts(admin: IOmniAdmin): PlaygroundFeaturePorts =
        PlaygroundFeaturePorts(
            // LOCAL_UI → IOmniAdmin → control-plane playground/orchestrator (INV-001).
            inference = AdminPlaygroundInferencePort(admin),
            capabilities = AdminPlaygroundCapabilityPort(admin),
            models = AdminPlaygroundModelCatalog(admin),
            metrics = NoOpPlaygroundMetricsPort,
            runtimeStatus = AdminPlaygroundRuntimeStatus(admin),
            // Structured tools remain fail-closed on Admin until dedicated AIDL surface;
            // plane ToolsApi is reachable via HTTP / playground STRUCTURED when attached.
            structured = FailClosedPlaygroundStructuredPort,
        )

    fun dashboardApi(admin: IOmniAdmin): DashboardApi =
        AdminProjectedDashboardApi(admin)

    fun benchmarkApi(admin: IOmniAdmin): BenchmarkApi =
        AdminProjectedBenchmarkApi(admin)

    fun serverPorts(admin: IOmniAdmin): ServerRuntimePorts =
        ServerRuntimePorts(
            loopback = AdminLoopbackPort(admin),
            tokens = EmptyTokenAdminPort,
            clients = EmptyClientAdminPort,
            capabilities = AdminCapabilityQueryPort(admin),
            inference = AdminServerInferencePort(admin),
            metrics = EmptyServerMetricsPort,
        )

    fun lanPorts(admin: IOmniAdmin): LanRuntimePorts =
        LanRuntimePorts(
            service = AdminLanServicePort(admin),
            pairing = FailClosedLanPairingPort,
            clients = EmptyLanClientPort,
        )
}

// ── Playground ──────────────────────────────────────────────────────────────

private class AdminPlaygroundModelCatalog(
    private val admin: IOmniAdmin,
) : PlaygroundModelCatalogPort {
    override fun listModels(): List<PlaygroundModelRow> =
        try {
            admin.snapshot.models.orEmpty().mapNotNull { m ->
                if (m == null || m.modelRevisionId.isNullOrBlank()) return@mapNotNull null
                PlaygroundModelRow(
                    modelRevisionId = m.modelRevisionId,
                    displayName = m.displayName.orEmpty()
                        .ifBlank { m.modelRevisionId.take(12) },
                    installationState = m.installationState,
                )
            }
        } catch (_: RemoteException) {
            emptyList()
        }
}

/**
 * Honest capability matrix projection via control-plane [IOmniAdmin.getInferenceCapabilityState].
 * Never invents SUPPORTED; may surface CONDITIONAL under exploratory policy (INV-018 / ENG-Q).
 */
private class AdminPlaygroundCapabilityPort(
    private val admin: IOmniAdmin,
) : PlaygroundCapabilityPort {
    override fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState =
        try {
            val raw = admin.getInferenceCapabilityState(capability.id, modelRevisionId)
            when (raw?.uppercase()) {
                "SUPPORTED" -> CapabilityState.SUPPORTED
                "CONDITIONAL" -> CapabilityState.CONDITIONAL
                "UNSUPPORTED" -> CapabilityState.UNSUPPORTED
                "TEMPORARILY_UNAVAILABLE" -> CapabilityState.TEMPORARILY_UNAVAILABLE
                else -> CapabilityState.UNKNOWN
            }
        } catch (_: RemoteException) {
            CapabilityState.UNKNOWN
        }

    override fun conditions(capability: CapabilityId, modelRevisionId: String): List<String> {
        val s = state(capability, modelRevisionId)
        return when (s) {
            CapabilityState.CONDITIONAL -> listOf(
                "exploratory_execute_enabled",
                "engine_unqualified",
                "no_device_evidence_pack",
            )
            CapabilityState.UNKNOWN -> listOf(
                "no device qualification evidence; exploratory execute not elevated to SUPPORTED",
            )
            else -> emptyList()
        }
    }
}

private class AdminPlaygroundRuntimeStatus(
    private val admin: IOmniAdmin,
) : PlaygroundRuntimeStatusPort {
    override fun runtimeState(): String =
        try {
            admin.snapshot.runtimeState.orEmpty().ifBlank { "UNKNOWN" }
        } catch (_: RemoteException) {
            "UNKNOWN"
        }

    override fun degradedReasons(): List<String> =
        try {
            val state = admin.snapshot.runtimeState.orEmpty()
            if (state.equals("DEGRADED", ignoreCase = true)) {
                listOf("runtime_degraded")
            } else {
                emptyList()
            }
        } catch (_: RemoteException) {
            listOf("admin_binder_unavailable")
        }
}

/**
 * Playground inference over non-exported [IOmniAdmin] (INV-001 — no native/DB in UI).
 * Proxies to control-plane playground + Orchestrator exploratory path.
 */
private class AdminPlaygroundInferencePort(
    private val admin: IOmniAdmin,
) : PlaygroundInferencePort {

    override suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<InferenceHandle> {
        requireLocalUi(principal)
        val userText = spec.messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
            ?: spec.messages.lastOrNull()?.content
            ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "chat messages empty"))
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = java.util.UUID.randomUUID().toString()
                idempotencyKey = "pg-chat-cmd-${spec.identity.idempotencyKey}"
                canonicalInputDigest = spec.identity.canonicalInputDigest
                hasExpectedVersion = false
                expectedVersion = 0L
            }
            val result = admin.executePlaygroundChat(
                spec.modelRevisionId,
                userText,
                spec.identity.requestId,
                spec.identity.idempotencyKey,
                cmd,
            )
            parseInferenceHandleResult(result)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "playground chat remote failure"))
        }
    }

    override suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<InferenceHandle> =
        OmniResult.err(
            OmniError.CAPABILITY_UNKNOWN(
                message = "embedding remains UNKNOWN/unqualified (fail closed)",
                details = mapOf("op" to "embed"),
            ),
        )

    override suspend fun cancel(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<com.omnillm.features.playground.ports.CancelPortResult> {
        requireLocalUi(principal)
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = spec.commandId
                idempotencyKey = spec.idempotencyKey
                canonicalInputDigest = spec.canonicalInputDigest
                hasExpectedVersion = false
                expectedVersion = 0L
            }
            val result = admin.cancelPlaygroundRequest(spec.requestId, cmd)
            if (result.error != null) {
                return OmniResult.err(aidlErrorToDomain(result.error!!))
            }
            val json = result.resultCanonicalJson.orEmpty()
            val phaseName = jsonField(json, "phase") ?: "REQUESTED"
            val phase = try {
                com.omnillm.features.playground.projection.CancelPhase.valueOf(phaseName)
            } catch (_: Exception) {
                com.omnillm.features.playground.projection.CancelPhase.REQUESTED
            }
            OmniResult.ok(
                com.omnillm.features.playground.ports.CancelPortResult(
                    requestId = spec.requestId,
                    phase = phase,
                    requestState = jsonField(json, "requestState"),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "playground cancel remote failure"))
        }
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<InferenceHandle> {
        requireLocalUi(principal)
        return try {
            parseInferenceHandleResult(admin.queryPlaygroundRequest(requestId))
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "playground query remote failure"))
        }
    }
}

private fun parseInferenceHandleResult(result: ai.omnillm.api.CommandResult): OmniResult<InferenceHandle> {
    if (result.error != null) {
        return OmniResult.err(aidlErrorToDomain(result.error!!))
    }
    val json = result.resultCanonicalJson.orEmpty()
    val requestId = jsonField(json, "requestId") ?: result.affectedResourceId.orEmpty()
    if (requestId.isBlank()) {
        return OmniResult.err(OmniError.INTERNAL(message = "missing requestId in playground result"))
    }
    val errCode = jsonField(json, "errorCode")
    val errMsg = jsonField(json, "errorMessage")
    val error = if (errCode != null) {
        val code = com.omnillm.core.errors.generated.OmniErrorCode.fromCode(errCode)
            ?: com.omnillm.core.errors.generated.OmniErrorCode.INTERNAL
        OmniError.of(code, errMsg ?: errCode)
    } else {
        null
    }
    val cancelPhase = jsonField(json, "cancelPhase")?.let {
        try {
            com.omnillm.features.playground.projection.CancelPhase.valueOf(it)
        } catch (_: Exception) {
            null
        }
    }
    return OmniResult.ok(
        InferenceHandle(
            requestId = requestId,
            operationKind = jsonField(json, "operationKind") ?: "CHAT",
            state = jsonField(json, "state") ?: "UNKNOWN",
            cancelPhase = cancelPhase,
            actualModelRevisionId = jsonField(json, "actualModelRevisionId"),
            engineBuildId = jsonField(json, "engineBuildId"),
            backend = jsonField(json, "backend"),
            assistantText = jsonField(json, "assistantText"),
            error = error,
            degraded = jsonField(json, "degraded") == "true",
            degradedReasons = if (jsonField(json, "degraded") == "true") {
                listOf("CONDITIONAL exploratory execute", "engine cells UNQUALIFIED")
            } else {
                emptyList()
            },
        ),
    )
}

private fun aidlErrorToDomain(err: ai.omnillm.api.OmniError): OmniError {
    val code = com.omnillm.core.errors.generated.OmniErrorCode.fromCode(err.code.orEmpty())
        ?: com.omnillm.core.errors.generated.OmniErrorCode.INTERNAL
    val details = err.details.orEmpty().mapNotNull { d ->
        val k = d?.key ?: return@mapNotNull null
        val v = d.stringValue ?: return@mapNotNull null
        k to v
    }.toMap()
    return OmniError.of(code, err.message ?: code.code, details)
}

/** Minimal JSON string field extractor (result envelopes are flat string maps). */
private fun jsonField(json: String, key: String): String? {
    if (json.isBlank()) return null
    val quoted = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
    quoted.find(json)?.groupValues?.getOrNull(1)?.let { raw ->
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }
    val bare = Regex(""""$key"\s*:\s*(null|true|false|-?\d+(?:\.\d+)?)""")
    bare.find(json)?.groupValues?.getOrNull(1)?.let { v ->
        return if (v == "null") null else v
    }
    return null
}

// ── Dashboard ───────────────────────────────────────────────────────────────

private class AdminProjectedDashboardApi(
    private val admin: IOmniAdmin,
) : DashboardApi {

    private val seq = AtomicLong(0L)

    override fun getSnapshot(principal: PrincipalId): OmniResult<DashboardSnapshot> {
        requireLocalUi(principal)
        return try {
            val snap = admin.snapshot
            val now = System.currentTimeMillis()
            val runtime = snap.runtimeState.orEmpty().ifBlank { "UNKNOWN" }
            val level = when (runtime.uppercase()) {
                "READY", "RUNNING", "ACTIVE" -> HealthLevel.HEALTHY
                "DEGRADED" -> HealthLevel.DEGRADED
                "FAULTED", "ERROR", "STOPPED" -> HealthLevel.UNAVAILABLE
                else -> HealthLevel.UNKNOWN
            }
            val jobs = snap.activeJobs.orEmpty().mapNotNull { j ->
                if (j == null || j.jobId.isNullOrBlank()) return@mapNotNull null
                RequestRowUi(
                    requestId = j.jobId,
                    correlationId = null,
                    principalId = LocalUiPrincipal.ID.value,
                    phase = j.state.orEmpty().ifBlank { "UNKNOWN" },
                    // OmniJobInfo has no kind field on Admin snapshot — generic label.
                    labelKey = "dashboard.job",
                    severity = com.omnillm.features.dashboard.api.DashboardSeverity.INFO,
                    engineBuildId = null,
                    modelRevisionId = null,
                    cancelAllowed = j.state in setOf("QUEUED", "RUNNING", "PAUSED"),
                )
            }
            val isEmpty = jobs.isEmpty() &&
                snap.models.isNullOrEmpty() &&
                runtime.equals("UNKNOWN", ignoreCase = true)
            OmniResult.ok(
                DashboardSnapshot(
                    snapshotVersion = seq.incrementAndGet(),
                    sampledAtEpochMs = now,
                    health = RuntimeHealthUi(
                        runtimeState = runtime,
                        resourceVersion = snap.snapshotVersion,
                        overallLevel = level,
                        degradedReasons = if (level == HealthLevel.DEGRADED) {
                            listOf("runtime_degraded")
                        } else {
                            emptyList()
                        },
                        subjects = emptyList(),
                        sampledAtEpochMs = now,
                    ),
                    resources = null,
                    performance = PerformanceStripUi(
                        ttftMs = null,
                        tokensPerSecond = null,
                        queueMs = null,
                        errorCount = null,
                        thermalStatus = null,
                        operationalOnly = true,
                    ),
                    requests = jobs,
                    traces = emptyList(),
                    actions = if (snap.models.isNullOrEmpty()) {
                        listOf(
                            ActionableItemUi(
                                reasonCode = "NO_MODELS",
                                labelKey = "dashboard.action.open_modelhub",
                                severity = com.omnillm.features.dashboard.api.DashboardSeverity.WARNING,
                                recommendedActionKeys = listOf("open_modelhub"),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    metrics = emptyList(),
                    isOperationallyEmpty = isEmpty,
                    lastMeasurementRun = null,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "dashboard snapshot remote failure"))
        }
    }

    override fun getTrace(principal: PrincipalId, correlationId: String): OmniResult<TraceSummaryUi> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "trace detail requires observability facade"))

    override fun cancelRequest(principal: PrincipalId, requestId: RequestId): OmniResult<CancelRequestResult> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "request cancel via Admin job cancel only"))

    override fun queryRequest(principal: PrincipalId, requestId: RequestId): OmniResult<CancelRequestResult> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "request query requires runtime binder"))

    override fun negotiate(required: List<CapabilityId>): CapabilityNegotiationResult {
        // Fail closed: Admin projection does not elevate UNKNOWN capabilities.
        val cells = required.map { cap ->
            CapabilityCellUi(
                capabilityId = cap.name,
                state = CapabilityState.UNKNOWN,
                conditions = listOf("admin_projection_no_device_evidence"),
            )
        }
        return CapabilityNegotiationResult(
            allSupported = false,
            cells = cells,
        )
    }
}

// ── Benchmark ───────────────────────────────────────────────────────────────

/**
 * BenchmarkApi over Admin jobs + pure local plan (ADR-002).
 * Start mutates only via Admin.startJob(BENCHMARK); no domain DB from UI.
 */
class AdminProjectedBenchmarkApi(
    private val admin: IOmniAdmin,
) : BenchmarkApi {

    private val snapshotSeq = AtomicLong(0L)
    private val lastPlan = AtomicReference<BenchmarkPlan?>(null)
    private val lastError = AtomicReference<OmniError?>(null)
    private val lastJobId = AtomicReference<String?>(null)
    private val lastRunId = AtomicReference<String?>(null)
    private val profiles = linkedMapOf<String, MeasurementProfile>()
    private val lock = Any()

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<BenchmarkSnapshot> {
        requireLocalUi(principal)
        return try {
            val snap = admin.snapshot
            val trackedId = lastJobId.get()
            val active = snap.activeJobs.orEmpty().firstOrNull { j ->
                j != null && !j.jobId.isNullOrBlank() &&
                    (trackedId == null || j.jobId == trackedId)
            } ?: if (trackedId != null) {
                try {
                    admin.getJob(trackedId)
                } catch (_: RemoteException) {
                    null
                }
            } else {
                null
            }
            val handle = active?.takeIf { !it.jobId.isNullOrBlank() }?.let {
                BenchmarkJobHandle(
                    jobId = it.jobId.orEmpty(),
                    runId = lastRunId.get(),
                    kind = "BENCHMARK",
                    state = it.state.orEmpty().ifBlank { "UNKNOWN" },
                    resourceVersion = it.resourceVersion,
                    createdNew = false,
                )
            }
            val plan = lastPlan.get()
            val uiPhase = when {
                lastError.get() != null && plan == null && handle == null -> BenchmarkUiPhases.ERROR
                handle != null && handle.state in setOf("QUEUED", "RUNNING") -> BenchmarkUiPhases.LOADING
                plan != null -> BenchmarkUiPhases.PREVIEW
                handle != null -> BenchmarkUiPhases.CONTENT
                else -> BenchmarkUiPhases.EMPTY
            }
            OmniResult.ok(
                BenchmarkSnapshot(
                    snapshotVersion = snapshotSeq.incrementAndGet(),
                    uiPhase = uiPhase,
                    plan = plan,
                    activeJob = handle,
                    profiles = synchronized(lock) { profiles.values.toList() },
                    runs = emptyList(),
                    currentRunByProfile = emptyMap(),
                    templates = listOf(
                        BenchmarkScenarioTemplate.QUICK_SMOKE,
                        BenchmarkScenarioTemplate.THROUGHPUT,
                        BenchmarkScenarioTemplate.LATENCY,
                    ),
                    lastReport = null,
                    lastError = lastError.get(),
                    degradedReasons = emptyList(),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "benchmark snapshot remote failure"))
        }
    }

    override fun planBenchmark(
        principal: PrincipalId,
        spec: PlanBenchmarkSpec,
    ): OmniResult<BenchmarkPlan> {
        requireLocalUi(principal)
        // Pure plan — no domain mutation (ADR-002).
        val profile = spec.profile
        val profileId = profile.profileId()
        val plan = BenchmarkPlan(
            profile = profile,
            profileId = profileId,
            canonicalProfileJson = profile.toCanonicalJson(),
            templateId = spec.templateId,
            iterations = spec.iterations,
            fallbackPolicy = spec.fallbackPolicy,
            estimatedSampleCount = profile.sampleCount,
            estimatedWarmupCount = profile.warmupCount,
            dimensions = ProfileComparison.expandDimensions(profile),
        )
        synchronized(lock) {
            profiles[profileId] = profile
        }
        lastPlan.set(plan)
        lastError.set(null)
        return OmniResult.ok(plan)
    }

    override suspend fun startBenchmark(
        principal: PrincipalId,
        spec: StartBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)
        return try {
            val aidl = OmniJobSpec()
            aidl.jobId = spec.jobId
            aidl.kind = "BENCHMARK"
            aidl.command = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            aidl.benchmark = OmniBenchmarkJobParameters().apply {
                modelRevisionId = spec.profile.modelRevisionId
                engineBuildId = spec.profile.engineBuildId
                backend = spec.profile.backend
                measurementProfileId = spec.profile.profileId()
                iterations = spec.iterations ?: spec.profile.sampleCount
            }
            val info = admin.startJob(aidl)
            val jobId = info.jobId.orEmpty().ifBlank { spec.jobId }
            lastJobId.set(jobId)
            lastRunId.set(spec.runId)
            lastError.set(null)
            OmniResult.ok(
                BenchmarkJobHandle(
                    jobId = jobId,
                    runId = spec.runId,
                    kind = "BENCHMARK",
                    state = info.state.orEmpty().ifBlank { "QUEUED" },
                    resourceVersion = info.resourceVersion,
                    createdNew = true,
                ),
            )
        } catch (e: RemoteException) {
            val err = OmniError.INTERNAL(message = e.message ?: "startBenchmark remote failure")
            lastError.set(err)
            OmniResult.err(err)
        }
    }

    override suspend fun cancelBenchmark(
        principal: PrincipalId,
        spec: CancelBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            admin.cancelJob(spec.jobId, cmd)
            val info = admin.getJob(spec.jobId)
            OmniResult.ok(
                BenchmarkJobHandle(
                    jobId = info.jobId.orEmpty().ifBlank { spec.jobId },
                    runId = null,
                    kind = "BENCHMARK",
                    state = info.state.orEmpty().ifBlank { "CANCELLED" },
                    resourceVersion = info.resourceVersion,
                    createdNew = false,
                    cancelRequested = true,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "cancelBenchmark remote failure"))
        }
    }

    override suspend fun queryBenchmarkJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)
        return try {
            val info = admin.getJob(jobId)
            OmniResult.ok(
                BenchmarkJobHandle(
                    jobId = info.jobId.orEmpty().ifBlank { jobId },
                    runId = null,
                    kind = "BENCHMARK",
                    state = info.state.orEmpty(),
                    resourceVersion = info.resourceVersion,
                    createdNew = false,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "queryBenchmark remote failure"))
        }
    }

    override suspend fun listRuns(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<List<MeasurementRun>> {
        requireLocalUi(principal)
        return OmniResult.ok(emptyList())
    }

    override suspend fun currentRun(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<MeasurementRun?> {
        requireLocalUi(principal)
        return OmniResult.ok(null)
    }

    override fun compareProfiles(
        principal: PrincipalId,
        leftProfileId: String,
        rightProfileId: String,
    ): OmniResult<ProfileComparisonView> {
        requireLocalUi(principal)
        val left = synchronized(lock) { profiles[leftProfileId] }
        val right = synchronized(lock) { profiles[rightProfileId] }
        if (left == null || right == null) {
            return OmniResult.err(OmniError.NOT_FOUND(message = "profile not in local plan cache"))
        }
        return OmniResult.ok(ProfileComparison.compare(left, right))
    }

    override suspend fun completeRun(
        jobId: String,
        metrics: MeasurementMetrics,
        forceInvalid: Boolean,
    ): OmniResult<MeasurementRun> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "completeRun is control-plane only (ADR-010)",
            ),
        )

    override suspend fun exportReport(
        principal: PrincipalId,
        spec: ExportReportSpec,
    ): OmniResult<MeasurementReport> {
        requireLocalUi(principal)
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "measurement report export requires control-plane ObservabilityFacade",
            ),
        )
    }

    override fun getProfile(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<MeasurementProfile> {
        requireLocalUi(principal)
        val p = synchronized(lock) { profiles[profileId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "profile not found",
                    details = mapOf("profileId" to profileId),
                ),
            )
        return OmniResult.ok(p)
    }
}

// ── Server ──────────────────────────────────────────────────────────────────

private class AdminLoopbackPort(
    private val admin: IOmniAdmin,
) : LoopbackServerPort {
    override suspend fun status(): OmniResult<LoopbackServerStatus> =
        try {
            val snap = admin.snapshot
            val runtime = snap.runtimeState.orEmpty()
            // Admin snapshot does not expose loopback bind detail — project unknown honestly.
            OmniResult.ok(
                LoopbackServerStatus(
                    enabled = false,
                    running = false,
                    host = "127.0.0.1",
                    port = 11434,
                    runtimeState = runtime.ifBlank { "UNKNOWN" },
                    resourceVersion = snap.snapshotVersion,
                    degradedReasons = listOf("loopback_status_not_projected_via_admin"),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "loopback status remote failure"))
        }

    override suspend fun ensureStarted(principal: PrincipalId): OmniResult<LoopbackServerStatus> {
        requireLocalUi(principal)
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "loopback ensure requires control-plane gateway lifecycle",
            ),
        )
    }
}

private object EmptyTokenAdminPort : TokenAdminPort {
    override suspend fun listTokens(principal: PrincipalId): OmniResult<List<DeveloperTokenView>> =
        OmniResult.ok(emptyList())

    override suspend fun issue(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        expiresInSeconds: Long,
        label: String?,
        command: ServerCommandIdentity,
    ): OmniResult<TokenIssuanceReceipt> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "token issue requires control-plane TokenService"))

    override suspend fun revoke(
        principal: PrincipalId,
        tokenId: String,
        command: ServerCommandIdentity,
    ): OmniResult<DeveloperTokenView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "token revoke requires control-plane TokenService"))
}

private object EmptyClientAdminPort : ClientAdminPort {
    override suspend fun listClients(principal: PrincipalId): OmniResult<List<ClientSummaryView>> =
        OmniResult.ok(emptyList())

    override suspend fun createClient(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "client create requires control-plane policy"))

    override suspend fun revoke(
        principal: PrincipalId,
        clientId: String,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "client revoke requires control-plane policy"))
}

private class AdminCapabilityQueryPort(
    private val admin: IOmniAdmin,
) : CapabilityQueryPort {
    override suspend fun listModels(principal: PrincipalId): OmniResult<List<ModelCapabilityView>> {
        requireLocalUi(principal)
        return try {
            OmniResult.ok(
                admin.snapshot.models.orEmpty().mapNotNull { m ->
                    if (m == null || m.modelRevisionId.isNullOrBlank()) return@mapNotNull null
                    ModelCapabilityView(
                        modelId = m.modelRevisionId,
                        modelRevisionId = m.modelRevisionId,
                        displayName = m.displayName.orEmpty().ifBlank { m.modelRevisionId.take(12) },
                        engineBuildId = null,
                        backend = null,
                        trustClass = null,
                        // Unknown cells not invented — empty list is honest until negotiated.
                        capabilities = emptyList(),
                    )
                },
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "listModels remote failure"))
        }
    }
}

/**
 * Server smoke over [IOmniAdmin.executeServerSmoke] → plane DeveloperServerApi.
 */
private class AdminServerInferencePort(
    private val admin: IOmniAdmin,
) : ServerInferencePort {
    override suspend fun submitSmoke(
        principal: PrincipalId,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult> {
        requireLocalUi(principal)
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = java.util.UUID.randomUUID().toString()
                idempotencyKey = "smoke-cmd-${claim.idempotencyKey}"
                canonicalInputDigest = claim.canonicalRequestDigestHex
                hasExpectedVersion = false
                expectedVersion = 0L
            }
            val result = admin.executeServerSmoke(
                claim.modelRevisionIdHex,
                claim.requestId,
                claim.idempotencyKey,
                cmd,
            )
            if (result.error != null) {
                return OmniResult.err(aidlErrorToDomain(result.error!!))
            }
            val json = result.resultCanonicalJson.orEmpty()
            val errCode = jsonField(json, "errorCode")
            val errMsg = jsonField(json, "errorMessage")
            val error = if (errCode != null) {
                val code = com.omnillm.core.errors.generated.OmniErrorCode.fromCode(errCode)
                    ?: com.omnillm.core.errors.generated.OmniErrorCode.INTERNAL
                OmniError.of(code, errMsg ?: errCode)
            } else {
                null
            }
            OmniResult.ok(
                SmokeTestResult(
                    step = jsonField(json, "step") ?: "EXECUTE",
                    success = jsonField(json, "success") == "true",
                    requestId = jsonField(json, "requestId") ?: claim.requestId,
                    requestState = jsonField(json, "requestState"),
                    error = error,
                    completedAtEpochMs = System.currentTimeMillis(),
                    actualModelRevisionId = jsonField(json, "modelRevisionId"),
                    actualEngineBuildId = jsonField(json, "engineBuildId"),
                    actualBackend = jsonField(json, "backend"),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "server smoke remote failure"))
        }
    }

    override suspend fun cancel(
        principal: PrincipalId,
        requestId: RequestId,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult> {
        // Reuse playground cancel path for request lifecycle (same control plane registry).
        requireLocalUi(principal)
        return try {
            val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                "server-cancel|${requestId.value}|${command.commandId}|${command.idempotencyKey}",
            )
            val cmd = OmniCommandRequest().apply {
                commandId = command.commandId
                idempotencyKey = command.idempotencyKey
                canonicalInputDigest = digest
                hasExpectedVersion = false
                expectedVersion = 0L
            }
            val result = admin.cancelPlaygroundRequest(requestId.value, cmd)
            if (result.error != null) {
                return OmniResult.ok(
                    SmokeTestResult(
                        step = "CANCEL",
                        success = false,
                        requestId = requestId.value,
                        requestState = null,
                        error = aidlErrorToDomain(result.error!!),
                        completedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            OmniResult.ok(
                SmokeTestResult(
                    step = "CANCEL",
                    success = true,
                    requestId = requestId.value,
                    requestState = jsonField(result.resultCanonicalJson.orEmpty(), "requestState")
                        ?: "CANCELLED",
                    error = null,
                    completedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "server cancel remote failure"))
        }
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<SmokeTestResult> {
        requireLocalUi(principal)
        return try {
            val result = admin.queryPlaygroundRequest(requestId.value)
            if (result.error != null) {
                return OmniResult.ok(
                    SmokeTestResult(
                        step = "QUERY",
                        success = false,
                        requestId = requestId.value,
                        requestState = null,
                        error = aidlErrorToDomain(result.error!!),
                        completedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            val json = result.resultCanonicalJson.orEmpty()
            OmniResult.ok(
                SmokeTestResult(
                    step = "QUERY",
                    success = true,
                    requestId = requestId.value,
                    requestState = jsonField(json, "state"),
                    error = null,
                    completedAtEpochMs = System.currentTimeMillis(),
                    actualModelRevisionId = jsonField(json, "actualModelRevisionId"),
                    actualEngineBuildId = jsonField(json, "engineBuildId"),
                    actualBackend = jsonField(json, "backend"),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "server query remote failure"))
        }
    }
}

private object EmptyServerMetricsPort : ServerMetricsPort {
    override suspend fun summary(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> =
        OmniResult.ok(emptyList())
}

// ── LAN ─────────────────────────────────────────────────────────────────────

private class AdminLanServicePort(
    private val admin: IOmniAdmin,
) : LanServicePort {
    override suspend fun status(): OmniResult<LanServiceStatus> =
        try {
            val snap = admin.snapshot
            val raw = snap.lanState.orEmpty().ifBlank { "DISABLED" }
            // Map free-form admin lanState into LAN_SERVICE catalog states.
            val state = when (raw.uppercase()) {
                "DISABLED", "OFF", "FALSE" -> "DISABLED"
                "STARTING" -> "STARTING"
                "ADVERTISING" -> "ADVERTISING"
                "ACTIVE", "ENABLED", "ON", "TRUE" -> "ACTIVE"
                "ROTATING" -> "ROTATING"
                "DRAINING" -> "DRAINING"
                "ERROR", "FAULTED" -> "ERROR"
                else -> "DISABLED"
            }
            val enabled = state != "DISABLED" && state != "ERROR"
            OmniResult.ok(
                LanServiceStatus(
                    enabled = enabled,
                    state = state,
                    connectionEpoch = 0L,
                    serverSpkiSha256 = null,
                    boundAddresses = emptyList(),
                    port = null,
                    certificateValid = false,
                    tlsReady = false,
                    pairingEndpointReady = false,
                    activeClientCount = 0,
                    resourceVersion = snap.snapshotVersion,
                    failureReason = if (state == "ERROR") raw else null,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "lan status remote failure"))
        }

    override suspend fun enable(
        principal: PrincipalId,
        spec: EnableLanSpec,
    ): OmniResult<LanServiceStatus> {
        requireLocalUi(principal)
        // Prefer settings apply when control plane maps server.lanEnabled.
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "LAN enable requires control-plane LAN host + TLS identity",
            ),
        )
    }

    override suspend fun disable(
        principal: PrincipalId,
        spec: DisableLanSpec,
    ): OmniResult<LanServiceStatus> {
        requireLocalUi(principal)
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "LAN disable requires control-plane LAN host",
            ),
        )
    }
}

private object FailClosedLanPairingPort : LanPairingPort {
    override suspend fun createChallenge(
        principal: PrincipalId,
        spec: CreatePairingChallengeSpec,
    ): OmniResult<LanChallengeView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "pairing requires control-plane LAN host"))

    override suspend fun approveChallenge(
        principal: PrincipalId,
        spec: ApprovePairingChallengeSpec,
    ): OmniResult<LanChallengeView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "pairing approve requires control-plane LAN host"))

    override suspend fun completeExchange(
        principal: PrincipalId,
        spec: CompletePairingExchangeSpec,
    ): OmniResult<LanTokenIssuanceReceipt> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "pairing exchange requires control-plane LAN host"))
}

private object EmptyLanClientPort : LanClientPort {
    override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
        OmniResult.ok(emptyList())

    override suspend fun revoke(
        principal: PrincipalId,
        spec: RevokeLanClientSpec,
    ): OmniResult<LanClientView> =
        OmniResult.err(OmniError.CAPABILITY_UNSUPPORTED(message = "LAN client revoke requires control-plane"))
}

private fun requireLocalUi(principal: PrincipalId) {
    require(principal.value == LocalUiPrincipal.ID.value) {
        "Admin projections accept LOCAL_UI principal only"
    }
}
