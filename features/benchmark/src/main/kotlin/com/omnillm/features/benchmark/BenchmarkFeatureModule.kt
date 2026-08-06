package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.ports.BenchmarkCapabilityAvailabilityPort
import com.omnillm.features.benchmark.ports.BenchmarkEnvironmentPort
import com.omnillm.features.benchmark.ports.DefaultBenchmarkCapabilityAvailabilityPort
import com.omnillm.features.benchmark.ports.EmptyBenchmarkEnvironmentPort
import com.omnillm.features.benchmark.usecase.BenchmarkService
import com.omnillm.features.benchmark.viewmodel.BenchmarkViewModel
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.observability.ObservabilityFacade

/**
 * Feature pack `:features:benchmark` (FEAT-BENCHMARK).
 *
 * Composes platform capabilities:
 * - PERFORMANCE_MEASUREMENT / EVIDENCE_LABELING
 * - CAPABILITY_NEGOTIATION / JOB_LIFECYCLE / JOB_RECOVERY / RESOURCE_ACCOUNTING
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * UI process talks only through this API / Admin binder (INV-001).
 * Measurement runs and job ledger are written only by the runtime control plane (ADR-010).
 *
 * Policy anchors:
 * - LAN / report: fail closed on auth; QR must not carry long-lived secrets
 * - Report ≠ operational telemetry (MetricClass.MEASUREMENT vs OPERATIONAL)
 * - Routing: never silent cross-revision fallback
 */
object BenchmarkFeatureModule {
    const val MODULE_PATH: String = ":features:benchmark"
    const val FEATURE_ID: String = "FEAT-BENCHMARK"

    /** From `specs/feature-capability-map.yaml` for FEAT-BENCHMARK. */
    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.PERFORMANCE_MEASUREMENT,
        CapabilityId.EVIDENCE_LABELING,
        CapabilityId.CAPABILITY_NEGOTIATION,
        CapabilityId.JOB_LIFECYCLE,
        CapabilityId.JOB_RECOVERY,
        CapabilityId.RESOURCE_ACCOUNTING,
    )

    /**
     * Wire control-plane dependencies. Call only from runtime host
     * (`:android:runtime-service`), never from UI process.
     */
    fun createApi(
        jobManager: JobManager,
        observability: ObservabilityFacade,
        environment: BenchmarkEnvironmentPort = EmptyBenchmarkEnvironmentPort,
        capabilityAvailability: BenchmarkCapabilityAvailabilityPort =
            DefaultBenchmarkCapabilityAvailabilityPort(),
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): BenchmarkApi =
        BenchmarkService(
            jobManager = jobManager,
            observability = observability,
            environment = environment,
            capabilityAvailability = capabilityAvailability,
            clockMs = clockMs,
        )

    fun createViewModel(api: BenchmarkApi): BenchmarkViewModel =
        BenchmarkViewModel(api)
}
