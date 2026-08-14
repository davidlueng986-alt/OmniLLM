package com.omnillm.features.dashboard

import com.omnillm.core.canonical.generated.CapabilityCatalog
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.features.dashboard.ports.DASHBOARD_SOFTWARE_CAPABILITIES
import com.omnillm.features.dashboard.ports.DashboardCapabilityPort
import com.omnillm.features.dashboard.ports.HonestDashboardCapabilityPort
import com.omnillm.features.dashboard.ports.RegistryBackedCapabilityPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * C-04 regression guard: the dashboard capability projection must never
 * fabricate SUPPORTED for capabilities the product does not provide.
 *
 * - Engine cells (TEXT_GENERATION / EMBEDDING / VISION_INPUT ...) come from
 *   the engine registry via CapabilityLookup — never invented.
 * - Observability cells are SUPPORTED only where the attached software stack
 *   truly provides them (health/metrics/trace via the observability registry).
 * - Every other cell fails closed to UNKNOWN (INV-018).
 */
class DashboardCapabilityHonestyTest {

    private val port: DashboardCapabilityPort = HonestDashboardCapabilityPort

    @Test
    fun engineCells_neverFabricateSupported() {
        assertNotEquals(
            "TEXT_GENERATION must not be SUPPORTED without engine evidence (C-04)",
            CapabilityState.SUPPORTED,
            port.state(CapabilityId.TEXT_GENERATION),
        )
        assertNotEquals(
            "VISION_INPUT (not implemented) must not be SUPPORTED (C-04)",
            CapabilityState.SUPPORTED,
            port.state(CapabilityId.VISION_INPUT),
        )
        assertNotEquals(
            "EMBEDDING must not be SUPPORTED without qualification (C-04)",
            CapabilityState.SUPPORTED,
            port.state(CapabilityId.EMBEDDING),
        )
        assertEquals(
            "engine cell without lookup fails closed to UNKNOWN",
            CapabilityState.UNKNOWN,
            port.state(CapabilityId.TEXT_GENERATION),
        )
    }

    @Test
    fun unknownCells_failClosed() {
        assertNotEquals(
            "DEVICE_DISCOVERY is not a dashboard software cell; must fail closed (C-04)",
            CapabilityState.SUPPORTED,
            port.state(CapabilityId.DEVICE_DISCOVERY),
        )
        assertNotEquals(
            "MODEL_ACQUISITION is not a dashboard software cell; must fail closed (C-04)",
            CapabilityState.SUPPORTED,
            port.state(CapabilityId.MODEL_ACQUISITION),
        )
        assertEquals(CapabilityState.UNKNOWN, port.state(CapabilityId.DEVICE_DISCOVERY))
    }

    @Test
    fun softwareCells_areReportedSupported() {
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.SERVICE_HEALTH))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.ENGINE_HEALTH))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.MODEL_HEALTH))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.REQUEST_TRACE))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.RESOURCE_ACCOUNTING))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.PERFORMANCE_MEASUREMENT))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.EVIDENCE_LABELING))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.LOCAL_UI_INTERFACE))
        assertEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.CAPABILITY_NEGOTIATION))
    }

    @Test
    fun jobProgress_isConditional_notFabricatedSupported() {
        // Metric slot defined in observability catalog but no producer feeds it
        // today → partial software path, never SUPPORTED.
        assertEquals(CapabilityState.CONDITIONAL, port.state(CapabilityId.JOB_PROGRESS))
    }

    @Test
    fun catalogSweep_neverBlanketSupported() {
        val invented = CapabilityCatalog.ALL
            .map { it.id }
            .filterNot { it in DASHBOARD_SOFTWARE_CAPABILITIES }
            .filter { port.state(it) == CapabilityState.SUPPORTED }
        assertEquals(
            "no capability outside the software-provided set may be SUPPORTED (C-04)",
            emptyList<CapabilityId>(),
            invented,
        )
    }

    @Test
    fun registryBacked_reflectsPlaneLookup_neverFabricates() {
        // Simulated plane lookup (dev-override posture — the honest ceiling
        // EngineExecuteBinding ever reports for engine cells).
        val plane: (CapabilityId) -> CapabilityState? = { cap ->
            when (cap) {
                CapabilityId.TEXT_GENERATION -> CapabilityState.CONDITIONAL
                else -> null
            }
        }
        val backed = RegistryBackedCapabilityPort(engineState = plane)
        assertEquals(CapabilityState.CONDITIONAL, backed.state(CapabilityId.TEXT_GENERATION))
        // Not-implemented cell stays fail-closed even with a bound engine.
        assertEquals(CapabilityState.UNKNOWN, backed.state(CapabilityId.VISION_INPUT))
        assertEquals(CapabilityState.SUPPORTED, backed.state(CapabilityId.SERVICE_HEALTH))
        // The lookup must never be overridden into SUPPORTED by the port.
        assertNotEquals(CapabilityState.SUPPORTED, backed.state(CapabilityId.TEXT_GENERATION))
    }

    @Test
    fun registryBacked_defaultsHonestWhenNoLookup() {
        val backed = RegistryBackedCapabilityPort()
        assertEquals(CapabilityState.UNKNOWN, backed.state(CapabilityId.TEXT_GENERATION))
        assertEquals(CapabilityState.UNKNOWN, backed.state(CapabilityId.EMBEDDING))
        assertEquals(CapabilityState.UNKNOWN, backed.state(CapabilityId.VISION_INPUT))
        assertEquals(CapabilityState.SUPPORTED, backed.state(CapabilityId.SERVICE_HEALTH))
    }
}
