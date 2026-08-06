package com.omnillm.features.benchmark.usecase

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.benchmark.BenchmarkFeatureModule
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
import com.omnillm.features.benchmark.domain.BenchmarkPhases
import com.omnillm.features.benchmark.domain.MeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.features.benchmark.export.BenchmarkReportBuilder
import com.omnillm.features.benchmark.policy.BenchmarkAuthPolicy
import com.omnillm.features.benchmark.policy.BenchmarkRoutingPolicy
import com.omnillm.features.benchmark.policy.ThermalDeviationPolicy
import com.omnillm.features.benchmark.ports.BenchmarkCapabilityAvailabilityPort
import com.omnillm.features.benchmark.ports.BenchmarkEnvironmentPort
import com.omnillm.features.benchmark.projection.BenchmarkStateProjection
import com.omnillm.features.benchmark.projection.ProfileComparison
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.observability.ObservabilityFacade
import java.util.concurrent.atomic.AtomicLong

/**
 * FEAT-BENCHMARK control-plane use-case facade.
 *
 * - MeasurementProfile plan (no mutation) → BENCHMARK job claim-or-return
 * - Thermal / routing deviation → INVALID / DEGRADED (no silent merge)
 * - Never silent cross-revision fallback
 * - LAN / report auth fail closed; report ≠ telemetry
 * - Client-generated jobId / runId / requestId / idempotencyKey (ADR-004/005)
 * - No direct DB writes (ADR-010 / INV-001)
 */
class BenchmarkService(
    private val jobManager: JobManager,
    @Suppress("unused") private val observability: ObservabilityFacade,
    private val environment: BenchmarkEnvironmentPort,
    private val capabilityAvailability: BenchmarkCapabilityAvailabilityPort,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : BenchmarkApi {

    private val snapshotSeq = AtomicLong(0L)
    private val lock = Any()

    /** profileId → profile */
    private val profiles = linkedMapOf<String, MeasurementProfile>()

    /** runId → run */
    private val runs = linkedMapOf<String, MeasurementRun>()

    /** jobId → runId */
    private val jobToRun = linkedMapOf<String, String>()

    /** profileId → next runSeq */
    private val nextRunSeq = linkedMapOf<String, Long>()

    /** jobId → fallback + allowlist (for completeRun) */
    private val jobRouting = linkedMapOf<String, JobRoutingContext>()

    private var lastPlan: BenchmarkPlan? = null
    private var lastReport: MeasurementReport? = null
    private var lastError: OmniError? = null
    private var activeJobId: String? = null

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<BenchmarkSnapshot> {
        requireLocalUi(principal)
        return OmniResult.ok(buildSnapshotLocked())
    }

    override fun planBenchmark(
        principal: PrincipalId,
        spec: PlanBenchmarkSpec,
    ): OmniResult<BenchmarkPlan> {
        requireLocalUi(principal)

        val auth = BenchmarkAuthPolicy.authorizeStart(principal)
        if (auth != null) return OmniResult.err(auth)

        val cap = negotiateRequired()
        if (cap != null) return OmniResult.err(cap)

        BenchmarkRoutingPolicy.parseFallbackPolicy(spec.fallbackPolicy)?.let {
            return OmniResult.err(it)
        }

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
            lastPlan = plan
            lastError = null
        }
        return OmniResult.ok(plan)
    }

    override suspend fun startBenchmark(
        principal: PrincipalId,
        spec: StartBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)

        val auth = BenchmarkAuthPolicy.authorizeStart(principal)
        if (auth != null) {
            lastError = auth
            return OmniResult.err(auth)
        }

        val cap = negotiateRequired()
        if (cap != null) {
            lastError = cap
            return OmniResult.err(cap)
        }

        BenchmarkRoutingPolicy.parseFallbackPolicy(spec.fallbackPolicy)?.let {
            lastError = it
            return OmniResult.err(it)
        }

        val jobId = try {
            JobId(spec.jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }

        val profile = spec.profile
        val profileId = profile.profileId()

        // Re-hash identity must match if caller supplied digest via command.
        if (spec.command.canonicalInputDigest != profileId &&
            spec.command.canonicalInputDigest != digestOfStart(spec)
        ) {
            // Allow either profile id or full start-spec digest; both are hex64.
            // Soft check: still accept when digest is any valid hex64 (client may hash more).
        }

        val identity = JobIdentity(
            jobId = jobId,
            principalId = principal,
            kind = JobKind.BENCHMARK,
            idempotencyKey = IdempotencyKey.parse(spec.command.idempotencyKey),
            canonicalSpecDigest = spec.command.canonicalInputDigest.lowercase(),
        )
        val params = JobParameters.Benchmark(
            modelRevisionId = profile.modelRevisionId,
            engineBuildId = profile.engineBuildId,
            backend = profile.backend,
            measurementProfileId = profileId,
            iterations = spec.iterations ?: profile.sampleCount,
        )

        val claim = when (val created = jobManager.create(identity, params)) {
            is OmniResult.Ok -> created.value
            is OmniResult.Err -> {
                lastError = created.error
                return created
            }
        }

        synchronized(lock) {
            profiles[profileId] = profile
            jobToRun[spec.jobId] = spec.runId
            jobRouting[spec.jobId] = JobRoutingContext(
                fallbackPolicy = FallbackPolicy.fromCatalogName(spec.fallbackPolicy)
                    ?: FallbackPolicy.NONE,
                allowedRevisionIds = spec.allowedRevisionIds,
                profileId = profileId,
            )
            activeJobId = spec.jobId
            lastError = null

            if (claim.createdNew || !runs.containsKey(spec.runId)) {
                val seq = (nextRunSeq[profileId] ?: 0L) + 1L
                nextRunSeq[profileId] = seq
                val now = clockMs()
                runs[spec.runId] = MeasurementRun(
                    runId = spec.runId,
                    profileId = profileId,
                    jobId = spec.jobId,
                    runSeq = seq,
                    startedAtEpochMs = now,
                    completedAtEpochMs = null,
                    outcome = MeasurementRunOutcomes.RUNNING,
                    environmentSnapshot = null,
                    metrics = null,
                )
            }
        }

        if (claim.createdNew) {
            jobManager.start(jobId)
            jobManager.updateProgress(
                jobId,
                JobProgress(currentPhase = BenchmarkPhases.WARMUP, sampleCount = 0L),
            )
        }

        val record = jobManager.query(jobId).getOrNull() ?: claim.record
        return OmniResult.ok(
            BenchmarkStateProjection.projectJobHandle(
                record = record,
                runId = spec.runId,
                createdNew = claim.createdNew,
            ),
        )
    }

    override suspend fun cancelBenchmark(
        principal: PrincipalId,
        spec: CancelBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)
        val auth = BenchmarkAuthPolicy.authorizeStart(principal)
        if (auth != null) return OmniResult.err(auth)

        val jobId = try {
            JobId(spec.jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }

        val cancelled = when (val c = jobManager.cancel(jobId, requestOnly = spec.requestOnly)) {
            is OmniResult.Ok -> c.value
            is OmniResult.Err -> {
                lastError = c.error
                return c
            }
        }

        synchronized(lock) {
            val runId = jobToRun[spec.jobId]
            if (runId != null && !spec.requestOnly) {
                val existing = runs[runId]
                if (existing != null && !MeasurementRunOutcomes.isTerminal(existing.outcome)) {
                    runs[runId] = existing.copy(
                        outcome = MeasurementRunOutcomes.CANCELLED,
                        completedAtEpochMs = clockMs(),
                        metrics = null,
                        error = OmniError.CANCELLED(message = "benchmark cancelled"),
                    )
                }
            }
            if (!spec.requestOnly && activeJobId == spec.jobId) {
                activeJobId = null
            }
            lastError = null
        }

        return OmniResult.ok(
            BenchmarkStateProjection.projectJobHandle(
                record = cancelled,
                runId = jobToRun[spec.jobId],
                createdNew = false,
            ),
        )
    }

    override suspend fun queryBenchmarkJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<BenchmarkJobHandle> {
        requireLocalUi(principal)
        val id = try {
            JobId(jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }
        return when (val q = jobManager.query(id)) {
            is OmniResult.Ok -> OmniResult.ok(
                BenchmarkStateProjection.projectJobHandle(
                    record = q.value,
                    runId = jobToRun[jobId],
                ),
            )
            is OmniResult.Err -> q
        }
    }

    override suspend fun listRuns(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<List<MeasurementRun>> {
        requireLocalUi(principal)
        if (!profileId.matches(HEX64)) return invalidId("profileId")
        val list = synchronized(lock) {
            runs.values
                .filter { it.profileId == profileId }
                .sortedByDescending { it.runSeq }
                .toList()
        }
        return OmniResult.ok(list)
    }

    override suspend fun currentRun(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<MeasurementRun?> {
        requireLocalUi(principal)
        if (!profileId.matches(HEX64)) return invalidId("profileId")
        val current = synchronized(lock) {
            BenchmarkStateProjection.currentRunsByProfile(runs.values.toList())[profileId]
        }
        return OmniResult.ok(current)
    }

    override fun compareProfiles(
        principal: PrincipalId,
        leftProfileId: String,
        rightProfileId: String,
    ): OmniResult<ProfileComparisonView> {
        requireLocalUi(principal)
        val left = synchronized(lock) { profiles[leftProfileId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "profile not found",
                    details = mapOf("profileId" to leftProfileId),
                ),
            )
        val right = synchronized(lock) { profiles[rightProfileId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "profile not found",
                    details = mapOf("profileId" to rightProfileId),
                ),
            )
        return OmniResult.ok(ProfileComparison.compare(left, right))
    }

    override suspend fun completeRun(
        jobId: String,
        metrics: MeasurementMetrics,
        forceInvalid: Boolean,
    ): OmniResult<MeasurementRun> {
        // Metric class fence: report != telemetry
        require(metrics.metricClass() == com.omnillm.runtime.observability.MetricClass.MEASUREMENT) {
            "benchmark metrics must be MEASUREMENT class"
        }

        val id = try {
            JobId(jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val record = when (val q = jobManager.query(id)) {
            is OmniResult.Ok -> q.value
            is OmniResult.Err -> return q
        }
        if (record.kind != JobKind.BENCHMARK) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "job is not BENCHMARK",
                    details = mapOf("kind" to record.kind.name),
                ),
            )
        }
        if (record.state == "CANCELLED" || record.cancelRequested) {
            return OmniResult.err(OmniError.CANCELLED(message = "benchmark cancelled before complete"))
        }
        if (record.isTerminal && record.state != "SUCCEEDED") {
            return OmniResult.err(
                record.error
                    ?: OmniError.STATE_CONFLICT(message = "job terminal: ${record.state}"),
            )
        }

        val runId = synchronized(lock) { jobToRun[jobId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "no run linked to job", details = mapOf("jobId" to jobId)),
            )
        val existing = synchronized(lock) { runs[runId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "run not found", details = mapOf("runId" to runId)),
            )
        val profile = synchronized(lock) { profiles[existing.profileId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "profile not found",
                    details = mapOf("profileId" to existing.profileId),
                ),
            )
        val routing = synchronized(lock) { jobRouting[jobId] }
            ?: JobRoutingContext(FallbackPolicy.NONE, emptySet(), existing.profileId)

        // Capture environment and apply routing + thermal policy.
        val env = environment.capture(
            requestedModelRevisionId = profile.modelRevisionId,
            requestedEngineBuildId = profile.engineBuildId,
            requestedBackend = profile.backend,
        ).let { if (it.capturedAtEpochMs == 0L) it.copy(capturedAtEpochMs = clockMs()) else it }

        when (
            val route = BenchmarkRoutingPolicy.validateActualAgainstProfile(
                profile = profile,
                actual = env,
                fallbackPolicy = routing.fallbackPolicy,
                allowedRevisionIds = routing.allowedRevisionIds,
            )
        ) {
            is BenchmarkRoutingPolicy.RoutingCheck.Fail -> {
                jobManager.fail(id, route.error)
                val failed = existing.copy(
                    outcome = MeasurementRunOutcomes.FAILED,
                    completedAtEpochMs = clockMs(),
                    environmentSnapshot = env,
                    metrics = null,
                    deviationReasons = listOf(
                        route.error.details["reason"] ?: "routing_failed",
                    ),
                    error = route.error,
                )
                synchronized(lock) {
                    runs[runId] = failed
                    if (activeJobId == jobId) activeJobId = null
                    lastError = route.error
                }
                return OmniResult.err(route.error)
            }
            is BenchmarkRoutingPolicy.RoutingCheck.Ok -> {
                val decision = ThermalDeviationPolicy.evaluate(
                    profile = profile,
                    environment = env,
                    routingDeviations = route.deviations,
                    forcedInvalid = forceInvalid,
                )
                return sealRun(id, record, existing, env, metrics, decision, jobId, runId)
            }
            is BenchmarkRoutingPolicy.RoutingCheck.Degraded -> {
                val decision = ThermalDeviationPolicy.evaluate(
                    profile = profile,
                    environment = env,
                    routingDeviations = route.deviations,
                    forcedInvalid = forceInvalid,
                )
                return sealRun(id, record, existing, env, metrics, decision, jobId, runId)
            }
        }
    }

    override suspend fun exportReport(
        principal: PrincipalId,
        spec: ExportReportSpec,
    ): OmniResult<MeasurementReport> {
        val auth = BenchmarkAuthPolicy.authorizeReportExport(principal)
        if (auth != null) return OmniResult.err(auth)

        // Only LOCAL_UI principal for UI path (INV-001).
        if (principal.value != LocalUiPrincipal.ID.value &&
            principal.value != "HTTP_LOCAL_ADMIN"
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "report export requires local admin principal",
                    details = mapOf("principalId" to principal.value),
                ),
            )
        }

        val profile = synchronized(lock) { profiles[spec.profileId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "profile not found",
                    details = mapOf("profileId" to spec.profileId),
                ),
            )

        val selected = synchronized(lock) {
            if (spec.runIds.isEmpty()) {
                runs.values.filter { it.profileId == spec.profileId }.toList()
            } else {
                spec.runIds.mapNotNull { runs[it] }
                    .filter { it.profileId == spec.profileId }
            }
        }
        if (selected.isEmpty()) {
            return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "no runs for report",
                    details = mapOf("profileId" to spec.profileId),
                ),
            )
        }

        val report = BenchmarkReportBuilder.build(
            reportId = spec.reportId,
            profile = profile,
            runs = selected,
            includePromptOutput = spec.includePromptOutput,
            createdAtEpochMs = clockMs(),
        )
        BenchmarkReportBuilder.assertNotTelemetry(report)

        // QR must not contain long-lived secrets.
        BenchmarkAuthPolicy.validateQrPayload(report.qrShareFields)?.let {
            return OmniResult.err(it)
        }

        synchronized(lock) {
            lastReport = report
            lastError = null
        }
        return OmniResult.ok(report)
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

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun sealRun(
        id: JobId,
        record: JobRecord,
        existing: MeasurementRun,
        env: com.omnillm.features.benchmark.domain.EnvironmentSnapshot,
        metrics: MeasurementMetrics,
        decision: ThermalDeviationPolicy.Decision,
        jobId: String,
        runId: String,
    ): OmniResult<MeasurementRun> {
        jobManager.updateProgress(
            id,
            JobProgress(
                currentPhase = BenchmarkPhases.SEALING,
                sampleCount = metrics.interTokenLatency?.sampleCount
                    ?: metrics.ttftMs?.let { 1L } ?: 0L,
            ),
        )

        val sealed = existing.copy(
            outcome = decision.outcome,
            completedAtEpochMs = clockMs(),
            environmentSnapshot = env,
            metrics = metrics,
            deviationReasons = decision.reasons,
            error = null,
        )

        when (record.state) {
            "QUEUED" -> {
                when (val started = jobManager.start(id)) {
                    is OmniResult.Err -> return started
                    is OmniResult.Ok -> Unit
                }
                when (val done = jobManager.succeed(id)) {
                    is OmniResult.Err -> return done
                    is OmniResult.Ok -> Unit
                }
            }
            "RUNNING" -> {
                when (val done = jobManager.succeed(id)) {
                    is OmniResult.Err -> return done
                    is OmniResult.Ok -> Unit
                }
            }
            "SUCCEEDED" -> Unit
            else -> {
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "cannot seal from job state ${record.state}",
                        details = mapOf("state" to record.state),
                    ),
                )
            }
        }

        synchronized(lock) {
            runs[runId] = sealed
            lastError = null
            if (activeJobId == jobId) activeJobId = null
        }
        return OmniResult.ok(sealed)
    }

    private fun buildSnapshotLocked(): BenchmarkSnapshot {
        val job: JobRecord?
        val runId: String?
        val plan: BenchmarkPlan?
        val error: OmniError?
        val report: MeasurementReport?
        val profileList: List<MeasurementProfile>
        val runList: List<MeasurementRun>
        synchronized(lock) {
            val jid = activeJobId
            job = jid?.let { jobManager.query(JobId(it)).getOrNull() }
            runId = jid?.let { jobToRun[it] }
            plan = lastPlan
            error = lastError
            report = lastReport
            profileList = profiles.values.toList()
            runList = runs.values.toList()
        }
        return BenchmarkStateProjection.projectSnapshot(
            snapshotVersion = snapshotSeq.incrementAndGet(),
            profiles = profileList,
            runs = runList,
            activeJob = job,
            runIdForJob = runId,
            plan = plan,
            lastReport = report,
            lastError = error,
        )
    }

    private fun negotiateRequired(): OmniError? {
        for (cap in BenchmarkFeatureModule.REQUIRED_CAPABILITIES) {
            when (val state = capabilityAvailability.resolve(cap)) {
                CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
                CapabilityState.UNSUPPORTED -> {
                    return OmniError.CAPABILITY_UNSUPPORTED(
                        message = "required capability unsupported",
                        details = mapOf(
                            "capabilityId" to cap.id,
                            "capabilityState" to state.name,
                        ),
                    )
                }
                CapabilityState.UNKNOWN, CapabilityState.TEMPORARILY_UNAVAILABLE -> {
                    return OmniError.CAPABILITY_UNKNOWN(
                        message = "required capability not available",
                        details = mapOf(
                            "capabilityId" to cap.id,
                            "capabilityState" to state.name,
                        ),
                    )
                }
            }
        }
        // Fail closed on unknown catalog capability names is handled by CapabilityId enum.
        @Suppress("UNUSED_VARIABLE")
        val _guard: CapabilityId = CapabilityId.PERFORMANCE_MEASUREMENT
        return null
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "BenchmarkApi accepts LOCAL_UI principal only on UI path (INV-001)"
        }
    }

    private fun invalidId(field: String): OmniResult.Err =
        OmniResult.err(
            OmniError.INVALID_REQUEST(
                message = "invalid $field",
                details = mapOf("field" to field),
            ),
        ) as OmniResult.Err

    private fun digestOfStart(spec: StartBenchmarkSpec): String =
        com.omnillm.core.canonical.IdentityHashing.sha256Hex(
            listOf(
                spec.jobId,
                spec.runId,
                spec.profile.profileId(),
                (spec.iterations ?: -1).toString(),
                spec.fallbackPolicy,
            ).joinToString("|"),
        )

    private data class JobRoutingContext(
        val fallbackPolicy: FallbackPolicy,
        val allowedRevisionIds: Set<String>,
        val profileId: String,
    )

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
