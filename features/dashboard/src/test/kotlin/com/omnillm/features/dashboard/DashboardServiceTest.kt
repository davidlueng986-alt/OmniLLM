package com.omnillm.features.dashboard

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.dashboard.api.DashboardUiPhase
import com.omnillm.features.dashboard.ports.ActiveRequestFact
import com.omnillm.features.dashboard.ports.DashboardFeaturePorts
import com.omnillm.features.dashboard.ports.GovernorResourceAdapter
import com.omnillm.features.dashboard.ports.InMemoryDashboardRequestPort
import com.omnillm.features.dashboard.ports.ObservabilityDashboardAdapter
import com.omnillm.features.dashboard.usecase.DashboardService
import com.omnillm.features.dashboard.viewmodel.DashboardViewModel
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.governor.ResourceLedger
import com.omnillm.runtime.observability.EvidenceSemantics
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthSubjectKind
import com.omnillm.runtime.observability.MetricId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-DASHBOARD happy path: health + resources + traces + evidence + UI phases.
 */
class DashboardServiceTest {

    private var now = 1_700_000_100_000L
    private lateinit var facade: com.omnillm.runtime.observability.ObservabilityFacade
    private lateinit var requests: InMemoryDashboardRequestPort
    private lateinit var api: DashboardService

    @Before
    fun setUp() {
        facade = ObservabilityModule.createFacade(clockWallMs = { now })
        facade.health.setRuntimeState("READY")
        facade.health.report(
            kind = HealthSubjectKind.SERVICE,
            subjectId = "omnillm",
            level = HealthLevel.HEALTHY,
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        facade.recordMetric(
            id = MetricId.REQUEST_TTFT_MS,
            value = 120.0,
            evidenceLabel = EvidenceLabel.MEASURED,
            dimensions = mapOf(
                "engineBuildId" to "eng-1",
                "modelRevisionId" to "rev-1",
                "backend" to "cpu",
                "deviceClass" to "phone",
            ),
        )
        requests = InMemoryDashboardRequestPort()
        val capacity = ResourceVector(cpuAnonBytes = 10_000L, nativeThreads = 32L)
        val free = ResourceVector(cpuAnonBytes = 8_000L, nativeThreads = 28L)
        val reserved = ResourceVector(cpuAnonBytes = 1_000L, nativeThreads = 2L)
        val allocated = ResourceVector(cpuAnonBytes = 1_000L, nativeThreads = 2L)
        val ledger = ResourceLedger(
            capacity = capacity,
            reserved = reserved,
            allocated = allocated,
            free = free,
            safetyMargin = ResourceVector(cpuAnonBytes = 500L),
        )
        val adapter = ObservabilityDashboardAdapter(facade) { emptyList() }
        api = DashboardService(
            DashboardFeaturePorts(
                health = adapter,
                metrics = adapter,
                traces = adapter,
                resources = GovernorResourceAdapter(
                    snapshot = { ledger },
                    clockWallMs = { now },
                ),
                requests = requests,
                clockWallMs = { now },
            ),
        )
    }

    @Test
    fun getSnapshot_includesHealthResourcesPerformanceEvidence() {
        val snap = api.getSnapshot() as OmniResult.Ok
        val v = snap.value
        assertEquals("READY", v.health.runtimeState)
        assertEquals(HealthLevel.HEALTHY, v.health.overallLevel)
        assertNotNull(v.resources)
        assertTrue(v.resources!!.conservationOk)
        assertNotNull(v.performance.ttftMs)
        // Histogram rollups from MetricRegistry use LAST_SAMPLED + methodVersion
        // (operational avg/percentile — not a MEASURED benchmark profile).
        assertEquals(EvidenceLabel.LAST_SAMPLED, v.performance.ttftMs!!.evidenceLabel)
        assertTrue(v.performance.ttftMs!!.allowsNumericDisplay)
        assertTrue(v.metrics.any { it.name == MetricId.REQUEST_TTFT_MS.wireName })
        assertNotNull(v.performance.ttftMs!!.displayValue)
        // Resource gauges retain MEASURED from Governor adapter.
        val reserved = v.resources!!.dimensions.first { it.dimension == "cpuAnonBytes" }.reserved
        assertEquals(EvidenceLabel.MEASURED, reserved.evidenceLabel)
        assertTrue(EvidenceSemantics.allowsNumericDisplay(EvidenceLabel.MEASURED))
    }

    @Test
    fun unknownMetric_inSnapshot_neverNumeric() {
        // Simulate cross-UID unmeasurable RSS: record with UNKNOWN is rejected by registry
        // for non-finite? We project unknownMetric helper path via empty series → no sample.
        // Direct projection covered in DashboardProjectionTest; here ensure absence ≠ 0 gauge.
        val snap = (api.getSnapshot() as OmniResult.Ok).value
        val unknownNamed = snap.metrics.none {
            it.evidenceLabel == EvidenceLabel.UNKNOWN && it.displayValue == 0.0
        }
        assertTrue(unknownNamed)
    }

    @Test
    fun viewModel_refresh_readyPhase() {
        val vm = DashboardFeatureModule.createViewModel(api)
        assertEquals(DashboardUiPhase.EMPTY, vm.state.phase)
        vm.refresh()
        assertEquals(DashboardUiPhase.READY, vm.state.phase)
        assertNotNull(vm.state.snapshot)
        assertNull(vm.state.lastError)
        assertFalse(vm.state.loading)
    }

    @Test
    fun viewModel_degradedWhenRuntimeDegraded() {
        facade.health.setRuntimeState("DEGRADED")
        facade.health.report(
            kind = HealthSubjectKind.ENGINE_MODULE,
            subjectId = "eng-1",
            level = HealthLevel.DEGRADED,
            reasonCodes = listOf("BACKEND_FALLBACK"),
            evidenceLabel = EvidenceLabel.MEASURED,
            affectedCapabilities = listOf("TEXT_GENERATION"),
            recommendedActions = listOf("view-reason"),
        )
        val vm = DashboardFeatureModule.createViewModel(api)
        vm.refresh()
        assertEquals(DashboardUiPhase.DEGRADED, vm.state.phase)
        assertTrue(vm.state.snapshot!!.actions.any { it.reasonCode == "BACKEND_FALLBACK" })
    }

    @Test
    fun createApi_fromFacade_works() {
        val fromFacade = DashboardFeatureModule.createApi(facade)
        val snap = fromFacade.getSnapshot() as OmniResult.Ok
        assertEquals("READY", snap.value.health.runtimeState)
    }
}
