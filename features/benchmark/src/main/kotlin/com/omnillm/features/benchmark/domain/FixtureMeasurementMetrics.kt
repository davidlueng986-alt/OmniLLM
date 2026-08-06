package com.omnillm.features.benchmark.domain

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.EvidenceLabel

/**
 * Synthetic fixture metrics when no engine cell is SUPPORTED (FEAT-BENCHMARK / SW software path).
 *
 * Evidence is [EvidenceLabel.SYNTHETIC] or [EvidenceLabel.REPORTED] with explicit source —
 * never presented as device PASS or engine SUPPORTED elevation.
 */
object FixtureMeasurementMetrics {

    const val FIXTURE_METHOD_VERSION: String = "omnillm.fixture.v1"
    const val FIXTURE_SOURCE: String = "benchmark-fixture-no-engine-supported"

    /**
     * Deterministic fixture metrics derived from [profileId] for stable digests.
     * Used by [com.omnillm.features.benchmark.usecase.BenchmarkJobRunner] when
     * engine capability is not SUPPORTED.
     */
    fun forProfile(profileId: String, sampleCount: Int = 3): MeasurementMetrics {
        require(sampleCount >= 1) { "sampleCount must be positive" }
        // Stable pseudo-values from profile digest prefix — not real device numbers.
        val seed = profileId.take(8).toLongOrNull(16) ?: 1L
        val ttft = 40.0 + (seed % 30)
        val tokPerSec = 8.0 + (seed % 5)
        val e2e = ttft + 120.0 + (seed % 50)
        val interToken = LatencySketch(
            methodVersion = FIXTURE_METHOD_VERSION,
            sampleCount = sampleCount.toLong(),
            sumMs = 12.0 * sampleCount,
            p50Ms = 10.0,
            p95Ms = 18.0,
            p99Ms = 22.0,
            errorBoundMs = 2.0,
            sketchDigest = IdentityHashing.sha256Hex("fixture-sketch\n$profileId\n$sampleCount"),
            evidenceLabel = EvidenceLabel.REPORTED,
        )
        return MeasurementMetrics(
            loadTimeMs = 100.0 + (seed % 20),
            ttftMs = ttft,
            throughputTokensPerSec = tokPerSec,
            interTokenLatency = interToken,
            endToEndLatencyMs = e2e,
            peakResidentBytes = 64L * 1024L * 1024L,
            peakAcceleratorBytes = null,
            peakWorkspaceBytes = 8L * 1024L * 1024L,
            energyHintJoules = null,
            thermalEventCount = 0,
            errorCount = 0,
            evidenceLabel = EvidenceLabel.REPORTED,
            methodVersion = FIXTURE_METHOD_VERSION,
        )
    }
}
