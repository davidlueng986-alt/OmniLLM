package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-mode semantics (FTR-03): both modes fail closed by default,
 * risk-ack gating is enforced, and honest capability projection reflects
 * the effective flags only.
 */
class ProductModePolicyTest {

    private fun snapshot(
        research: Boolean = false,
        risky: Boolean = false,
        resourceVersion: Long = 1L,
    ): SettingsSnapshot =
        SettingsSnapshot(
            resourceVersion = resourceVersion,
            values = linkedMapOf(
                "product.researchModeEnabled" to SettingValue.BoolValue(research),
                "product.riskyPerformanceModeEnabled" to SettingValue.BoolValue(risky),
            ),
        )

    @Test
    fun modes_failClosedByDefault() {
        val modes = ConfigurationCatalog.productModes(null)
        assertFalse(modes.researchModeEnabled)
        assertFalse(modes.riskyPerformanceModeEnabled)
        assertFalse(ProductModePolicy.researchModeEnabled(null))
        assertFalse(ProductModePolicy.riskyPerformanceModeEnabled(null))
    }

    @Test
    fun catalogDefaults_governorCapacities_matchHistoricalValues() {
        // ARC-10: catalog defaults must equal the historical hardcoded
        // capacities (512MiB anon / 8GiB file / 64 threads / 1024 FDs).
        val caps = ConfigurationCatalog.governorCapacities(null)
        assertEquals(512L * 1024L * 1024L, caps.anonMemoryBytes)
        assertEquals(8L * 1024L * 1024L * 1024L, caps.fileCacheBytes)
        assertEquals(64L, caps.threads)
        assertEquals(1024L, caps.fileDescriptors)
        assertEquals(ConfigurationCatalog.DEFAULT_PROBE_DEADLINE_MS, caps.probeDeadlineMs)
    }

    @Test
    fun governorCapacities_readEffectiveSettings() {
        val snap = SettingsSnapshot(
            resourceVersion = 7L,
            values = linkedMapOf(
                "resource.governorAnonMemoryCapBytes" to SettingValue.IntValue(1024L),
                "resource.governorFileCacheCapBytes" to SettingValue.IntValue(2048L),
                "resource.governorThreadCap" to SettingValue.IntValue(16L),
                "resource.governorFdCap" to SettingValue.IntValue(64L),
                "resource.probeDeadlineMs" to SettingValue.IntValue(5_000L),
            ),
        )
        val caps = ConfigurationCatalog.governorCapacities(snap)
        assertEquals(1024L, caps.anonMemoryBytes)
        assertEquals(2048L, caps.fileCacheBytes)
        assertEquals(16L, caps.threads)
        assertEquals(64L, caps.fileDescriptors)
        assertEquals(5_000L, caps.probeDeadlineMs)
    }

    @Test
    fun governorCapacities_clampToAtLeastOne_andFallBackOnAbsent() {
        val snap = SettingsSnapshot(
            resourceVersion = 1L,
            values = linkedMapOf(
                "resource.governorAnonMemoryCapBytes" to SettingValue.IntValue(-5L),
            ),
        )
        val caps = ConfigurationCatalog.governorCapacities(snap)
        assertEquals(1L, caps.anonMemoryBytes)
        assertEquals(ConfigurationCatalog.DEFAULT_THREAD_CAP, caps.threads)
    }

    @Test
    fun projectedModes_areHonest() {
        val off = ProductModePolicy.projectedModes(snapshot(research = false, risky = false))
        assertFalse(off.rawDiagnosticsAllowed)
        assertFalse(off.backendSelectionOptionsAllowed)
        assertFalse(off.performancePathAllowed)

        val researchOnly = ProductModePolicy.projectedModes(snapshot(research = true))
        assertTrue(researchOnly.rawDiagnosticsAllowed)
        assertTrue(researchOnly.backendSelectionOptionsAllowed)
        assertFalse(researchOnly.performancePathAllowed)

        val riskyOnly = ProductModePolicy.projectedModes(snapshot(risky = true))
        assertFalse(riskyOnly.rawDiagnosticsAllowed)
        assertTrue(riskyOnly.performancePathAllowed)
    }

    @Test
    fun riskyPerformance_gate_refusesWhenModeOff() {
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val ack = ProductModePolicy.RiskAck(
            ackId = "ack-1",
            principalId = "p1",
            riskId = ProductModePolicy.RISKY_PERFORMANCE_RISK_ID,
            policyVersion = 1L,
            acknowledgedAtEpochMs = 1_000L,
        )
        store.save(ack)
        val result = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = false),
            store,
            ack,
            nowEpochMs = 2_000L,
        )
        assertTrue(result is OmniResult.Err)
        assertEquals("FORBIDDEN", (result as OmniResult.Err).error.code.code)
    }

    @Test
    fun riskyPerformance_gate_refusesWithoutAck() {
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val result = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = true),
            store,
            ack = null,
            nowEpochMs = 2_000L,
        )
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun riskyPerformance_gate_refusesUnknownRiskId() {
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val ack = ProductModePolicy.RiskAck(
            ackId = "ack-1",
            principalId = "p1",
            riskId = "some-other-risk",
            policyVersion = 1L,
            acknowledgedAtEpochMs = 1_000L,
        )
        store.save(ack)
        val result = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = true),
            store,
            ack,
            nowEpochMs = 2_000L,
        )
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun riskyPerformance_gate_refusesPolicyVersionMismatch() {
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val ack = ProductModePolicy.RiskAck(
            ackId = "ack-1",
            principalId = "p1",
            riskId = ProductModePolicy.RISKY_PERFORMANCE_RISK_ID,
            policyVersion = 9L,
            acknowledgedAtEpochMs = 1_000L,
        )
        store.save(ack)
        val result = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = true, resourceVersion = 1L),
            store,
            ack,
            nowEpochMs = 2_000L,
        )
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun riskyPerformance_gate_consumesAckOnce() {
        val store = ProductModePolicy.InMemoryRiskAckStore()
        val ack = ProductModePolicy.RiskAck(
            ackId = "ack-1",
            principalId = "p1",
            riskId = ProductModePolicy.RISKY_PERFORMANCE_RISK_ID,
            policyVersion = 1L,
            acknowledgedAtEpochMs = 1_000L,
        )
        store.save(ack)

        val first = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = true),
            store,
            ack,
            nowEpochMs = 2_000L,
        )
        assertTrue(first is OmniResult.Ok)

        // Replay of the same ack must be refused (one ack = one use).
        val replay = ProductModePolicy.requireRiskyPerformanceAccess(
            snapshot(risky = true),
            store,
            ack,
            nowEpochMs = 3_000L,
        )
        assertTrue(replay is OmniResult.Err)
    }

    @Test
    fun catalogSeeds_defaultsForNewKeys() {
        val pm = PolicyManager()
        val snap = pm.settingsSnapshot()
        assertEquals(false, snap.values["product.researchModeEnabled"]?.asBoolOrNull())
        assertEquals(false, snap.values["product.riskyPerformanceModeEnabled"]?.asBoolOrNull())
        assertEquals(
            ConfigurationCatalog.DEFAULT_ANON_MEMORY_CAP_BYTES,
            snap.values["resource.governorAnonMemoryCapBytes"]?.asLongOrNull(),
        )
        assertEquals(
            ConfigurationCatalog.DEFAULT_THREAD_CAP,
            snap.values["resource.governorThreadCap"]?.asLongOrNull(),
        )
    }
}
