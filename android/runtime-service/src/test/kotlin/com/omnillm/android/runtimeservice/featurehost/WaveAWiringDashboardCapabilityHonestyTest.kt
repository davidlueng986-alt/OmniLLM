package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.orchestrator.CapabilityLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-04 wiring regression: WaveAWiring must hand the dashboard an honest
 * capability projection — engine cells come from the plane's real
 * CapabilityLookup (never fabricated SUPPORTED), observability cells from the
 * attached software stack.
 */
class WaveAWiringDashboardCapabilityHonestyTest {

    private fun wireWaveA(
        capabilityLookupOverride: CapabilityLookup? = null,
    ): WaveAFeaturePacks {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val admin = AdminModule.createService(
            commandLedger = ledgers.commandLedger,
            jobManager = jobs,
            policyManager = policy,
            runtimeStateProvider = { "READY" },
            lanStateProvider = { "DISABLED" },
            clockMs = { 1_700_000_000_000L },
        )
        return WaveAWiring.bootstrapForTest(
            adminApi = admin,
            jobManager = jobs,
            requestRegistry = ledgers.requestRegistry,
            observability = observability,
            capabilityLookupOverride = capabilityLookupOverride,
        )
    }

    @Test
    fun dashboard_engineCells_neverFabricatedSupported() {
        val waveA = wireWaveA()
        val cells = waveA.dashboard.negotiate(
            listOf(
                CapabilityId.TEXT_GENERATION,
                CapabilityId.VISION_INPUT,
                CapabilityId.EMBEDDING,
                CapabilityId.STRUCTURED_OUTPUT,
                CapabilityId.TOOL_CALLING,
            ),
        ).cells.associateBy { it.capabilityId }
        // No engine attached in bootstrap wiring → honest UNKNOWN, never SUPPORTED.
        for (engineCell in listOf("TEXT_GENERATION", "VISION_INPUT", "EMBEDDING", "STRUCTURED_OUTPUT", "TOOL_CALLING")) {
            assertNotEquals(
                "dashboard must not invent SUPPORTED for $engineCell (C-04)",
                CapabilityState.SUPPORTED,
                cells.getValue(engineCell).state,
            )
        }
        assertEquals(CapabilityState.UNKNOWN, cells.getValue("TEXT_GENERATION").state)
        assertEquals(CapabilityState.UNKNOWN, cells.getValue("VISION_INPUT").state)
    }

    @Test
    fun dashboard_observabilityCells_areSoftwareSupported() {
        val waveA = wireWaveA()
        val cells = waveA.dashboard.negotiate(
            listOf(
                CapabilityId.SERVICE_HEALTH,
                CapabilityId.ENGINE_HEALTH,
                CapabilityId.MODEL_HEALTH,
                CapabilityId.REQUEST_TRACE,
                CapabilityId.RESOURCE_ACCOUNTING,
                CapabilityId.PERFORMANCE_MEASUREMENT,
                CapabilityId.EVIDENCE_LABELING,
                CapabilityId.LOCAL_UI_INTERFACE,
                CapabilityId.JOB_PROGRESS,
            ),
        ).cells.associateBy { it.capabilityId }
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("SERVICE_HEALTH").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("ENGINE_HEALTH").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("MODEL_HEALTH").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("REQUEST_TRACE").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("RESOURCE_ACCOUNTING").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("PERFORMANCE_MEASUREMENT").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("EVIDENCE_LABELING").state)
        assertEquals(CapabilityState.SUPPORTED, cells.getValue("LOCAL_UI_INTERFACE").state)
        assertEquals(CapabilityState.CONDITIONAL, cells.getValue("JOB_PROGRESS").state)
    }

    @Test
    fun dashboard_reflectsPlaneLookupCeiling_neverWidensToSupported() {
        // Dev-override plane (the honest ceiling EngineExecuteBinding ever
        // reports): the dashboard must reflect it verbatim — CONDITIONAL, never
        // SUPPORTED.
        val waveA = wireWaveA(
            capabilityLookupOverride = CapabilityLookup { _, _ -> CapabilityState.CONDITIONAL },
        )
        val cells = waveA.dashboard.negotiate(
            listOf(CapabilityId.TEXT_GENERATION, CapabilityId.VISION_INPUT),
        ).cells.associateBy { it.capabilityId }
        assertEquals(CapabilityState.CONDITIONAL, cells.getValue("TEXT_GENERATION").state)
        assertEquals(CapabilityState.CONDITIONAL, cells.getValue("VISION_INPUT").state)
        assertNotEquals(
            "plane CONDITIONAL must never be widened to SUPPORTED (C-04)",
            CapabilityState.SUPPORTED,
            cells.getValue("TEXT_GENERATION").state,
        )
    }

    @Test
    fun dashboard_snapshot_stillServedWithHonestNegotiation() {
        val waveA = wireWaveA()
        val snap = waveA.dashboard.getSnapshot()
        assertTrue("dashboard snapshot must remain served (C-04)", snap is OmniResult.Ok)
    }
}
