package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.features.benchmark.BenchmarkFeatureModule
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.contentreport.ContentReportModule
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.diagnostics.DiagnosticsModule
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.lan.LanFeatureModule
import com.omnillm.features.lan.api.LanAccessApi
import com.omnillm.features.lan.domain.ContentReportVsTelemetry
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.routing.RoutingFeatureModule
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.routing.ports.RoutingFeaturePorts
import com.omnillm.features.tools.ToolsFeatureModule
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.features.tools.ports.InMemoryToolProposalLedger
import com.omnillm.features.tools.ports.LocalAdminToolsScopePort
import com.omnillm.android.runtimeservice.http.LanTlsEndpoint
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.observability.ObservabilityFacade
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.security.InMemorySecretBroker
import com.omnillm.runtime.policy.security.SecretBroker
import com.omnillm.data.persistence.ToolProposalLedgerPorts

/**
 * Wave-B Feature Pack host attached to the runtime control plane (ADR-010).
 *
 * Packs: lan, benchmark, diagnostics, routing, tools, ai-content-report.
 * UI never constructs this object (INV-001). Call [bootstrap] only from
 * [com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane] or tests.
 *
 * Policy anchors preserved at bootstrap:
 * - LAN default-off
 * - Content report stream ≠ telemetry
 * - Routing never silent cross-revision fallback (orchestrator fail-closed until wired)
 */
data class FeaturePackHost(
    val lanHost: ControlPlaneLanHost,
    val lanPorts: LanRuntimePorts,
    val lanApi: LanAccessApi,
    val benchmarkApi: BenchmarkApi,
    val diagnosticsApi: DiagnosticsApi,
    val routingApi: RoutingApi,
    val toolsApi: ToolsApi,
    val contentReportApi: ContentReportApi,
    /** Wave-A packs (admin, auto-setup, modelhub, playground, server, dashboard); null only in pure wave-B unit fixtures. */
    val waveA: WaveAFeaturePacks? = null,
) {
    /** Catalog feature IDs reachable from this host (order not significant). */
    val featureIds: Set<String> = buildSet {
        add(LanFeatureModule.FEATURE_ID)
        add(BenchmarkFeatureModule.FEATURE_ID)
        add(DiagnosticsModule.FEATURE_ID)
        add(RoutingFeatureModule.FEATURE_ID)
        add(ToolsFeatureModule.FEATURE_ID)
        add(ContentReportModule.FEATURE_ID)
        if (waveA != null) addAll(WaveAWiring.WAVE_A_FEATURE_IDS)
    }

    fun modulePaths(): Set<String> = setOf(
        LanFeatureModule.MODULE_PATH,
        BenchmarkFeatureModule.MODULE_PATH,
        DiagnosticsModule.MODULE_PATH,
        RoutingFeatureModule.MODULE_PATH,
        ToolsFeatureModule.MODULE_PATH,
        ContentReportModule.MODULE_PATH,
    )

    /** True when LAN product default + host state are default-off. */
    fun lanIsDefaultOff(): Boolean = lanHost.isDefaultOff()

    /** Report stream kind is never TELEMETRY. */
    fun contentReportIsNotTelemetry(): Boolean =
        ContentReportPolicy.DATA_STREAM_KIND != ContentReportPolicy.TELEMETRY_STREAM_KIND &&
            ContentReportPolicy.DATA_STREAM_KIND == "AI_CONTENT_REPORT" &&
            !ContentReportVsTelemetry.telemetryModeImpliesReportConsent("OFF") &&
            !ContentReportVsTelemetry.lanEnabledImpliesTelemetryExport(true)

    companion object {
        val WAVE_B_FEATURE_IDS: Set<String> = setOf(
            "FEAT-LAN",
            "FEAT-BENCHMARK",
            "FEAT-DIAGNOSTICS",
            "FEAT-ROUTING",
            "FEAT-TOOLS",
            "FEAT-AI-CONTENT-REPORT",
        )

        /**
         * Pure bootstrap of Feature Pack APIs from control-plane dependencies.
         * Does not open Android Context or native engines.
         */
        fun bootstrap(
            jobManager: JobManager,
            policyManager: PolicyManager,
            observability: ObservabilityFacade,
            clockMs: () -> Long = { System.currentTimeMillis() },
            contentReportApi: ContentReportApi? = null,
            /**
             * When non-null and [contentReportApi] is null, wires SQLite-backed
             * content-report store (production). Hermetic tests omit both and get
             * [com.omnillm.features.contentreport.ports.InMemoryContentReportStore].
             */
            contentReportLedger: com.omnillm.data.persistence.ContentReportLedgerPorts? = null,
            /**
             * When non-null, wires SQLite-backed tool proposal ledger (production).
             * Hermetic tests omit and get [InMemoryToolProposalLedger].
             */
            toolProposalLedger: ToolProposalLedgerPorts? = null,
            /** When non-null, attaches wave-A packs (production RuntimeControlPlane path). */
            waveA: WaveAFeaturePacks? = null,
            /**
             * Production: [PolicyModule.SecurityStack] for durable pairing/tokens.
             * Hermetic wave-B tests may omit (local challenge fallback).
             */
            securityStack: PolicyModule.SecurityStack? = null,
            /**
             * Production: real TLS identity + optional network bind.
             * Unit tests inject identity-only endpoint or leave null (STARTING without TLS).
             */
            lanTlsEndpoint: LanTlsEndpoint? = null,
            /** Production true; hermetic tests false (no socket bind). */
            lanBindNetwork: Boolean = false,
        ): FeaturePackHost {
            require(!LanServiceLifecyclePolicy.DEFAULT_ENABLED) {
                "LAN product default must stay false (FEAT-LAN)"
            }

            val lanHost = ControlPlaneLanHost(
                policyManager = policyManager,
                pairingService = securityStack?.pairingChallenges,
                tokenService = securityStack?.tokenService,
                tlsEndpoint = lanTlsEndpoint,
                bindNetworkOnEnable = lanBindNetwork,
                clockMs = clockMs,
            )
            val lanPorts = lanHost.asPorts()
            val lanApi = LanFeatureModule.createApi(lanPorts)

            val benchmarkApi = BenchmarkFeatureModule.createApi(
                jobManager = jobManager,
                observability = observability,
                clockMs = clockMs,
            )
            val diagnosticsApi = DiagnosticsModule.createApi(
                jobManager = jobManager,
                observability = observability,
                clockMs = clockMs,
            )
            // Prefer plane Orchestrator when Wave-A is present (SW-FEAT-10 / SW-ENG-06).
            // Pure wave-B fixtures keep fail-closed until orchestrator is injected.
            val routingOrch = if (waveA != null) {
                val planner = waveA.candidatePlanner
                    ?: com.omnillm.runtime.orchestrator.CandidatePlanner(
                        capabilities = waveA.engineExecute.capabilityLookup,
                        health = com.omnillm.runtime.orchestrator.HealthLookup {
                            com.omnillm.runtime.orchestrator.HealthSnapshot()
                        },
                        engine = waveA.engineExecute.inferenceEngine,
                    )
                ControlPlaneFeaturePorts.routingOrchestrator(waveA.orchestrator, planner)
            } else {
                FailClosedRoutingOrchestrator
            }
            val routingApi = RoutingFeatureModule.createApi(
                RoutingFeaturePorts(
                    orchestrator = routingOrch,
                    capabilities = HostRoutingCapabilityPort(),
                    clockMs = clockMs,
                ),
            )
            val toolsInference = if (waveA != null) {
                ControlPlaneFeaturePorts.toolsInference(
                    orchestrator = waveA.orchestrator,
                    binding = waveA.engineExecute,
                    modelManager = waveA.modelManager,
                    clockMs = clockMs,
                    runtimeEpoch = { 1L },
                )
            } else {
                FailClosedToolsInferencePort
            }
            val toolsApi = if (toolProposalLedger != null) {
                ToolsFeatureModule.createDurableApi(
                    inference = toolsInference,
                    ledger = toolProposalLedger,
                    capabilities = HostToolsCapabilityPort(),
                    scopes = LocalAdminToolsScopePort,
                    clockMs = clockMs,
                )
            } else {
                ToolsFeatureModule.createApi(
                    inference = toolsInference,
                    capabilities = HostToolsCapabilityPort(),
                    scopes = LocalAdminToolsScopePort,
                    ledger = InMemoryToolProposalLedger(),
                    clockMs = clockMs,
                )
            }
            val reportApi = contentReportApi
                ?: if (contentReportLedger != null) {
                    // Production: plane SecurityStack broker (Keystore-wrapped).
                    // Hermetic durable tests without securityStack: InMemorySecretBroker.
                    val reportBroker: SecretBroker =
                        securityStack?.secretBroker ?: InMemorySecretBroker(clockMs)
                    ContentReportModule.createDurableApi(
                        jobManager = jobManager,
                        observability = observability,
                        ledger = contentReportLedger,
                        secretBroker = reportBroker,
                        clockMs = clockMs,
                    )
                } else {
                    ContentReportModule.createApi(
                        jobManager = jobManager,
                        observability = observability,
                        clockMs = clockMs,
                    )
                }

            // Bind ToolsApi into playground STRUCTURED_TOOLS adapter (late bind).
            waveA?.toolsApiHolder?.toolsApi = toolsApi
            // Bind BenchmarkApi into dashboard MEASUREMENTS last-run projection.
            waveA?.benchmarkApiHolder?.benchmarkApi = benchmarkApi

            val host = FeaturePackHost(
                lanHost = lanHost,
                lanPorts = lanPorts,
                lanApi = lanApi,
                benchmarkApi = benchmarkApi,
                diagnosticsApi = diagnosticsApi,
                routingApi = routingApi,
                toolsApi = toolsApi,
                contentReportApi = reportApi,
                waveA = waveA,
            )
            // Wave-B always present; wave-A optional until production attach injects it.
            check(host.featureIds.containsAll(WAVE_B_FEATURE_IDS)) {
                "wave-B feature set missing: expected $WAVE_B_FEATURE_IDS got ${host.featureIds}"
            }
            if (waveA != null) {
                check(host.featureIds.containsAll(WaveAWiring.WAVE_A_FEATURE_IDS)) {
                    "wave-A feature set missing: ${host.featureIds}"
                }
                waveA.assertAllServicesNonNull()
            }
            check(host.lanIsDefaultOff()) { "LAN must bootstrap default-off" }
            check(host.contentReportIsNotTelemetry()) {
                "content report must not equal telemetry stream"
            }
            return host
        }
    }
}
