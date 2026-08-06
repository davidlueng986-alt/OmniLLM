package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.benchmark.domain.BenchmarkScenarioTemplate
import com.omnillm.features.benchmark.domain.FixtureMeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementProfileFactory
import com.omnillm.runtime.observability.MetricClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureMetricsAndProfileFactoryTest {

    @Test
    fun fixture_metrics_are_measurement_class_reported() {
        val profileId = "a".repeat(64)
        val m = FixtureMeasurementMetrics.forProfile(profileId, sampleCount = 3)
        assertEquals(MetricClass.MEASUREMENT, m.metricClass())
        assertEquals(EvidenceLabel.REPORTED, m.evidenceLabel)
        assertEquals(FixtureMeasurementMetrics.FIXTURE_METHOD_VERSION, m.methodVersion)
        assertTrue(m.ttftMs != null && m.ttftMs!! > 0)
    }

    @Test
    fun profile_factory_changes_id_when_dimension_changes() {
        val host = MeasurementProfileFactory.HostDimensions(
            engineBuildId = "engine:llama:test",
            backend = "cpu",
            modelRevisionId = "b".repeat(64),
            deviceExecutionFingerprint = "dev-fp-1",
        )
        val a = MeasurementProfileFactory.fromTemplate(BenchmarkScenarioTemplate.QUICK_SMOKE, host)
        val b = MeasurementProfileFactory.fromTemplate(
            BenchmarkScenarioTemplate.QUICK_SMOKE,
            host.copy(threads = 8),
        )
        assertNotEquals(a.profileId(), b.profileId())
        assertEquals(a.sampleCount, BenchmarkScenarioTemplate.QUICK_SMOKE.defaultSampleCount)
    }
}
