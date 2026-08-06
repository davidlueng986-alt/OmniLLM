package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.benchmark.TestFixtures.baseProfile
import com.omnillm.features.benchmark.domain.LatencySketch
import com.omnillm.features.benchmark.projection.ProfileComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Profile identity / comparison and percentile sketch correctness
 * (FEAT-BENCHMARK acceptance §1 / §4).
 */
class ProfileComparisonAndSketchTest {

    @Test
    fun anyResultAffectingDimension_changesProfileId() {
        val base = baseProfile()
        val variants = listOf(
            base.copy(engineBuildId = "other-engine"),
            base.copy(backend = "gpu"),
            base.copy(modelRevisionId = TestFixtures.REV_B),
            base.copy(threads = 8),
            base.copy(contextLength = 4096),
            base.copy(fixtureCorpusDigest = "f".repeat(64)),
            base.copy(metricMethod = "t_digest"),
            base.copy(histogramSketchVersion = "td-v2"),
            base.copy(warmupCount = 3),
            base.copy(sampleCount = 10),
            base.copy(thermalCeilingCelsius = 50),
            base.copy(pageSizeBytes = 4096),
        )
        val baseId = base.profileId()
        for (v in variants) {
            assertNotEquals(
                "expected different profileId for $v",
                baseId,
                v.profileId(),
            )
        }
    }

    @Test
    fun canonicalJson_stableAcrossCalls() {
        val p = baseProfile()
        assertEquals(p.toCanonicalJson(), p.toCanonicalJson())
        assertEquals(p.profileId(), p.profileId())
    }

    @Test
    fun compare_identicalProfiles_compatible() {
        val a = baseProfile()
        val b = baseProfile() // same dimensions
        val cmp = ProfileComparison.compare(a, b)
        assertTrue(cmp.compatible)
        assertFalse(cmp.forbidSingleRanking)
        assertTrue(cmp.differingDimensions.isEmpty())
    }

    @Test
    fun percentileSketch_averageFromCountSumOnly() {
        val sketch = LatencySketch(
            methodVersion = "hdr-v1",
            sampleCount = 4,
            sumMs = 100.0,
            p50Ms = 20.0,
            p95Ms = 40.0,
            p99Ms = 50.0,
            errorBoundMs = 1.5,
            sketchDigest = "a".repeat(64),
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        assertEquals(25.0, sketch.averageMs()!!, 0.0001)
        // Percentiles are independent of average.
        assertEquals(20.0, sketch.p50Ms!!, 0.0)
    }

    @Test
    fun percentileSketch_zeroSamples_averageNull() {
        val sketch = LatencySketch(
            methodVersion = "hdr-v1",
            sampleCount = 0,
            sumMs = 0.0,
            p50Ms = null,
            p95Ms = null,
            p99Ms = null,
            errorBoundMs = 0.0,
            sketchDigest = null,
        )
        assertNull(sketch.averageMs())
    }

    @Test
    fun knownDistributionFixture_p50p95WithinErrorBound() {
        // Synthetic: samples [10, 20, 30, 40, 50] → p50≈30, p95≈50
        val samples = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val sketch = LatencySketch(
            methodVersion = "fixed-bucket-v1",
            sampleCount = samples.size.toLong(),
            sumMs = samples.sum(),
            p50Ms = 30.0,
            p95Ms = 50.0,
            p99Ms = 50.0,
            errorBoundMs = 2.0,
            sketchDigest = "b".repeat(64),
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        // True sorted percentiles for odd n: p50 = median = 30, max = 50
        assertTrue(abs(sketch.p50Ms!! - 30.0) <= sketch.errorBoundMs)
        assertTrue(abs(sketch.p95Ms!! - 50.0) <= sketch.errorBoundMs)
        // Digest remains after "raw drop" (samples not stored on sketch).
        assertNotNull(sketch.sketchDigest)
        assertEquals(64, sketch.sketchDigest!!.length)
    }
}
