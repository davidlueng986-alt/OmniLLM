package com.omnillm.ui.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.routing.api.RoutingUiPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-06 regression: production UiSession wiring (no-arg session construction +
 * Admin binder available) must attach the diagnostics / routing / content-report
 * ViewModels. Today the three screens render their disconnected-fallback state
 * because `UiSession` never receives these VMs from the binder listener.
 *
 * RED note: this test compiles against the intended production seam
 * `BinderAdminFeatureFactory.createFeatureViewModels(admin)` which is exactly
 * what `UiSession` will call when the Admin binder connects. It cannot compile
 * until the wiring exists — the missing wiring IS the defect.
 */
class ProductionViewModelBundleTest {

    private val revisionHex = "a".repeat(64)

    private fun fakeAdmin(): FakeOmniAdmin {
        val model = ai.omnillm.api.OmniModelInfo().apply {
            modelRevisionId = revisionHex
            displayName = "SmolLM-fixture"
            installationState = "READY"
        }
        val snapshot = ai.omnillm.api.OmniAdminSnapshot().apply {
            snapshotVersion = 7L
            runtimeState = "READY"
            lanState = "DISABLED"
            models = arrayOf(model)
            activeJobs = emptyArray()
            settings = ai.omnillm.api.OmniSettingsSnapshot().apply { resourceVersion = 3L }
        }
        return FakeOmniAdmin(snapshot)
    }

    @Test
    fun productionViewModelBundle_attachesDiagnosticsRoutingContentReport() {
        val vms = BinderAdminFeatureFactory.createFeatureViewModels(fakeAdmin())

        assertNotNull("diagnosticsVm must be attached in production (C-06)", vms.diagnostics)
        assertNotNull("routingVm must be attached in production (C-06)", vms.routing)
        assertNotNull("contentReportVm must be attached in production (C-06)", vms.contentReport)

        val diagnostics = vms.diagnostics
        runBlocking { diagnostics.refresh() }
        assertEquals(
            "diagnostics must render session data, not an invented error",
            DiagnosticUiPhases.EMPTY,
            diagnostics.uiState().uiPhase,
        )
        assertEquals(
            "diagnostics default actions still offer plan-export",
            listOf("plan-export"),
            diagnostics.uiState().actions,
        )
        val plan = diagnostics.planExport()
        assertTrue(plan is OmniResult.Ok)
        assertEquals(
            "pure plan must move the screen to PREVIEW",
            DiagnosticUiPhases.PREVIEW,
            diagnostics.uiState().uiPhase,
        )

        val routing = vms.routing
        routing.negotiate()
        assertTrue(
            "routing negotiation must be honest (READY on plane support, ERROR surfaced otherwise)",
            routing.state.phase == RoutingUiPhase.READY || routing.state.phase == RoutingUiPhase.ERROR,
        )
        assertNotNull("routing snapshot must be populated after negotiation", routing.state.snapshot)

        val contentReport = vms.contentReport
        runBlocking { contentReport.refresh() }
        assertEquals(
            "content-report snapshot must carry the real data-stream kind",
            ContentReportPolicy.DATA_STREAM_KIND,
            contentReport.uiState().dataStreamKind,
        )
        assertFalse("content-report stream must never be telemetry", contentReport.uiState().isTelemetryStream)
    }
}
