package com.omnillm.interfaces.http

import com.omnillm.core.canonical.generated.EvidenceLabel
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-08 contract test: MetricSnapshot uses snapshot_version/samples and
 * MetricSample carries name/value/unit/evidence_label/sampled_at — the old
 * resource_version/series/id names silently dropped data.
 */
class WireMetricsContractTest {

    @Test
    fun metricSnapshot_wireUsesSnapshotVersionAndSamples() {
        val dto = MetricSnapshotDto(
            snapshotVersion = 2,
            samples = listOf(
                MetricSampleDto(
                    name = "request.ttft_ms",
                    value = 12.5,
                    unit = "ms",
                    evidenceLabel = EvidenceLabel.LAST_SAMPLED,
                    sampledAt = "2026-08-04T00:00:00Z",
                    dimensions = mapOf("stat" to "avg"),
                ),
            ),
        )
        val json = HttpJson.codec.encodeToString(MetricSnapshotDto.serializer(), dto)
        assertTrue("snapshot_version required by MetricSnapshot: $json", json.contains("\"snapshot_version\":2"))
        assertTrue("samples required by MetricSnapshot: $json", json.contains("\"samples\":"))
        assertTrue("evidence_label required by MetricSample: $json", json.contains("\"evidence_label\":\"LAST_SAMPLED\""))
        assertTrue("unit required by MetricSample: $json", json.contains("\"unit\":\"ms\""))
        assertTrue("legacy series must not leak: $json", !json.contains("series"))
        assertTrue("legacy resource_version must not leak: $json", !json.contains("resource_version"))
        assertTrue("legacy sample id must not leak: $json", !json.contains("\"id\":"))
    }
}
