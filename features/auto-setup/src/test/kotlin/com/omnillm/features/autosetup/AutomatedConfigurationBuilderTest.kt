package com.omnillm.features.autosetup

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.autosetup.config.AutomatedConfigurationBuilder
import com.omnillm.features.autosetup.domain.ConfigValueSources
import com.omnillm.features.autosetup.domain.RecommendationReasonCodes
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.core.canonical.generated.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomatedConfigurationBuilderTest {

    @Test
    fun prefersCpuWhenAcceleratorUnknown() {
        val cfg = AutomatedConfigurationBuilder.build(
            candidate = candidate(backend = "gpu-vulkan"),
            device = device(gpuEvidence = EvidenceLabel.UNKNOWN),
            preferences = prefs(),
        )
        assertEquals("cpu", cfg.backend.value)
        assertEquals(ConfigValueSources.DEVICE_EVIDENCE, cfg.backend.source)
        assertEquals(RecommendationReasonCodes.ACCELERATOR_UNKNOWN, cfg.backend.reasonCode)
        assertTrue(cfg.requiresPlanReservation)
    }

    @Test
    fun usesUserPreferredBackend() {
        val cfg = AutomatedConfigurationBuilder.build(
            candidate = candidate(backend = "cpu"),
            device = device(gpuEvidence = EvidenceLabel.MEASURED),
            preferences = UserSetupPreferences(
                targetOperation = CapabilityId.TEXT_GENERATION,
                preferredBackend = "cpu",
            ),
        )
        assertEquals("cpu", cfg.backend.value)
        assertEquals(ConfigValueSources.USER_PREFERENCE, cfg.backend.source)
    }

    @Test
    fun conservativeContextOnUnknownDeviceRam() {
        val cfg = AutomatedConfigurationBuilder.build(
            candidate = candidate(),
            device = device(ramBytes = null),
            preferences = prefs(),
        )
        assertEquals(1024, cfg.contextTokens.value)
        assertEquals(ConfigValueSources.DEVICE_EVIDENCE, cfg.contextTokens.source)
    }

    @Test
    fun lowPowerUsesSingleThread() {
        val cfg = AutomatedConfigurationBuilder.build(
            candidate = candidate(),
            device = device(),
            preferences = UserSetupPreferences(
                targetOperation = CapabilityId.TEXT_GENERATION,
                preferLowPower = true,
            ),
        )
        assertEquals(1, cfg.threads.value)
        assertEquals(ConfigValueSources.USER_PREFERENCE, cfg.threads.source)
    }
}
