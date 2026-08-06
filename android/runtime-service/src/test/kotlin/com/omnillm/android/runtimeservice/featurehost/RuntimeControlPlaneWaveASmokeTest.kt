package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.admin.AdminFeatureModule
import com.omnillm.features.autosetup.AutoSetupModule
import com.omnillm.features.dashboard.DashboardFeatureModule
import com.omnillm.features.modelhub.ModelhubModule
import com.omnillm.features.playground.PlaygroundModule
import com.omnillm.features.server.ServerFeatureModule
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-A smoke: RuntimeControlPlane exposes each wave-A service non-null
 * via FeaturePackHost.waveA / WaveAWiring (same path as attach).
 */
class RuntimeControlPlaneWaveASmokeTest {

    private fun wireWaveA(): WaveAFeaturePacks {
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
        )
    }

    @Test
    fun waveA_allFeatureServicesNonNull() {
        val waveA = wireWaveA()
        waveA.assertAllServicesNonNull()
        assertNotNull(waveA.admin)
        assertNotNull(waveA.autoSetup)
        assertNotNull(waveA.modelHub)
        assertNotNull(waveA.playground)
        assertNotNull(waveA.server)
        assertNotNull(waveA.dashboard)
        assertNotNull(waveA.modelManager)
        assertNotNull(waveA.orchestrator)
        assertNotNull(waveA.resourceGovernor)
    }

    @Test
    fun waveA_featureIdsMatchCatalog() {
        assertEquals(
            setOf(
                AdminFeatureModule.FEATURE_ID,
                AutoSetupModule.FEATURE_ID,
                ModelhubModule.FEATURE_ID,
                PlaygroundModule.FEATURE_ID,
                ServerFeatureModule.FEATURE_ID,
                DashboardFeatureModule.FEATURE_ID,
            ),
            WaveAWiring.WAVE_A_FEATURE_IDS,
        )
    }

    @Test
    fun waveA_attachedOnFeaturePackHost_exposesServices() {
        val waveA = wireWaveA()
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val host = FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = policy,
            observability = observability,
            waveA = waveA,
        )
        assertNotNull(host.waveA)
        host.waveA!!.assertAllServicesNonNull()
        assertTrue(host.featureIds.containsAll(WaveAWiring.WAVE_A_FEATURE_IDS))
        assertTrue(host.featureIds.containsAll(FeaturePackHost.WAVE_B_FEATURE_IDS))
    }

    @Test
    fun waveA_deviceProbe_doesNotInventAcceleratorEvidence() = runBlocking {
        val waveA = wireWaveA()
        val device = waveA.autoSetup.discoverDevice()
        assertTrue(device is OmniResult.Ok)
        val snap = (device as OmniResult.Ok).value
        assertEquals(EvidenceLabel.UNKNOWN, snap.gpuEvidenceLabel)
        assertEquals(EvidenceLabel.UNKNOWN, snap.npuEvidenceLabel)
    }
}
