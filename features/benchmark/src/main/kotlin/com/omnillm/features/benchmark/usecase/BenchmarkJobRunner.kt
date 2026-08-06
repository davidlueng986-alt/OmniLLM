package com.omnillm.features.benchmark.usecase

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.domain.BenchmarkPhases
import com.omnillm.features.benchmark.domain.FixtureMeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.ports.BenchmarkCapabilityAvailabilityPort
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobProgress
import com.omnillm.core.state.domain.JobId

/**
 * Control-plane job runner hooks for BENCHMARK jobs (FEAT-BENCHMARK §3).
 *
 * - Advances job progress phases (warmup → sample → sealing)
 * - When engine [CapabilityId.TEXT_GENERATION] / performance path is not SUPPORTED,
 *   seals with [FixtureMeasurementMetrics] (REPORTED, fixture source) — never invents PASS
 * - Does not elevate capability matrix cells
 *
 * Call only from runtime process (ADR-010). UI never invokes this.
 */
class BenchmarkJobRunner(
    private val benchmarkApi: BenchmarkApi,
    private val jobManager: JobManager,
    private val capabilityAvailability: BenchmarkCapabilityAvailabilityPort,
    private val engineGenerationState: () -> CapabilityState = { CapabilityState.UNKNOWN },
) {

    /**
     * Drive a started BENCHMARK job to terminal with real or fixture metrics.
     *
     * @param jobId client-generated job id already claimed via [BenchmarkApi.startBenchmark]
     * @param profileId profile id for fixture seed when no engine SUPPORTED
     * @param sampleCount profile sample count for fixture sketch
     * @param liveMetrics when non-null and engine SUPPORTED, used instead of fixture
     */
    suspend fun runToCompletion(
        jobId: String,
        profileId: String,
        sampleCount: Int = 3,
        liveMetrics: MeasurementMetrics? = null,
    ): OmniResult<MeasurementRun> {
        val id = try {
            JobId(jobId)
        } catch (_: IllegalArgumentException) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.INVALID_REQUEST(
                    message = "invalid jobId",
                    details = mapOf("jobId" to jobId),
                ),
            )
        }

        val record = when (val q = jobManager.query(id)) {
            is OmniResult.Ok -> q.value
            is OmniResult.Err -> return q
        }
        if (record.kind != JobKind.BENCHMARK) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.INVALID_REQUEST(
                    message = "job is not BENCHMARK",
                    details = mapOf("kind" to record.kind.name),
                ),
            )
        }

        // Progress hooks (shared scheduler with interactive work — FEAT-BENCHMARK §3).
        if (record.state == "QUEUED") {
            when (val s = jobManager.start(id)) {
                is OmniResult.Err -> return s
                is OmniResult.Ok -> Unit
            }
        }
        jobManager.updateProgress(
            id,
            JobProgress(currentPhase = BenchmarkPhases.WARMUP, sampleCount = 0L),
        )
        jobManager.updateProgress(
            id,
            JobProgress(currentPhase = BenchmarkPhases.SAMPLING, sampleCount = sampleCount.toLong()),
        )

        val engineState = engineGenerationState()
        val metrics = when {
            liveMetrics != null && engineState == CapabilityState.SUPPORTED -> liveMetrics
            engineState == CapabilityState.SUPPORTED && liveMetrics == null -> {
                // Engine claimed SUPPORTED but host did not supply metrics — fail closed
                // rather than invent PASS numbers.
                return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.INTERNAL(
                        message = "engine SUPPORTED but live metrics not provided",
                        details = mapOf("jobId" to jobId),
                    ),
                )
            }
            else -> {
                // UNKNOWN / UNSUPPORTED / CONDITIONAL without live metrics → fixture path.
                // Mark REPORTED fixture; never elevates matrix to SUPPORTED.
                FixtureMeasurementMetrics.forProfile(profileId, sampleCount)
            }
        }

        // Capability gate for PERFORMANCE_MEASUREMENT remains on BenchmarkService.
        @Suppress("UNUSED_VARIABLE")
        val _perf = capabilityAvailability.resolve(CapabilityId.PERFORMANCE_MEASUREMENT)

        return benchmarkApi.completeRun(
            jobId = jobId,
            metrics = metrics,
            forceInvalid = false,
        )
    }
}
