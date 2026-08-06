package com.omnillm.features.benchmark.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.benchmark.domain.MeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun

/**
 * Public Benchmark API for LOCAL_UI / Admin composition (FEAT-BENCHMARK).
 *
 * Composes PERFORMANCE_MEASUREMENT, EVIDENCE_LABELING, CAPABILITY_NEGOTIATION,
 * JOB_LIFECYCLE, JOB_RECOVERY, RESOURCE_ACCOUNTING without redefining Request /
 * Session / Trust (FEATURE-SYSTEM §1).
 *
 * Plan → Reserve → Commit → Execute: [planBenchmark] has no domain mutation
 * (ADR-002). Mutations go through Job Manager; this feature never writes the
 * domain DB itself (ADR-010 / INV-001).
 */
interface BenchmarkApi {

    /** Full benchmark screen snapshot. */
    suspend fun getSnapshot(principal: PrincipalId): OmniResult<BenchmarkSnapshot>

    /**
     * Pure plan of MeasurementProfile + fairness notice (ADR-002).
     * Does not create jobs or runs.
     */
    fun planBenchmark(
        principal: PrincipalId,
        spec: PlanBenchmarkSpec,
    ): OmniResult<BenchmarkPlan>

    /**
     * Claim BENCHMARK job and open a MeasurementRun placeholder.
     * Client generates jobId / runId / idempotencyKey (ADR-004/005).
     */
    suspend fun startBenchmark(
        principal: PrincipalId,
        spec: StartBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle>

    /** Cancel non-terminal benchmark job. */
    suspend fun cancelBenchmark(
        principal: PrincipalId,
        spec: CancelBenchmarkSpec,
    ): OmniResult<BenchmarkJobHandle>

    /** Query job after reply loss — never re-create blindly. */
    suspend fun queryBenchmarkJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<BenchmarkJobHandle>

    /** List runs for a profile, ordered by runSeq DESC. */
    suspend fun listRuns(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<List<MeasurementRun>>

    /**
     * Current run for a profile: uniquely by max runSeq
     * (FEAT-BENCHMARK acceptance §2 — not timestamp).
     */
    suspend fun currentRun(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<MeasurementRun?>

    /**
     * Compare two profiles — forbids single ranking when incompatible
     * (FEAT-BENCHMARK acceptance §6).
     */
    fun compareProfiles(
        principal: PrincipalId,
        leftProfileId: String,
        rightProfileId: String,
    ): OmniResult<ProfileComparisonView>

    /**
     * Runtime-host pipeline: capture environment, apply thermal/routing
     * policy, attach metrics, seal run outcome. Partial never becomes VALID.
     */
    suspend fun completeRun(
        jobId: String,
        metrics: MeasurementMetrics,
        forceInvalid: Boolean = false,
    ): OmniResult<MeasurementRun>

    /**
     * Export measurement report (local-admin only). Report is MetricClass.MEASUREMENT
     * and must not be treated as operational telemetry.
     */
    suspend fun exportReport(
        principal: PrincipalId,
        spec: ExportReportSpec,
    ): OmniResult<MeasurementReport>

    /** Look up a registered profile by id. */
    fun getProfile(
        principal: PrincipalId,
        profileId: String,
    ): OmniResult<MeasurementProfile>
}
