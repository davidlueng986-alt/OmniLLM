package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.benchmark.BenchmarkFeatureModule
import com.omnillm.features.contentreport.ContentReportModule
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.diagnostics.DiagnosticsModule
import com.omnillm.features.lan.LanFeatureModule
import com.omnillm.features.lan.domain.ContentReportVsTelemetry
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.routing.RoutingFeatureModule
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.tools.ToolsFeatureModule
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-B: each Feature Pack is reachable from control-plane bootstrap
 * (FeaturePackHost) and projects onto Admin/HTTP wiring surfaces.
 *
 * Pure JVM — no Robolectric / process attach required.
 */
class FeaturePackHostBootstrapTest {

    private fun bootstrapHost(): FeaturePackHost {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        return FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = policy,
            observability = observability,
            clockMs = { 1_700_000_000_000L },
        )
    }

    @Test
    fun bootstrap_attachesAllWaveBPacks() {
        val host = bootstrapHost()
        assertTrue(host.featureIds.containsAll(FeaturePackHost.WAVE_B_FEATURE_IDS))
        // Pure wave-B bootstrap omits wave-A unless injected (production attach adds it).
        assertEquals(FeaturePackHost.WAVE_B_FEATURE_IDS, host.featureIds.filter { it in FeaturePackHost.WAVE_B_FEATURE_IDS }.toSet())
        assertTrue(host.featureIds.contains(LanFeatureModule.FEATURE_ID))
        assertTrue(host.featureIds.contains(BenchmarkFeatureModule.FEATURE_ID))
        assertTrue(host.featureIds.contains(DiagnosticsModule.FEATURE_ID))
        assertTrue(host.featureIds.contains(RoutingFeatureModule.FEATURE_ID))
        assertTrue(host.featureIds.contains(ToolsFeatureModule.FEATURE_ID))
        assertTrue(host.featureIds.contains(ContentReportModule.FEATURE_ID))

        assertNotNull(host.lanApi)
        assertNotNull(host.benchmarkApi)
        assertNotNull(host.diagnosticsApi)
        assertNotNull(host.routingApi)
        assertNotNull(host.toolsApi)
        assertNotNull(host.contentReportApi)

        assertEquals(LanFeatureModule.MODULE_PATH, host.modulePaths().first { it.contains("lan") })
    }

    @Test
    fun lan_defaultsOff_andEnableStaysNonReadyWithoutTls() = runBlocking {
        val host = bootstrapHost()
        assertTrue(host.lanIsDefaultOff())
        assertFalse(LanServiceLifecyclePolicy.DEFAULT_ENABLED)
        assertEquals("DISABLED", host.lanHost.adminLanStateLabel())

        val snap = host.lanApi.snapshot()
        assertFalse(snap.defaultLanEnabled)

        val status = host.lanPorts.service.status()
        assertTrue(status is OmniResult.Ok)
        assertFalse((status as OmniResult.Ok).value.enabled)
        assertEquals("DISABLED", status.value.state)

        // Pairing fail-closed while default-off / not TLS-ready.
        val challenge = host.lanPorts.pairing.createChallenge(
            principal = LocalUiPrincipal.ID,
            spec = com.omnillm.features.lan.api.CreatePairingChallengeSpec(
                command = com.omnillm.features.lan.api.LanCommandIdentity(
                    commandId = "11111111-1111-1111-1111-111111111111",
                    idempotencyKey = "idem-lan-1",
                ),
                challengeId = "22222222-2222-2222-2222-222222222222",
            ),
        )
        assertTrue(challenge is OmniResult.Err)
    }

    @Test
    fun contentReport_isNotTelemetry() = runBlocking {
        val host = bootstrapHost()
        assertTrue(host.contentReportIsNotTelemetry())
        assertEquals(ContentReportPolicy.DATA_STREAM_KIND, "AI_CONTENT_REPORT")
        assertFalse(
            ContentReportPolicy.DATA_STREAM_KIND == ContentReportPolicy.TELEMETRY_STREAM_KIND,
        )
        assertFalse(ContentReportVsTelemetry.telemetryModeImpliesReportConsent("LOCAL_ONLY"))
        assertFalse(ContentReportVsTelemetry.lanEnabledImpliesTelemetryExport(true))

        val snap = host.contentReportApi.getSnapshot(LocalUiPrincipal.ID)
        assertTrue(snap is OmniResult.Ok)
        val ok = (snap as OmniResult.Ok).value
        assertEquals(ContentReportPolicy.DATA_STREAM_KIND, ok.dataStreamKind)
        assertFalse(ok.isTelemetryStream)
    }

    @Test
    fun routing_noSilentFallback_andOrchestratorFailClosed() = runBlocking {
        val host = bootstrapHost()
        val principal = LocalUiPrincipal.ID

        val negotiation = host.routingApi.negotiate(principal)
        // CODE-05: wave-B bootstrap has no Orchestrator attached, so the host
        // must never claim SUPPORTED — negotiation fails closed with
        // CAPABILITY_UNSUPPORTED instead of inventing an operable routing cell.
        assertTrue(negotiation is OmniResult.Err)
        assertEquals(
            OmniErrorCode.CAPABILITY_UNSUPPORTED,
            (negotiation as OmniResult.Err).error.code,
        )

        val minPlacement = PlacementClassLabels.PRIVILEGED_TRUSTED

        // ALLOW_LIST without allowlist is rejected (no silent cross-revision).
        val invalid = host.routingApi.validatePreference(
            principal,
            RoutingPreferenceView(
                fallbackPolicy = FallbackPolicy.ALLOW_LIST,
                revisionAllowlistHex = emptyList(),
                minimumPlacementClass = minPlacement,
            ),
        )
        assertTrue(invalid is OmniResult.Err)

        // NONE with non-empty allowlist is also rejected.
        val noneWithList = host.routingApi.validatePreference(
            principal,
            RoutingPreferenceView(
                fallbackPolicy = FallbackPolicy.NONE,
                revisionAllowlistHex = listOf("a".repeat(64)),
                minimumPlacementClass = minPlacement,
            ),
        )
        assertTrue(noneWithList is OmniResult.Err)

        // Plan fails closed — no invented candidates / silent fallback path.
        val plan = host.routingApi.planRoute(
            principal,
            com.omnillm.features.routing.api.PlanRouteSpec(
                requestedRevisionIdHex = "1".repeat(64),
                preference = RoutingPreferenceView(
                    fallbackPolicy = FallbackPolicy.NONE,
                    minimumPlacementClass = minPlacement,
                ),
                candidates = emptyList(),
            ),
        )
        assertTrue(plan is OmniResult.Err)
    }

    @Test
    fun benchmark_and_diagnostics_and_tools_reachable() = runBlocking {
        val host = bootstrapHost()
        val principal = LocalUiPrincipal.ID

        val bench = host.benchmarkApi.getSnapshot(principal)
        assertTrue(bench is OmniResult.Ok)

        val diag = host.diagnosticsApi.getSnapshot(principal)
        assertTrue(diag is OmniResult.Ok)

        val tools = host.toolsApi.getSnapshot(principal)
        assertTrue(tools is OmniResult.Ok)
    }

    @Test
    fun httpHandler_attachesAllPackMarkers_fromHost() {
        val host = bootstrapHost()
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val handler = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            policyManager = policy,
            lanPorts = host.lanPorts,
            diagnosticsApi = host.diagnosticsApi,
            contentReportApi = host.contentReportApi,
            routingApi = host.routingApi,
            toolsApi = host.toolsApi,
            benchmarkApi = host.benchmarkApi,
        )
        val markers = handler.attachedFeaturePackMarkers()
        assertEquals(
            mapOf(
                "lan" to true,
                "benchmark" to true,
                "diagnostics" to true,
                "routing" to true,
                "tools" to true,
                "ai-content-report" to true,
            ),
            markers,
        )
        // Principal shape still available for Admin/HTTP projection tests.
        val principal = HttpPrincipal(
            principalId = "http-test",
            tokenId = "tok-1",
            scopes = setOf("*"),
            revocationEpoch = 0L,
            loopbackOnly = true,
        )
        assertTrue(principal.hasScope("lan.manage"))
    }
}
