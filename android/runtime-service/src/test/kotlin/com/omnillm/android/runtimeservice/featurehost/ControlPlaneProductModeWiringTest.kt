package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.memory.InMemoryInstallationRepository
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.ProductModePolicy
import com.omnillm.runtime.policy.SettingValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 / C-12: ProductModePolicy must be wired into production so the modes are
 * not paper flags (FTR-03).
 *
 * - Research mode (`product.researchModeEnabled`): widens the honest diagnostic
 *   surface ??cells UNKNOWN in normal mode disclose reason conditions, and
 *   CONDITIONAL cells carry research markers. State is NEVER widened to
 *   SUPPORTED by research mode.
 * - Risky performance mode (`product.riskyPerformanceModeEnabled`): the
 *   production risk gate (productModeRisk) requires a one-use RiskAck minted by
 *   a LOCAL_ADMIN principal at the current policy version; without an ack,
 *   after settings change (version bump), or on replay the gate is FORBIDDEN.
 *
 * Both modes default OFF (fail closed).
 */
class ControlPlaneProductModeWiringTest {

    private val principal = LocalUiPrincipal.ID
    private val installedRevision = ModelRevisionId.parse(
        IdentityHashing.sha256Hex("model|product-mode-wiring"),
    )

    private fun boundBinding(devMode: Boolean, exploratory: Boolean): EngineExecuteBinding {
        val pack = EnginePackAttachment.attachForTest(includeStubEngine = true)
        val binding = EngineExecuteBinding(
            buildMode = ProductBuildMode(developmentShipMode = devMode),
            exploratoryEnabled = { exploratory },
        )
        binding.applyAttachment(pack)
        return binding
    }

    private fun policyWithMode(key: String, on: Boolean): PolicyManager {
        val pm = PolicyModule.createManager()
        if (on) {
            val r = pm.patchSettings(
                pm.settingsSnapshot().resourceVersion,
                mapOf(key to SettingValue.BoolValue(true)),
                source = "administrator-policy",
            )
            check(r is OmniResult.Ok) { "settings patch must succeed: $r" }
        }
        return pm
    }

    private fun modesOf(pm: PolicyManager): () -> ProductModePolicy.ModeProjection =
        { ProductModePolicy.projectedModes(pm.settingsSnapshot()) }

    private fun modelManagerWithOneInstallation(): ModelManagerWithInstallations {
        val repo = InMemoryInstallationRepository()
        runBlocking {
            repo.save(
                InstallationSnapshot.discovered(
                    installationId = InstallationId("550e8400-e29b-41d4-a716-446655440000"),
                    modelRevisionId = installedRevision,
                    artifactPackageId = ArtifactPackageId.parse("d".repeat(64)),
                ),
            )
        }
        val mm = ModelManagerModule.createInMemoryControlPlane(installations = repo)
        return ModelManagerWithInstallations(mm, runBlocking { mm.listInstallations() })
    }

    private class ModelManagerWithInstallations(
        val modelManager: com.omnillm.runtime.modelmanager.ModelManager,
        val installations: List<InstallationSnapshot>,
    )

    // ------------------------------------------------------------------
    // Research mode: honest diagnostic-surface widening (never SUPPORTED)
    // ------------------------------------------------------------------

    @Test
    fun researchMode_disclosesDiagnostics_forCapabilityUnknownInNormalMode() {
        // Compliance build + bound engine + exploratory ON: EMBEDDING is
        // UNKNOWN in normal mode (capability path not implemented).
        val binding = boundBinding(devMode = false, exploratory = true)
        val normal = ControlPlaneFeaturePorts.playgroundCapabilities(binding, emptyList())
        val pm = policyWithMode("product.researchModeEnabled", on = true)
        val research = ControlPlaneFeaturePorts.playgroundCapabilities(
            binding,
            emptyList(),
            productModes = modesOf(pm),
        )
        assertEquals(
            CapabilityState.UNKNOWN,
            normal.state(CapabilityId.EMBEDDING, installedRevision.hex),
        )
        // State must stay UNKNOWN ??research mode never invents operability.
        assertEquals(
            CapabilityState.UNKNOWN,
            research.state(CapabilityId.EMBEDDING, installedRevision.hex),
        )
        assertNotEquals(
            CapabilityState.SUPPORTED,
            research.state(CapabilityId.EMBEDDING, installedRevision.hex),
        )
        // Normal mode: no disclosure (empty conditions).
        assertEquals(emptyList<String>(), normal.conditions(CapabilityId.EMBEDDING, installedRevision.hex))
        // Research mode: the diagnostic surface widens ??reason conditions appear.
        val conds = research.conditions(CapabilityId.EMBEDDING, installedRevision.hex)
        assertTrue("research mode must disclose diagnostics: $conds", conds.contains("research_mode"))
        assertTrue("raw diagnostics marker missing: $conds", conds.contains("research_mode_raw_diagnostics"))
    }

    @Test
    fun researchMode_markersOnConditionalCell_neverWidenToSupported() {
        val holder = modelManagerWithOneInstallation()
        // Dev-override build: bound engine projects CONDITIONAL for
        // TEXT_GENERATION (never SUPPORTED).
        val binding = boundBinding(devMode = true, exploratory = true)
        val normal = ControlPlaneFeaturePorts.playgroundCapabilities(binding, holder.installations)
        val pm = policyWithMode("product.researchModeEnabled", on = true)
        val research = ControlPlaneFeaturePorts.playgroundCapabilities(
            binding,
            holder.installations,
            productModes = modesOf(pm),
        )
        assertEquals(
            CapabilityState.CONDITIONAL,
            normal.state(CapabilityId.TEXT_GENERATION, installedRevision.hex),
        )
        assertFalse(
            normal.conditions(CapabilityId.TEXT_GENERATION, installedRevision.hex)
                .contains("research_mode"),
        )
        val conds = research.conditions(CapabilityId.TEXT_GENERATION, installedRevision.hex)
        assertTrue("research marker missing: $conds", conds.contains("research_mode"))
        assertTrue("backend-selection marker missing: $conds", conds.contains("research_mode_backend_selection"))
        assertNotEquals(
            CapabilityState.SUPPORTED,
            research.state(CapabilityId.TEXT_GENERATION, installedRevision.hex),
        )
    }

    @Test
    fun researchMode_offByDefault_conditionsMatchNormalProjection() {
        val holder = modelManagerWithOneInstallation()
        val binding = boundBinding(devMode = true, exploratory = true)
        val explicitOff = ControlPlaneFeaturePorts.playgroundCapabilities(
            binding,
            holder.installations,
            productModes = { ProductModePolicy.projectedModes(null) },
        )
        val plain = ControlPlaneFeaturePorts.playgroundCapabilities(binding, holder.installations)
        assertEquals(
            plain.conditions(CapabilityId.TEXT_GENERATION, installedRevision.hex),
            explicitOff.conditions(CapabilityId.TEXT_GENERATION, installedRevision.hex),
        )
        assertFalse(
            explicitOff.conditions(CapabilityId.TEXT_GENERATION, installedRevision.hex)
                .contains("research_mode"),
        )
    }

    @Test
    fun serverCapabilities_researchMode_disclosesConditions_onModelRows() = runBlocking {
        val holder = modelManagerWithOneInstallation()
        val binding = boundBinding(devMode = false, exploratory = true)
        val pm = policyWithMode("product.researchModeEnabled", on = true)
        val port = ControlPlaneFeaturePorts.serverCapabilities(
            binding = binding,
            modelManager = holder.modelManager,
            clockMs = { 1_700_000_000_000L },
            productModes = modesOf(pm),
        )
        val models = port.listModels(principal)
        assertTrue("listModels must succeed: $models", models is OmniResult.Ok)
        val row = (models as OmniResult.Ok).value.single()
        val cell = row.capabilities.single()
        // TEXT_GENERATION is CONDITIONAL under exploratory; research mode must
        // widen the condition surface (never SUPPORTED).
        assertEquals(CapabilityState.CONDITIONAL, cell.state)
        assertNotEquals(CapabilityState.SUPPORTED, cell.state)
        assertTrue("research marker missing: ${cell.conditions}", cell.conditions.contains("research_mode"))
        assertTrue("raw diagnostics marker missing: ${cell.conditions}", cell.conditions.contains("research_mode_raw_diagnostics"))
    }

    // ------------------------------------------------------------------
    // Risky performance mode: production RiskAck gate
    // ------------------------------------------------------------------

    @Test
    fun riskyPerformance_gate_offByDefault_forbidsAckAndRiskyCall() = runBlocking {
        val pm = PolicyModule.createManager()
        val port = ControlPlaneFeaturePorts.productModeRisk(
            policy = { pm },
            store = ProductModePolicy.InMemoryRiskAckStore(),
            clockMs = { 10_000L },
        )
        // Fail closed: ack minting refused while the mode is OFF.
        assertTrue(port.acknowledgeRiskyPerformance(principal, "ack-x") is OmniResult.Err)
        // Fail closed: risky call refused without an ack.
        assertTrue(
            port.runRiskyPerformance(principal, ackId = null) { OmniResult.ok("proceeded") }
                is OmniResult.Err,
        )
    }

    @Test
    fun riskyPerformance_gate_requiresAck_thenConsumesIt() = runBlocking {
        val pm = PolicyModule.createManager()
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val port = ControlPlaneFeaturePorts.productModeRisk(
            policy = { pm },
            store = store,
            clockMs = { 10_000L },
        )
        // Enable risky performance mode via the settings patch path
        // (LOCAL_ADMIN / administrator-policy ??the AdminApiService source).
        val patched = pm.patchSettings(
            pm.settingsSnapshot().resourceVersion,
            mapOf("product.riskyPerformanceModeEnabled" to SettingValue.BoolValue(true)),
            source = "administrator-policy",
        )
        assertTrue("enable risky mode: $patched", patched is OmniResult.Ok)

        // Mint a one-use ack bound to the current policy resourceVersion.
        assertTrue(port.acknowledgeRiskyPerformance(principal, "ack-1") is OmniResult.Ok)

        // WITHOUT an ack the risky entry is FORBIDDEN (never proceeds).
        val noAck = port.runRiskyPerformance(principal, ackId = null) { OmniResult.ok("should-not-run") }
        assertTrue("without ack must be refused: $noAck", noAck is OmniResult.Err)

        // WITH the ack the risky entry proceeds.
        val ok = port.runRiskyPerformance(principal, "ack-1") { OmniResult.ok("ran") }
        assertTrue("with ack must proceed: $ok", ok is OmniResult.Ok)
        assertEquals("ran", (ok as OmniResult.Ok).value)

        // Replay of the same ack is refused (one ack = one use).
        val replay = port.runRiskyPerformance(principal, "ack-1") { OmniResult.ok("replay") }
        assertTrue("replay must be refused: $replay", replay is OmniResult.Err)
    }

    @Test
    fun riskyPerformance_gate_settingsChange_bindsAckToPolicyVersion() = runBlocking {
        val pm = PolicyModule.createManager()
        val port = ControlPlaneFeaturePorts.productModeRisk(
            policy = { pm },
            store = ProductModePolicy.InMemoryRiskAckStore(),
            clockMs = { 10_000L },
        )
        fun enableRiskyMode() {
            val r = pm.patchSettings(
                pm.settingsSnapshot().resourceVersion,
                mapOf("product.riskyPerformanceModeEnabled" to SettingValue.BoolValue(true)),
                source = "administrator-policy",
            )
            assertTrue("enable risky mode: $r", r is OmniResult.Ok)
        }
        enableRiskyMode()
        assertTrue(port.acknowledgeRiskyPerformance(principal, "ack-v1") is OmniResult.Ok)

        // Any later settings patch bumps the resourceVersion ??the minted ack
        // is stale and must be re-acknowledged (fail closed).
        enableRiskyMode()
        val stale = port.runRiskyPerformance(principal, "ack-v1") { OmniResult.ok("stale-run") }
        assertTrue("stale ack must be refused: $stale", stale is OmniResult.Err)
    }

    @Test
    fun riskyPerformance_gate_nonLocalAdminPrincipal_refused() = runBlocking {
        val pm = policyWithMode("product.riskyPerformanceModeEnabled", on = true)
        val port = ControlPlaneFeaturePorts.productModeRisk(
            policy = { pm },
            store = ProductModePolicy.InMemoryRiskAckStore(),
            clockMs = { 10_000L },
        )
        val foreign = PrincipalId.parse("another-principal")
        assertTrue(port.acknowledgeRiskyPerformance(foreign, "ack-foreign") is OmniResult.Err)
        assertTrue(
            port.runRiskyPerformance(foreign, "ack-foreign") { OmniResult.ok("x") }
                is OmniResult.Err,
        )
    }

    // ------------------------------------------------------------------
    // Settings path: LOCAL_ADMIN can flip both product-mode keys
    // ------------------------------------------------------------------

    @Test
    fun settingsPath_localAdmin_patchFlipsBothProductModes() {
        val pm = PolicyModule.createManager()
        val r = pm.patchSettings(
            pm.settingsSnapshot().resourceVersion,
            mapOf(
                "product.researchModeEnabled" to SettingValue.BoolValue(true),
                "product.riskyPerformanceModeEnabled" to SettingValue.BoolValue(true),
            ),
            source = "administrator-policy",
        )
        assertTrue("both-mode patch must succeed: $r", r is OmniResult.Ok)
        val snap = pm.settingsSnapshot()
        assertTrue(snap.values["product.researchModeEnabled"]?.asBoolOrNull() == true)
        assertTrue(snap.values["product.riskyPerformanceModeEnabled"]?.asBoolOrNull() == true)
        assertEquals(
            "administrator-policy",
            snap.effective["product.researchModeEnabled"]?.selectedSource,
        )
        assertFalse(
            // Defaults stay fail-closed on a fresh manager.
            PolicyModule.createManager().settingsSnapshot()
                .values["product.researchModeEnabled"]?.asBoolOrNull() == true,
        )
    }

    // ------------------------------------------------------------------
    // Production wiring: WaveAWiring forwards effective settings
    // ------------------------------------------------------------------

    @Test
    fun waveAWiring_researchMode_viaSettingsDep_projectsResearchMarkers() = runBlocking {
        val pm = policyWithMode("product.researchModeEnabled", on = true)
        val holder = modelManagerWithOneInstallation()
        val binding = boundBinding(devMode = true, exploratory = true)
        val jobs = JobManagerModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val admin = AdminModule.createService(
            commandLedger = ledgers.commandLedger,
            jobManager = jobs,
            policyManager = pm,
            runtimeStateProvider = { "READY" },
            lanStateProvider = { "DISABLED" },
            clockMs = { 1_700_000_000_000L },
        )
        val packs = WaveAWiring.wire(
            WaveAWiring.Deps(
                adminApi = admin,
                jobManager = jobs,
                modelManager = holder.modelManager,
                requestRegistry = ledgers.requestRegistry,
                observability = observability,
                runtimeState = { "READY" },
                lanState = { "DISABLED" },
                runtimeEpoch = { 1L },
                bootId = { "boot-product-mode-wiring" },
                clockMs = { 1_700_000_000_000L },
                engineExecute = binding,
                settings = { pm.settingsSnapshot() },
            ),
        )
        val negotiated = packs.playground.negotiateCapabilities(
            principal,
            PlaygroundTab.CHAT,
            installedRevision.hex,
        )
        assertTrue("negotiate must succeed: $negotiated", negotiated is OmniResult.Ok)
        val required = (negotiated as OmniResult.Ok).value.required
        // Dev-override engine projects TEXT_GENERATION CONDITIONAL; research mode
        // must widen the condition surface on that cell (never SUPPORTED).
        val tg = required.first { it.capabilityId == CapabilityId.TEXT_GENERATION }
        assertEquals(CapabilityState.CONDITIONAL, tg.state)
        assertTrue("research marker missing: ${tg.conditions}", tg.conditions.contains("research_mode"))
        assertTrue("raw diagnostics marker missing: ${tg.conditions}", tg.conditions.contains("research_mode_raw_diagnostics"))
        assertTrue(
            "no cell may be widened to SUPPORTED: $required",
            required.none { cell -> cell.state == CapabilityState.SUPPORTED },
        )
    }

    @Test
    fun waveAWiring_modesOffByDefault_viaSettingsDep_projectsNoResearchMarkers() = runBlocking {
        val pm = PolicyModule.createManager()
        val holder = modelManagerWithOneInstallation()
        val binding = boundBinding(devMode = true, exploratory = true)
        val jobs = JobManagerModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val admin = AdminModule.createService(
            commandLedger = ledgers.commandLedger,
            jobManager = jobs,
            policyManager = pm,
            runtimeStateProvider = { "READY" },
            lanStateProvider = { "DISABLED" },
            clockMs = { 1_700_000_000_000L },
        )
        val packs = WaveAWiring.wire(
            WaveAWiring.Deps(
                adminApi = admin,
                jobManager = jobs,
                modelManager = holder.modelManager,
                requestRegistry = ledgers.requestRegistry,
                observability = observability,
                runtimeState = { "READY" },
                lanState = { "DISABLED" },
                runtimeEpoch = { 1L },
                bootId = { "boot-product-mode-wiring-off" },
                clockMs = { 1_700_000_000_000L },
                engineExecute = binding,
                settings = { pm.settingsSnapshot() },
            ),
        )
        val negotiated = packs.playground.negotiateCapabilities(
            principal,
            PlaygroundTab.CHAT,
            installedRevision.hex,
        )
        assertTrue("negotiate must succeed: $negotiated", negotiated is OmniResult.Ok)
        val required = (negotiated as OmniResult.Ok).value.required
        val tg = required.first { it.capabilityId == CapabilityId.TEXT_GENERATION }
        assertEquals(CapabilityState.CONDITIONAL, tg.state)
        assertFalse(
            "fail-closed default must not project research markers: ${tg.conditions}",
            tg.conditions.contains("research_mode"),
        )
    }
}
