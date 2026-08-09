package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.orchestrator.CapabilityLookup
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import com.omnillm.runtime.orchestrator.StreamEvent
import com.omnillm.runtime.orchestrator.StreamTerminal
import com.omnillm.runtime.orchestrator.StreamTerminalKind
import com.omnillm.runtime.policy.SettingValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine execute binding: FakeEngine + attachForTest stub path.
 *
 * Build posture is variant-scoped (BLD-02): dev mode projects **CONDITIONAL** —
 * never plain SUPPORTED — for generation caps (COR-10 / INV-018/019), with an
 * explicit `development_ship_mode` condition. Compliance mode (dev OFF):
 * exploratory CONDITIONAL only when policy allows; never SUPPORTED without
 * evidence.
 */
class EngineExecuteBindingTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val device = DeviceExecutionFingerprint.parse("device-fp-exec-bind")

    /** Dev-mode posture (what a debug build wires). */
    private val devMode = ProductBuildMode(developmentShipMode = true)

    /** Fail-closed posture (what a release build wires — BLD-02 default). */
    private val failClosed = ProductBuildMode.FAIL_CLOSED

    private fun candidate(
        build: EngineBuildId = EngineBuildId.parse("engine-build-fake-1"),
    ): RoutingCandidate =
        RoutingCandidate(
            candidateId = "primary",
            modelRevisionId = revision,
            installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
            engineBuildId = build,
            backend = "cpu",
            placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            loadKeyDigest = digest,
            isPrimary = true,
            deviceExecutionFingerprint = device,
        )

    @Test
    fun unbound_capabilityIsUnknown() {
        val binding = EngineExecuteBinding(exploratoryEnabled = { true })
        assertFalse(binding.isEngineBound())
        assertEquals(
            CapabilityState.UNKNOWN,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate()),
        )
    }

    @Test
    fun devMode_bound_projectsConditionalNeverSupported() {
        // COR-10 regression: dev mode must NOT project plain SUPPORTED.
        val fake = FakePortEngine()
        val binding = EngineExecuteBinding(buildMode = devMode, exploratoryEnabled = { true })
        binding.bindTestEngine(fake)
        for (cap in listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.TOOL_CALLING,
            CapabilityId.EMBEDDING,
        )) {
            val state = binding.resolveCapability(cap, candidate())
            assertEquals(
                "dev mode projection for $cap must be CONDITIONAL, was $state",
                CapabilityState.CONDITIONAL,
                state,
            )
            assertTrue(
                "dev mode must never project SUPPORTED (got $state for $cap)",
                state != CapabilityState.SUPPORTED,
            )
        }
        // The conditions list discloses the dev-override honestly.
        val conds = binding.conditions(CapabilityId.TEXT_GENERATION, revision.hex)
        assertTrue("development_ship_mode", "development_ship_mode" in conds)
        assertTrue("engine_unqualified", "engine_unqualified" in conds)
        // Not-yet-implemented dev-open caps disclose the pending marker.
        assertTrue(
            "dev_path_open_implementation_pending",
            "dev_path_open_implementation_pending" in
                binding.conditions(CapabilityId.STRUCTURED_OUTPUT, revision.hex),
        )
    }

    @Test
    fun releaseConfig_devOff_projectsNeverSupported() {
        // BLD-02 regression: release configuration (fail-closed mode) never
        // projects SUPPORTED; exploratory only widens to CONDITIONAL.
        val binding = EngineExecuteBinding(buildMode = failClosed, exploratoryEnabled = { true })
        binding.bindTestEngine(FakePortEngine())
        assertEquals(
            CapabilityState.CONDITIONAL,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate()),
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            binding.resolveCapability(CapabilityId.STRUCTURED_OUTPUT, candidate()),
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            binding.resolveCapability(CapabilityId.EMBEDDING, candidate()),
        )
        assertTrue(
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate()) !=
                CapabilityState.SUPPORTED,
        )
    }

    @Test
    fun fakeEngine_withoutExploratory_reflectsBuildMode() {
        // Dev mode: bound adapter is executable regardless of the exploratory flag.
        val devBinding = EngineExecuteBinding(buildMode = devMode, exploratoryEnabled = { false })
        devBinding.bindTestEngine(FakePortEngine())
        assertEquals(
            CapabilityState.CONDITIONAL,
            devBinding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate()),
        )
        // Release posture: exploratory off ⇒ UNKNOWN.
        val releaseBinding = EngineExecuteBinding(buildMode = failClosed, exploratoryEnabled = { false })
        releaseBinding.bindTestEngine(FakePortEngine())
        assertEquals(
            CapabilityState.UNKNOWN,
            releaseBinding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate()),
        )
    }

    @Test
    fun attachForTest_stub_bindsAdapter_capabilityReflectsBuildMode() {
        val pack = EnginePackAttachment.attachForTest(
            buildMode = devMode,
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        assertNotNull(pack.llamaCppEngine)
        val binding = EngineExecuteBinding(buildMode = devMode, exploratoryEnabled = { true })
        val result = binding.applyAttachment(pack)
        assertTrue(result.bound)
        assertTrue(binding.isEngineBound())
        // Dev mode: registrations present ⇒ executable (never SUPPORTED evidence).
        assertTrue(
            com.omnillm.android.runtimeservice.controlplane.EngineSelectionPolicy
                .anyExecutableCell(pack.registry, devMode),
        )
        val build = pack.llamaCppEngine!!.engineBuildId
        assertEquals(
            CapabilityState.CONDITIONAL,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate(build)),
        )
        // Cross-build refuses
        assertEquals(
            CapabilityState.UNKNOWN,
            binding.resolveCapability(
                CapabilityId.TEXT_GENERATION,
                candidate(EngineBuildId.parse("engine-other-build-xx")),
            ),
        )
    }

    @Test
    fun attachForTest_releaseMode_cellsStayUnknownExecutableFalse() {
        // BLD-02 regression: release-mode attach must not claim executability
        // from registrations; registry projection stays UNKNOWN (no evidence).
        val pack = EnginePackAttachment.attachForTest(
            buildMode = failClosed,
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        assertFalse(
            com.omnillm.android.runtimeservice.controlplane.EngineSelectionPolicy
                .anyExecutableCell(pack.registry, failClosed),
        )
        val binding = EngineExecuteBinding(buildMode = failClosed, exploratoryEnabled = { true })
        val result = binding.applyAttachment(pack)
        assertTrue(result.bound)
        val build = pack.llamaCppEngine!!.engineBuildId
        // Exploratory on ⇒ CONDITIONAL; still never SUPPORTED.
        assertEquals(
            CapabilityState.CONDITIONAL,
            binding.resolveCapability(CapabilityId.TEXT_GENERATION, candidate(build)),
        )
    }

    @Test
    fun attachForTest_withoutStub_unbindsFailClosed() {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = false,
            deviceFingerprint = device,
        )
        val binding = EngineExecuteBinding(exploratoryEnabled = { true })
        val result = binding.applyAttachment(pack)
        assertFalse(result.bound)
        assertFalse(binding.isEngineBound())
        assertTrue(binding.inferenceEngine.delegate is FailClosedInferenceEngine)
    }

    @Test
    fun orchestrator_submitPump_withFake_whenConditional() = runBlocking {
        val fake = FakePortEngine()
        val binding = EngineExecuteBinding(exploratoryEnabled = { true })
        binding.bindTestEngine(fake)
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val orch = OrchestratorModule.create(
            registry = ledgers.requestRegistry,
            governor = com.omnillm.runtime.governor.ResourceGovernor(
                capacity = ResourceVector(cpuAnonBytes = 50_000_000L, nativeThreads = 32L),
                safetyMargin = ResourceVector(cpuAnonBytes = 1_000L, nativeThreads = 1L),
                issuerBootId = "boot-test",
                runtimeEpoch = 1L,
                clockMonotonic = { 1_000L },
            ),
            engine = binding.inferenceEngine,
            capabilities = binding.capabilityLookup,
            issuerBootId = "boot-test",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
        )
        val build = EngineBuildId.parse("engine-build-fake-1")
        val req = OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-test"),
            idempotencyKey = IdempotencyKey.parse("idem-${UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(build)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        assertTrue(terminal is OmniResult.Ok)
        assertEquals("COMPLETED", (terminal as OmniResult.Ok).value.state)
        assertTrue(fake.planCount.get() >= 1)
        assertTrue(fake.commitCount.get() >= 1)
        assertTrue(fake.startCount.get() >= 1)
    }

    @Test
    fun waveA_wiring_usesDelegatingNotPermanentFailClosed() {
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
        val binding = EngineExecuteBinding(exploratoryEnabled = { false })
        val waveA = WaveAWiring.bootstrapForTest(
            adminApi = admin,
            jobManager = jobs,
            requestRegistry = ledgers.requestRegistry,
            observability = observability,
            engineExecute = binding,
        )
        assertTrue(waveA.engineExecute === binding)
        // Default unbound → fail-closed delegate
        assertTrue(waveA.engineExecute.inferenceEngine.delegate is FailClosedInferenceEngine)
        // After Fake bind, not fail-closed
        binding.bindTestEngine(FakePortEngine())
        assertTrue(waveA.engineExecute.isEngineBound())
        assertFalse(waveA.engineExecute.inferenceEngine.delegate is FailClosedInferenceEngine)
    }

    @Test
    fun featurePackHost_withWaveA_bindsRoutingToOrchestrator() = runBlocking {
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
        )
        val binding = EngineExecuteBinding(exploratoryEnabled = { true })
        binding.bindTestEngine(FakePortEngine())
        val waveA = WaveAWiring.bootstrapForTest(
            adminApi = admin,
            jobManager = jobs,
            requestRegistry = ledgers.requestRegistry,
            observability = observability,
            engineExecute = binding,
        )
        val host = FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = policy,
            observability = observability,
            waveA = waveA,
        )
        // Routing is not permanent FailClosed when wave-A present
        assertNotNull(host.routingApi)
        val build = EngineBuildId.parse("engine-build-fake-1")
        val req = OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-route"),
            idempotencyKey = IdempotencyKey.parse("idem-route"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(build)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )
        // Plan via orchestrator path should succeed with Fake + CONDITIONAL
        val plan = waveA.candidatePlanner!!.plan(req)
        assertTrue(plan is OmniResult.Ok)
    }

    @Test
    fun exploratorySetting_defaultReflectsBuildMode() {
        val def = com.omnillm.runtime.policy.ConfigurationCatalog.definition(
            EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE,
        )
        assertNotNull(def)
        // The static catalog is fail-closed (COR-10): the dev-mode default ON is
        // seeded by the Android control plane, not baked into the JVM catalog.
        assertEquals(SettingValue.BoolValue(false), def!!.defaultValue)
        // Dev-mode posture default is ON so debug/dev works out of the box.
        assertTrue(ProductBuildMode(developmentShipMode = true).defaultExploratoryExecuteEnabled())
        assertFalse(failClosed.defaultExploratoryExecuteEnabled())
        // An *absent* setting value is false (effective value composed by merge).
        assertFalse(EngineExecuteBinding.readExploratoryEnabled(emptyMap()))
        assertTrue(
            EngineExecuteBinding.readExploratoryEnabled(
                mapOf(EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE to SettingValue.BoolValue(true)),
            ),
        )
    }

    @Test
    fun llamaCppAdapter_withStub_planIsPure() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        val adapter = LlamaCppInferenceEngineAdapter(pack.llamaCppEngine!!)
        val req = OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-llama"),
            idempotencyKey = IdempotencyKey.parse("idem-llama"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(pack.llamaCppEngine!!.engineBuildId)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )
        val plan = adapter.planInference(req, req.candidates.first())
        assertTrue(plan is OmniResult.Ok)
        // Mismatched build fails closed
        val bad = adapter.planInference(req, candidate(EngineBuildId.parse("engine-wrong-build")))
        assertTrue(bad is OmniResult.Err)
    }

    private fun orchestratorWith(engine: InferenceEnginePort, capabilities: CapabilityLookup): Orchestrator =
        OrchestratorModule.create(
            registry = RequestRegistryModule.createInMemoryWithCommits().requestRegistry,
            governor = com.omnillm.runtime.governor.ResourceGovernor(
                capacity = ResourceVector(
                    cpuAnonBytes = 512L * 1024L * 1024L,
                    nativeThreads = 64L,
                    fileDescriptors = 256L,
                ),
                safetyMargin = ResourceVector(
                    cpuAnonBytes = 1_000L,
                    nativeThreads = 1L,
                    fileDescriptors = 4L,
                ),
                issuerBootId = "boot-llama-e2e",
                runtimeEpoch = 1L,
                clockMonotonic = { 1_000L },
            ),
            engine = engine,
            capabilities = capabilities,
            issuerBootId = "boot-llama-e2e",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
        )

    private fun orchestrationRequest(build: EngineBuildId): OrchestrationRequest =
        OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-llama-e2e"),
            idempotencyKey = IdempotencyKey.parse("idem-llama-e2e-${UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(build)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )

    @Test
    fun llamaCppStub_orchestratorSubmitPump_endToEnd() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        val binding = EngineExecuteBinding(
            buildMode = devMode,
            exploratoryEnabled = { true },
        )
        binding.applyAttachment(pack)
        assertTrue(binding.isEngineBound())

        val orch = orchestratorWith(binding.inferenceEngine, binding.capabilityLookup)
        val build = pack.llamaCppEngine!!.engineBuildId
        val req = orchestrationRequest(build)
        val submitted = orch.submit(req)
        assertTrue("submit should succeed: $submitted", submitted is OmniResult.Ok)
        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        assertTrue("execute should succeed: $terminal", terminal is OmniResult.Ok)
        val result = (terminal as OmniResult.Ok).value
        assertEquals("COMPLETED", result.state)
        assertEquals(build.value, result.actualRouting.engineBuildId.value)
        assertEquals("cpu", result.actualRouting.backend)
        // Registry projection: attach seeds only UNQUALIFIED cells (compliance invariant).
        // Default binding posture is fail-closed ⇒ no executability from registrations.
        assertFalse(
            com.omnillm.android.runtimeservice.controlplane.EngineSelectionPolicy
                .anyExecutableCell(pack.registry, failClosed),
        )
        assertTrue(
            pack.registry.listCells().none {
                it.qualificationStatus ==
                    com.omnillm.engines.api.EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE
            },
        )
    }

    @Test
    fun llamaCppAdapter_realResolverUsesInstalledGguf() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        val build = pack.llamaCppEngine!!.engineBuildId
        val realPath = "/runtime/files/installations/test-install/WEIGHTS"
        var resolvedRevisions = 0
        val resolver = LlamaCppInferenceEngineAdapter.ModelSourceResolver { plan ->
            assertEquals(revision, plan.modelRevisionId)
            resolvedRevisions++
            OmniResult.ok(
                LlamaCppInferenceEngineAdapter.ResolvedModelSource(
                    storageRootKey = "installations/test-install",
                    resolvedModelPath = realPath,
                ),
            )
        }
        val adapter = LlamaCppInferenceEngineAdapter(
            engine = pack.llamaCppEngine!!,
            modelSourceResolver = resolver,
            fallbackToFixtureOnUnresolved = false,
        )
        val orch = orchestratorWith(
            adapter,
            CapabilityLookup { _, _ -> CapabilityState.SUPPORTED },
        )
        val submitted = orch.submit(orchestrationRequest(build))
        assertTrue("submit should succeed: $submitted", submitted is OmniResult.Ok)
        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        assertTrue("execute should succeed: $terminal", terminal is OmniResult.Ok)
        assertEquals("COMPLETED", (terminal as OmniResult.Ok).value.state)
        assertTrue("resolver must be invoked for the real install", resolvedRevisions >= 1)
    }

    @Test
    fun llamaCppAdapter_unresolvedWithoutFallback_failsClosed() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        val build = pack.llamaCppEngine!!.engineBuildId
        val resolver = LlamaCppInferenceEngineAdapter.ModelSourceResolver { _ ->
            OmniResult.err(OmniError.NOT_FOUND(message = "no READY installation"))
        }
        val adapter = LlamaCppInferenceEngineAdapter(
            engine = pack.llamaCppEngine!!,
            modelSourceResolver = resolver,
            fallbackToFixtureOnUnresolved = false,
        )
        val orch = orchestratorWith(
            adapter,
            CapabilityLookup { _, _ -> CapabilityState.SUPPORTED },
        )
        val submitted = orch.submit(orchestrationRequest(build))
        assertTrue("submit should succeed: $submitted", submitted is OmniResult.Ok)
        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        assertTrue(
            "no READY install + no fallback must fail closed: $terminal",
            terminal is OmniResult.Err,
        )
    }

    @Test
    fun llamaCppAdapter_unresolvedWithFallback_usesFixture() = runBlocking {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        val build = pack.llamaCppEngine!!.engineBuildId
        val resolver = LlamaCppInferenceEngineAdapter.ModelSourceResolver { _ ->
            OmniResult.err(OmniError.NOT_FOUND(message = "no READY installation"))
        }
        val adapter = LlamaCppInferenceEngineAdapter(
            engine = pack.llamaCppEngine!!,
            modelSourceResolver = resolver,
            fallbackToFixtureOnUnresolved = true,
        )
        val orch = orchestratorWith(
            adapter,
            CapabilityLookup { _, _ -> CapabilityState.SUPPORTED },
        )
        val submitted = orch.submit(orchestrationRequest(build))
        assertTrue("submit should succeed: $submitted", submitted is OmniResult.Ok)
        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        assertTrue("DEV fallback should succeed: $terminal", terminal is OmniResult.Ok)
        assertEquals("COMPLETED", (terminal as OmniResult.Ok).value.state)
    }

    /**
     * Minimal Fake [InferenceEnginePort] (mirrors orchestrator test Fake).
     */
    private class FakePortEngine : InferenceEnginePort {
        val planCount = AtomicInteger(0)
        val commitCount = AtomicInteger(0)
        val startCount = AtomicInteger(0)
        private val commits = linkedMapOf<String, String>()

        override suspend fun planInference(
            request: OrchestrationRequest,
            candidate: RoutingCandidate,
        ): OmniResult<InferencePlanOutcome> {
            planCount.incrementAndGet()
            val dig = Sha256Digest.parse("e".repeat(64))
            val envelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 1_024L, nativeThreads = 1L),
                peak = ResourceVector(cpuAnonBytes = 2_048L, nativeThreads = 2L),
            )
            val plan = Plan(
                planId = PlanId.parse("plan-${planCount.get()}"),
                requestId = request.requestId,
                principalId = request.principalId,
                modelRevisionId = candidate.modelRevisionId,
                engineBuildId = candidate.engineBuildId,
                deviceExecutionFingerprint = candidate.deviceExecutionFingerprint,
                canonicalInputDigest = dig,
                resourceEnvelope = envelope,
                expiryMonotonic = request.deadlineMonotonic,
                runtimeEpoch = request.runtimeEpoch,
            )
            return OmniResult.ok(InferencePlanOutcome(plan, envelope, dig))
        }

        override suspend fun commitInference(
            plan: Plan,
            reservation: Reservation,
            commit: Commit,
        ): OmniResult<PreparedOperation> {
            commitCount.incrementAndGet()
            commits[commit.commitId.value] = "COMMITTED"
            return OmniResult.ok(
                PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse("prep-${commit.commitId.value}"),
                    operationId = "op-${commit.commitId.value}",
                    requestId = commit.requestId,
                    commitId = commit.commitId,
                    principalId = commit.principalId,
                    reservationId = reservation.reservationId,
                    revisionLeaseId = commit.revisionLeaseId,
                    issuerBootId = commit.issuerBootId,
                    runtimeEpoch = commit.runtimeEpoch,
                    revocationEpoch = commit.revocationEpoch,
                    sourceSessionEpoch = commit.sourceSessionEpoch,
                    targetSessionId = null,
                    canonicalInputDigest = plan.canonicalInputDigest,
                ),
            )
        }

        override suspend fun start(
            prepared: PreparedOperation,
            operationId: String,
            runtimeEpoch: Long,
        ): OmniResult<Unit> {
            startCount.incrementAndGet()
            return OmniResult.ok(Unit)
        }

        override suspend fun nextEvents(
            prepared: PreparedOperation,
            fromSeq: Long,
        ): OmniResult<StreamBatchOutcome> {
            if (fromSeq >= 1L) {
                return OmniResult.ok(
                    StreamBatchOutcome(
                        seqFrom = fromSeq,
                        seqTo = fromSeq,
                        events = emptyList(),
                        terminal = StreamTerminal(
                            kind = StreamTerminalKind.SUCCESS,
                            outputDigest = Sha256Digest.parse("f".repeat(64)),
                        ),
                    ),
                )
            }
            return OmniResult.ok(
                StreamBatchOutcome(
                    seqFrom = 0L,
                    seqTo = 1L,
                    events = listOf(StreamEvent(0L, "delta", Sha256Digest.parse("f".repeat(64)))),
                    terminal = StreamTerminal(
                        kind = StreamTerminalKind.SUCCESS,
                        outputDigest = Sha256Digest.parse("f".repeat(64)),
                    ),
                ),
            )
        }

        override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
            OmniResult.ok(
                CommitQueryState(
                    commitId = commitId,
                    state = commits[commitId.value] ?: "UNKNOWN",
                    loadedModelId = null,
                ),
            )
    }
}
