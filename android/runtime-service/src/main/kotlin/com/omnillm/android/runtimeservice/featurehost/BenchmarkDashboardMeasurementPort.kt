package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.domain.FixtureMeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.features.dashboard.ports.DashboardMeasurementPort
import com.omnillm.features.dashboard.ports.EmptyDashboardMeasurementPort
import com.omnillm.features.dashboard.ports.LastMeasurementFact
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking

/**
 * Projects last sealed benchmark run into dashboard MEASUREMENTS tab.
 * Late-bound [holder] so Wave-A dashboard can construct before Wave-B BenchmarkApi.
 *
 * Fixture metrics stay REPORTED with fixture source — never invent engine SUPPORTED.
 */
class BenchmarkDashboardMeasurementPort(
    private val holder: BenchmarkApiHolder,
) : DashboardMeasurementPort {

    override fun lastSealedRun(): LastMeasurementFact? {
        val api = holder.benchmarkApi ?: return null
        return runBlocking {
            when (val snap = api.getSnapshot(LocalUiPrincipal.ID)) {
                is com.omnillm.core.canonical.generated.OmniResult.Err -> null
                is com.omnillm.core.canonical.generated.OmniResult.Ok -> {
                    val runs = snap.value.runs
                        .filter { MeasurementRunOutcomes.isTerminal(it.outcome) }
                        .sortedByDescending { it.runSeq }
                    val run = runs.firstOrNull() ?: return@runBlocking null
                    val metrics = run.metrics
                    val fixture = metrics?.methodVersion == FixtureMeasurementMetrics.FIXTURE_METHOD_VERSION
                    LastMeasurementFact(
                        runId = run.runId,
                        profileId = run.profileId,
                        runSeq = run.runSeq,
                        outcome = run.outcome,
                        startedAtEpochMs = run.startedAtEpochMs,
                        completedAtEpochMs = run.completedAtEpochMs,
                        ttftMs = metrics?.ttftMs,
                        throughputTokensPerSec = metrics?.throughputTokensPerSec,
                        endToEndLatencyMs = metrics?.endToEndLatencyMs,
                        evidenceLabel = metrics?.evidenceLabel
                            ?: com.omnillm.core.canonical.generated.EvidenceLabel.UNKNOWN,
                        methodVersion = metrics?.methodVersion,
                        fixtureSource = if (fixture) {
                            FixtureMeasurementMetrics.FIXTURE_SOURCE
                        } else {
                            null
                        },
                        deviationReasons = run.deviationReasons,
                    )
                }
            }
        }
    }
}

/**
 * Process-local late binding for BenchmarkApi → dashboard measurements.
 *
 * ARC-04 documented debt: kept because benchmarkApi is constructed by
 * FeaturePackHost.bootstrap AFTER WaveAWiring.wire() (bootstrap cycle —
 * see featurehost/README.md Wave-A vs Wave-B). @Volatile + fail-closed
 * (null ⇒ empty projection). Removal requires bootstrap reordering.
 */
class BenchmarkApiHolder {
    @Volatile
    var benchmarkApi: BenchmarkApi? = null
}

/** Delegating measurement port that reads from late-bound holder. */
class DeferredDashboardMeasurementPort(
    private val holder: BenchmarkApiHolder,
) : DashboardMeasurementPort {
    private val adapter = BenchmarkDashboardMeasurementPort(holder)

    override fun lastSealedRun(): LastMeasurementFact? =
        if (holder.benchmarkApi == null) {
            EmptyDashboardMeasurementPort.lastSealedRun()
        } else {
            adapter.lastSealedRun()
        }
}
