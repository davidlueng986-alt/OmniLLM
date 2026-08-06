package com.omnillm.features.benchmark.domain

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.runtime.observability.MetricClass

/**
 * Latency distribution sketch (DATA-MEASUREMENT §3 / FEAT-BENCHMARK §4).
 * Percentiles come from a versioned histogram/sketch; count/sum only for average.
 * Raw samples may be dropped after seal if sketch + digest remain verifiable.
 */
data class LatencySketch(
    val methodVersion: String,
    val sampleCount: Long,
    val sumMs: Double,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    /** Declared absolute error bound for displayed percentiles (ms). */
    val errorBoundMs: Double,
    /** Opaque digest of the sketch payload for integrity after raw drop. */
    val sketchDigest: String?,
    val evidenceLabel: EvidenceLabel = EvidenceLabel.MEASURED,
) {
    init {
        require(methodVersion.isNotBlank()) { "methodVersion must be non-blank" }
        require(sampleCount >= 0L) { "sampleCount must be non-negative" }
        require(sumMs >= 0.0) { "sumMs must be non-negative" }
        require(errorBoundMs >= 0.0) { "errorBoundMs must be non-negative" }
        sketchDigest?.let {
            require(it.matches(HEX64)) { "sketchDigest must be 64-char hex" }
        }
        p50Ms?.let { require(it >= 0.0) { "p50Ms must be non-negative" } }
        p95Ms?.let { require(it >= 0.0) { "p95Ms must be non-negative" } }
        p99Ms?.let { require(it >= 0.0) { "p99Ms must be non-negative" } }
    }

    /** Average from count/sum only — never from percentiles (DATA-MEASUREMENT §3). */
    fun averageMs(): Double? =
        if (sampleCount > 0L) sumMs / sampleCount.toDouble() else null

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Separated measurement metrics (FEAT-BENCHMARK §4).
 * Never mixed into operational telemetry comparison surfaces.
 */
data class MeasurementMetrics(
    val loadTimeMs: Double?,
    val ttftMs: Double?,
    val throughputTokensPerSec: Double?,
    val interTokenLatency: LatencySketch?,
    val endToEndLatencyMs: Double?,
    val peakResidentBytes: Long?,
    val peakAcceleratorBytes: Long?,
    val peakWorkspaceBytes: Long?,
    val energyHintJoules: Double?,
    val thermalEventCount: Int,
    val errorCount: Int,
    val evidenceLabel: EvidenceLabel = EvidenceLabel.MEASURED,
    val methodVersion: String,
) {
    init {
        require(methodVersion.isNotBlank()) { "methodVersion must be non-blank" }
        require(thermalEventCount >= 0) { "thermalEventCount must be non-negative" }
        require(errorCount >= 0) { "errorCount must be non-negative" }
        loadTimeMs?.let { require(it >= 0.0) { "loadTimeMs must be non-negative" } }
        ttftMs?.let { require(it >= 0.0) { "ttftMs must be non-negative" } }
        throughputTokensPerSec?.let {
            require(it >= 0.0) { "throughputTokensPerSec must be non-negative" }
        }
        endToEndLatencyMs?.let {
            require(it >= 0.0) { "endToEndLatencyMs must be non-negative" }
        }
        peakResidentBytes?.let {
            require(it >= 0L) { "peakResidentBytes must be non-negative" }
        }
        peakAcceleratorBytes?.let {
            require(it >= 0L) { "peakAcceleratorBytes must be non-negative" }
        }
        peakWorkspaceBytes?.let {
            require(it >= 0L) { "peakWorkspaceBytes must be non-negative" }
        }
        energyHintJoules?.let {
            require(it >= 0.0) { "energyHintJoules must be non-negative" }
        }
    }

    /** Metric class fence: always MEASUREMENT (CORE-OBSERVABILITY §5). */
    fun metricClass(): MetricClass = MetricClass.MEASUREMENT
}

/**
 * Template presets for zero-threshold UI (FEAT-BENCHMARK §2).
 * Values remain expandable to full profile dimensions.
 */
data class BenchmarkScenarioTemplate(
    val templateId: String,
    val labelKey: String,
    val descriptionKey: String,
    val defaultSampleCount: Int,
    val defaultWarmupCount: Int,
    val defaultContextLength: Int,
    val defaultOutputTokenLimit: Int,
) {
    init {
        require(templateId.isNotBlank()) { "templateId must be non-blank" }
        require(labelKey.isNotBlank()) { "labelKey must be non-blank" }
        require(defaultSampleCount in 1..10_000) { "defaultSampleCount must be 1..10000" }
        require(defaultWarmupCount >= 0) { "defaultWarmupCount must be non-negative" }
        require(defaultContextLength > 0) { "defaultContextLength must be positive" }
        require(defaultOutputTokenLimit > 0) { "defaultOutputTokenLimit must be positive" }
    }

    companion object {
        val QUICK_SMOKE: BenchmarkScenarioTemplate = BenchmarkScenarioTemplate(
            templateId = "quick_smoke",
            labelKey = "benchmark.template.quick_smoke",
            descriptionKey = "benchmark.template.quick_smoke.desc",
            defaultSampleCount = 3,
            defaultWarmupCount = 1,
            defaultContextLength = 512,
            defaultOutputTokenLimit = 64,
        )
        val THROUGHPUT: BenchmarkScenarioTemplate = BenchmarkScenarioTemplate(
            templateId = "throughput",
            labelKey = "benchmark.template.throughput",
            descriptionKey = "benchmark.template.throughput.desc",
            defaultSampleCount = 20,
            defaultWarmupCount = 2,
            defaultContextLength = 2048,
            defaultOutputTokenLimit = 256,
        )
        val LATENCY: BenchmarkScenarioTemplate = BenchmarkScenarioTemplate(
            templateId = "latency",
            labelKey = "benchmark.template.latency",
            descriptionKey = "benchmark.template.latency.desc",
            defaultSampleCount = 50,
            defaultWarmupCount = 5,
            defaultContextLength = 1024,
            defaultOutputTokenLimit = 128,
        )

        val ALL: List<BenchmarkScenarioTemplate> = listOf(QUICK_SMOKE, THROUGHPUT, LATENCY)

        fun byId(id: String): BenchmarkScenarioTemplate? =
            ALL.firstOrNull { it.templateId == id }
    }
}
