package com.omnillm.features.dashboard

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.dashboard.ports.ActiveRequestFact
import com.omnillm.features.dashboard.ports.DashboardFeaturePorts
import com.omnillm.features.dashboard.ports.InMemoryDashboardRequestPort
import com.omnillm.features.dashboard.ports.MapCapabilityPort
import com.omnillm.features.dashboard.ports.ObservabilityDashboardAdapter
import com.omnillm.features.dashboard.usecase.DashboardService
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthSubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-DASHBOARD failure paths:
 * - Capability negotiation UNSUPPORTED / UNKNOWN fail closed (INV-018)
 * - Cancel with client-generated requestId (ADR-004/005)
 * - Cancel when CANCELLATION capability unsupported
 */
class DashboardCapabilityCancelTest {

    private var now = 1_700_000_200_000L
    private lateinit var facade: com.omnillm.runtime.observability.ObservabilityFacade
    private lateinit var requests: InMemoryDashboardRequestPort

    private val clientRequestId = RequestId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

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
        requests = InMemoryDashboardRequestPort()
        requests.put(
            ActiveRequestFact(
                requestId = clientRequestId.value,
                correlationId = "corr-cancel-1",
                principalId = "LOCAL_UI",
                phase = "STREAMING",
                engineBuildId = "eng-1",
                cancelAllowed = true,
            ),
        )
    }

    private fun service(caps: MapCapabilityPort): DashboardService {
        val adapter = ObservabilityDashboardAdapter(facade) { listOf("corr-cancel-1") }
        return DashboardService(
            DashboardFeaturePorts(
                health = adapter,
                metrics = adapter,
                traces = adapter,
                requests = requests,
                capabilities = caps,
                clockWallMs = { now },
            ),
        )
    }

    @Test
    fun negotiate_unsupported_blocksAllSupported() {
        val caps = MapCapabilityPort(
            mapOf(
                CapabilityId.SERVICE_HEALTH to CapabilityState.SUPPORTED,
                CapabilityId.REQUEST_TRACE to CapabilityState.UNSUPPORTED,
            ),
            default = CapabilityState.SUPPORTED,
        )
        val api = service(caps)
        val result = api.negotiate(
            listOf(CapabilityId.SERVICE_HEALTH, CapabilityId.REQUEST_TRACE),
        )
        assertFalse(result.allSupported)
        assertEquals(listOf("REQUEST_TRACE"), result.unsupportedIds)
        assertEquals(listOf("REQUEST_TRACE"), result.blockingIds)
    }

    @Test
    fun negotiate_unknown_isBlocking_failClosed() {
        val caps = MapCapabilityPort(
            mapOf(CapabilityId.SERVICE_HEALTH to CapabilityState.SUPPORTED),
            default = CapabilityState.UNKNOWN,
        )
        val api = service(caps)
        val result = api.negotiate(
            listOf(CapabilityId.SERVICE_HEALTH, CapabilityId.RESOURCE_ACCOUNTING),
        )
        assertFalse(result.allSupported)
        assertTrue(result.blockingIds.contains("RESOURCE_ACCOUNTING"))
        val cell = result.cells.first { it.capabilityId == "RESOURCE_ACCOUNTING" }
        assertEquals(CapabilityState.UNKNOWN, cell.state)
    }

    @Test
    fun getSnapshot_capabilityUnsupported_returnsError() {
        val caps = MapCapabilityPort(
            mapOf(CapabilityId.EVIDENCE_LABELING to CapabilityState.UNSUPPORTED),
            default = CapabilityState.SUPPORTED,
        )
        val api = service(caps)
        val snap = api.getSnapshot()
        assertTrue(snap is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (snap as OmniResult.Err).error.code)
        assertTrue(snap.error.details["unsupported"]!!.contains("EVIDENCE_LABELING"))
    }

    @Test
    fun getTrace_unsupported_failClosed() {
        val caps = MapCapabilityPort(
            mapOf(CapabilityId.REQUEST_TRACE to CapabilityState.UNSUPPORTED),
            default = CapabilityState.SUPPORTED,
        )
        val api = service(caps)
        val r = api.getTrace(correlationId = "corr-cancel-1")
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
    }

    @Test
    fun cancel_withClientGeneratedRequestId_reachesCancelled() {
        val caps = MapCapabilityPort(
            mapOf(CapabilityId.CANCELLATION to CapabilityState.SUPPORTED),
            default = CapabilityState.SUPPORTED,
        )
        val api = service(caps)
        val cancelled = api.cancelRequest(requestId = clientRequestId) as OmniResult.Ok
        assertEquals(clientRequestId.value, cancelled.value.requestId)
        assertEquals("STREAMING", cancelled.value.priorPhase)
        assertEquals("CANCELLED", cancelled.value.phase)
        assertTrue(cancelled.value.terminal)

        val queried = api.queryRequest(requestId = clientRequestId) as OmniResult.Ok
        assertEquals("CANCELLED", queried.value.phase)
        assertTrue(queried.value.terminal)
    }

    @Test
    fun cancel_whenCancellationUnsupported_failsClosed() {
        val caps = MapCapabilityPort(
            mapOf(CapabilityId.CANCELLATION to CapabilityState.UNSUPPORTED),
            default = CapabilityState.SUPPORTED,
        )
        val api = service(caps)
        val r = api.cancelRequest(requestId = clientRequestId)
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)

        // Request remains active (no mutation when capability blocked).
        val still = api.queryRequest(requestId = clientRequestId) as OmniResult.Ok
        assertEquals("STREAMING", still.value.phase)
    }

    @Test
    fun cancel_terminalRequest_stateConflict() {
        val caps = MapCapabilityPort(emptyMap(), default = CapabilityState.SUPPORTED)
        val api = service(caps)
        // First cancel succeeds
        assertTrue(api.cancelRequest(requestId = clientRequestId) is OmniResult.Ok)
        // Second cancel fails closed
        val second = api.cancelRequest(requestId = clientRequestId)
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (second as OmniResult.Err).error.code)
    }

    @Test
    fun viewModel_cancelRequest_updatesState() {
        val caps = MapCapabilityPort(emptyMap(), default = CapabilityState.SUPPORTED)
        val api = service(caps)
        val vm = DashboardFeatureModule.createViewModel(api)
        vm.refresh()
        assertTrue(vm.state.snapshot!!.requests.any { it.requestId == clientRequestId.value })

        val result = vm.cancelRequest(clientRequestId)
        assertTrue(result is OmniResult.Ok)
        assertEquals("CANCELLED", vm.state.lastCancel!!.phase)
        // After refresh, active list no longer includes cancelled
        assertFalse(
            vm.state.snapshot!!.requests.any {
                it.requestId == clientRequestId.value && it.phase == "STREAMING"
            },
        )
    }
}
