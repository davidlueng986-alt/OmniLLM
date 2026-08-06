package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricCatalogAndRegistryTest {

    @Test
    fun catalogContainsAllTenMetricsFromSpec() {
        assertEquals(10, MetricCatalog.METRICS.size)
        assertNotNull(MetricCatalog.definitionOrNull("request.ttft_ms"))
        assertNotNull(MetricCatalog.definitionOrNull("job.progress"))
        assertNull(MetricCatalog.definitionOrNull("invented.metric"))
    }

    @Test
    fun metricIdsMatchWireNames() {
        for (id in MetricId.entries) {
            assertEquals(id, MetricId.requireFromWireName(id.wireName))
            assertEquals(id.wireName, MetricCatalog.definition(id).id.wireName)
        }
    }

    @Test
    fun countSumCannotClaimPercentile() {
        val acc = CountSumAccumulator().observe(10.0).observe(20.0)
        assertEquals(15.0, acc.averageOrNull()!!, 0.0)
        try {
            acc.percentileForbidden(95.0)
            throw AssertionError("expected percentile rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("count+sum cannot claim percentile"))
        }
    }

    @Test
    fun histogramPercentileRequiresMethodVersion() {
        val h = FixedBucketHistogram(
            methodVersion = DefaultLatencyBucketsMs.METHOD_VERSION,
            bucketUpperBounds = DefaultLatencyBucketsMs.BOUNDS,
        )
        // empty → null (UNKNOWN semantics, not 0)
        assertNull(h.percentileOrNull(50.0))
        h.observe(40.0)
        h.observe(80.0)
        h.observe(120.0)
        val p50 = h.percentileOrNull(50.0)
        assertNotNull(p50)
        assertTrue(p50!! >= 40.0)
        val snap = h.snapshot()
        assertEquals(DefaultLatencyBucketsMs.METHOD_VERSION, snap.methodVersion)
        assertNotNull(snap.percentileOrNull(95.0))
    }

    @Test
    fun forbiddenPromptDimensionRejected() {
        val reg = InMemoryMetricRegistry(clockWallMs = { 1_000L })
        val result = reg.record(
            id = MetricId.REQUEST_TTFT_MS,
            value = 12.0,
            evidenceLabel = EvidenceLabel.MEASURED,
            dimensions = mapOf("prompt" to "hello"),
        )
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun privatePathDimensionRejected() {
        val reg = InMemoryMetricRegistry(clockWallMs = { 1_000L })
        val result = reg.record(
            id = MetricId.REQUEST_TTFT_MS,
            value = 12.0,
            evidenceLabel = EvidenceLabel.MEASURED,
            dimensions = mapOf(
                "engineBuildId" to "eng-1",
                "backend" to "cpu",
                "modelRevisionId" to "rev",
                "deviceClass" to "phone",
            ).plus("engineBuildId" to "C:\\Users\\secret\\model.gguf"),
        )
        // engineBuildId value looks like Windows path → rejected
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun recordTtftAndSnapshotExposesEvidenceAndPercentiles() {
        val reg = InMemoryMetricRegistry(clockWallMs = { 2_000L })
        val dims = mapOf(
            "engineBuildId" to "eng-a",
            "modelRevisionId" to "a".repeat(64),
            "backend" to "cpu",
            "deviceClass" to "phone",
        )
        assertTrue(
            reg.record(
                MetricId.REQUEST_TTFT_MS,
                45.0,
                EvidenceLabel.MEASURED,
                dims,
            ) is OmniResult.Ok,
        )
        assertTrue(
            reg.record(
                MetricId.REQUEST_TTFT_MS,
                120.0,
                EvidenceLabel.MEASURED,
                dims,
            ) is OmniResult.Ok,
        )

        val snap = reg.snapshot(includeDetailRestricted = true)
        assertTrue(snap.snapshotVersion > 0L)
        assertTrue(snap.samples.isNotEmpty())
        for (s in snap.samples) {
            assertNotNull(s.evidenceLabel)
            assertTrue(s.sampledAtEpochMs >= 0L)
        }
        val p95 = snap.samples.firstOrNull { it.dimensions["stat"] == "p95" }
        assertNotNull(p95)
        assertEquals(DefaultLatencyBucketsMs.METHOD_VERSION, p95!!.methodVersion)
    }

    @Test
    fun summaryExcludesRestrictedMetrics() {
        val reg = InMemoryMetricRegistry(clockWallMs = { 3_000L })
        reg.record(
            MetricId.REQUEST_QUEUE_MS,
            30.0,
            EvidenceLabel.MEASURED,
            mapOf("principalClass" to "local", "operationKind" to "chat"),
        )
        reg.record(
            MetricId.REQUEST_ERROR_COUNT,
            1.0,
            EvidenceLabel.MEASURED,
            mapOf("errorCode" to "RATE_LIMITED", "phase" to "execute"),
        )

        val summary = reg.snapshot(
            privacyFilter = setOf(MetricPrivacy.OPERATIONAL),
            includeDetailRestricted = false,
        )
        assertFalse(summary.samples.any { it.name == "request.queue_ms" })
        assertTrue(summary.samples.any { it.name == "request.error_count" })
    }

    @Test
    fun reportedWithoutSourceFails() {
        val reg = InMemoryMetricRegistry()
        val r = reg.record(
            MetricId.RESOURCE_ALLOCATED_BYTES,
            1024.0,
            EvidenceLabel.REPORTED,
            mapOf("resourceDimension" to "cpuAnonBytes", "ownerKind" to "session"),
            source = null,
        )
        assertTrue(r is OmniResult.Err)
    }

    @Test
    fun gaugeAndLastRecordedWithEvidence() {
        val reg = InMemoryMetricRegistry(clockWallMs = { 9_000L })
        reg.record(
            MetricId.RESOURCE_RESERVED_BYTES,
            4096.0,
            EvidenceLabel.MEASURED,
            mapOf("resourceDimension" to "cpuAnonBytes", "ownerKind" to "request"),
        )
        reg.record(
            MetricId.ENGINE_HEALTH,
            1.0,
            EvidenceLabel.MEASURED,
            mapOf("engineBuildId" to "eng", "backend" to "gpu"),
        )
        val g = reg.gaugeSample(
            MetricId.RESOURCE_RESERVED_BYTES,
            mapOf("resourceDimension" to "cpuAnonBytes", "ownerKind" to "request"),
        )
        assertNotNull(g)
        assertEquals(EvidenceLabel.MEASURED, g!!.evidenceLabel)
        assertEquals(9_000L, g.sampledAtEpochMs)
    }
}
