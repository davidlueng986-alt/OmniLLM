package com.omnillm.features.dashboard

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.features.dashboard.api.DashboardUiPhase
import com.omnillm.features.dashboard.ports.ActiveRequestFact
import com.omnillm.features.dashboard.ports.LedgerEvidence
import com.omnillm.features.dashboard.projection.DashboardProjection
import com.omnillm.runtime.governor.ResourceLedger
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthSubject
import com.omnillm.runtime.observability.HealthSubjectKind
import com.omnillm.runtime.observability.HealthView
import com.omnillm.runtime.observability.MetricSample
import com.omnillm.runtime.observability.MetricSnapshot
import com.omnillm.runtime.observability.RequestTrace
import com.omnillm.runtime.observability.ServiceHealthSnapshot
import com.omnillm.runtime.observability.TraceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-DASHBOARD pure projection rules:
 * evidence labels, UNKNOWN ≠ 0, empty/loading/degraded, conservation, actions.
 */
class DashboardProjectionTest {

    private val now = 1_700_000_000_000L

    @Test
    fun measuredMetric_allowsNumericDisplay() {
        val sample = MetricSample(
            name = "request.ttft_ms",
            value = 42.0,
            unit = "ms",
            evidenceLabel = EvidenceLabel.MEASURED,
            sampledAtEpochMs = now - 100,
        )
        val ui = DashboardProjection.projectMetric(sample, now)
        assertTrue(ui.allowsNumericDisplay)
        assertEquals(42.0, ui.displayValue!!, 0.0)
        assertEquals(EvidenceLabel.MEASURED, ui.evidenceLabel)
        assertEquals(100L, ui.ageMs)
    }

    @Test
    fun unknownMetric_neverShowsZero() {
        val ui = DashboardProjection.unknownMetric(
            name = "resource.memory_rss",
            unit = "bytes",
            sampledAtEpochMs = now - 5_000,
            nowEpochMs = now,
        )
        assertEquals(EvidenceLabel.UNKNOWN, ui.evidenceLabel)
        assertFalse(ui.allowsNumericDisplay)
        assertNull(ui.displayValue)
        assertEquals(5_000L, ui.ageMs)
    }

    @Test
    fun unknownEvidenceLabel_onSample_hidesNumeric() {
        val sample = MetricSample(
            name = "resource.memory_sample_age_ms",
            value = 0.0, // producer may still emit a raw number
            unit = "ms",
            evidenceLabel = EvidenceLabel.UNKNOWN,
            sampledAtEpochMs = now,
        )
        val ui = DashboardProjection.projectMetric(sample, now)
        assertFalse(ui.allowsNumericDisplay)
        assertNull(ui.displayValue)
    }

    @Test
    fun reportedMetric_requiresSource() {
        val sample = MetricSample(
            name = "resource.allocated_bytes",
            value = 1024.0,
            unit = "bytes",
            evidenceLabel = EvidenceLabel.REPORTED,
            sampledAtEpochMs = now,
            source = "isolated-worker",
            dimensions = mapOf("resourceDimension" to "cpuAnonBytes", "ownerKind" to "worker"),
        )
        val ui = DashboardProjection.projectMetric(sample, now)
        assertEquals("isolated-worker", ui.source)
        assertEquals(EvidenceLabel.REPORTED, ui.evidenceLabel)
    }

    @Test
    fun resourceAccounting_conservationOk() {
        val capacity = ResourceVector(cpuAnonBytes = 1_000L)
        val reserved = ResourceVector(cpuAnonBytes = 200L)
        val allocated = ResourceVector(cpuAnonBytes = 300L)
        val free = ResourceVector(cpuAnonBytes = 500L)
        val safety = ResourceVector(cpuAnonBytes = 100L)
        val ledger = ResourceLedger(
            capacity = capacity,
            reserved = reserved,
            allocated = allocated,
            free = free,
            safetyMargin = safety,
        )
        assertTrue(ledger.checkInvariants() is OmniResult.Ok)

        val ui = DashboardProjection.projectResources(
            ledger = ledger,
            evidence = LedgerEvidence(EvidenceLabel.MEASURED, now, "resource-governor"),
            nowEpochMs = now,
        )
        assertNotNull(ui)
        assertTrue(ui!!.conservationOk)
        val cpu = ui.dimensions.first { it.dimension == "cpuAnonBytes" }
        assertEquals(1_000.0, cpu.capacity.displayValue!!, 0.0)
        assertEquals(200.0, cpu.reserved.displayValue!!, 0.0)
        assertEquals(300.0, cpu.allocated.displayValue!!, 0.0)
        assertEquals(EvidenceLabel.MEASURED, cpu.reserved.evidenceLabel)
    }

    @Test
    fun healthDegraded_projectsActions() {
        val subject = HealthView(
            subject = HealthSubject(HealthSubjectKind.THERMAL, "device-1"),
            level = HealthLevel.DEGRADED,
            reasonCodes = listOf("THERMAL_THROTTLE"),
            sinceEpochMs = now - 10_000,
            sampledAtEpochMs = now,
            evidenceLabel = EvidenceLabel.MEASURED,
            affectedCapabilities = listOf("TEXT_GENERATION"),
            recommendedActions = listOf("wait-thermal", "reduce-context"),
        )
        val health = ServiceHealthSnapshot(
            runtimeState = "DEGRADED",
            resourceVersion = 3L,
            overallLevel = HealthLevel.DEGRADED,
            degradedReasons = listOf("THERMAL_THROTTLE"),
            subjects = listOf(subject),
            sampledAtEpochMs = now,
        )
        val healthUi = DashboardProjection.projectHealth(health, now)
        assertEquals("DEGRADED", healthUi.runtimeState)
        assertEquals(HealthLevel.DEGRADED, healthUi.overallLevel)

        val actions = DashboardProjection.projectActions(healthUi, null, emptyList())
        assertTrue(actions.any { it.reasonCode == "THERMAL_THROTTLE" })
        assertTrue(
            actions.first { it.reasonCode == "THERMAL_THROTTLE" }
                .recommendedActionKeys.contains("wait-thermal"),
        )
    }

    @Test
    fun requestRow_cancelAllowed_whileStreaming() {
        val row = DashboardProjection.projectRequest(
            ActiveRequestFact(
                requestId = "11111111-1111-1111-1111-111111111111",
                phase = "STREAMING",
                cancelAllowed = true,
            ),
        )
        assertEquals("request.generating", row.labelKey)
        assertTrue(row.cancelAllowed)
    }

    @Test
    fun traceSummary_redactedProjection() {
        val trace = RequestTrace(
            correlationId = "corr-1",
            causationId = null,
            principalId = "LOCAL_UI",
            requestId = "11111111-1111-1111-1111-111111111111",
            jobId = null,
            policyVersion = "v1",
            engineBuildId = "engine-a",
            modelRevisionId = null,
            runtimeEpoch = 1L,
            events = listOf(
                TraceEvent(
                    sequence = 0L,
                    name = "accepted",
                    phase = "CLAIMED",
                    monotonicNs = 1L,
                    wallEpochMs = now,
                ),
                TraceEvent(
                    sequence = 1L,
                    name = "first_token",
                    phase = "STREAMING",
                    monotonicNs = 2L,
                    wallEpochMs = now + 1,
                ),
            ),
        )
        val ui = DashboardProjection.projectTrace(trace)
        assertEquals("corr-1", ui.correlationId)
        assertEquals(2, ui.eventCount)
        assertEquals("first_token", ui.lastEventName)
        assertEquals("STREAMING", ui.lastPhase)
    }

    @Test
    fun uiPhase_empty_loading_ready_degraded_error() {
        assertEquals(
            DashboardUiPhase.EMPTY,
            DashboardProjection.resolveUiPhase(false, false, false, null),
        )
        assertEquals(
            DashboardUiPhase.LOADING,
            DashboardProjection.resolveUiPhase(false, true, false, null),
        )
        assertEquals(
            DashboardUiPhase.ERROR,
            DashboardProjection.resolveUiPhase(false, false, true, null),
        )

        val readySnap = DashboardProjection.projectSnapshot(
            snapshotVersion = 1L,
            health = ServiceHealthSnapshot(
                runtimeState = "READY",
                resourceVersion = 1L,
                overallLevel = HealthLevel.HEALTHY,
                degradedReasons = emptyList(),
                subjects = listOf(
                    HealthView(
                        subject = HealthSubject(HealthSubjectKind.SERVICE, "omnillm"),
                        level = HealthLevel.HEALTHY,
                        sinceEpochMs = now,
                        sampledAtEpochMs = now,
                        evidenceLabel = EvidenceLabel.MEASURED,
                    ),
                ),
                sampledAtEpochMs = now,
            ),
            metricSnapshot = MetricSnapshot(1L, emptyList()),
            ledger = null,
            ledgerEvidence = LedgerEvidence(EvidenceLabel.MEASURED, now),
            requests = emptyList(),
            traces = emptyList(),
            nowEpochMs = now,
        )
        assertEquals(
            DashboardUiPhase.READY,
            DashboardProjection.resolveUiPhase(true, false, false, readySnap),
        )

        val degradedSnap = readySnap.copy(
            health = readySnap.health.copy(
                runtimeState = "DEGRADED",
                overallLevel = HealthLevel.DEGRADED,
            ),
        )
        assertEquals(
            DashboardUiPhase.DEGRADED,
            DashboardProjection.resolveUiPhase(true, false, false, degradedSnap),
        )
    }
}
