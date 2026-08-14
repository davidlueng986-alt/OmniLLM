package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 / C-12: the product-mode keys (`product.researchModeEnabled` /
 * `product.riskyPerformanceModeEnabled`) must be settable through the existing
 * settings apply-patch path used by AdminApiService (source
 * `administrator-policy`, LOCAL_ADMIN class) and must stay fail-closed on a
 * fresh manager.
 */
class ProductModeSettingsPathTest {

    @Test
    fun patchSettings_administratorSource_canFlipResearchMode() {
        val pm = PolicyManager()
        val patched = pm.patchSettings(
            baseVersion = pm.settingsSnapshot().resourceVersion,
            changes = mapOf(
                "product.researchModeEnabled" to SettingValue.BoolValue(true),
            ),
            source = "administrator-policy",
        )
        assertTrue("patch must succeed: $patched", patched is OmniResult.Ok)
        val snap = pm.settingsSnapshot()
        assertTrue(snap.values["product.researchModeEnabled"]?.asBoolOrNull() == true)
        assertEquals(1L, snap.resourceVersion)
    }

    @Test
    fun patchSettings_administratorSource_canFlipRiskyPerformanceMode() {
        val pm = PolicyManager()
        val patched = pm.patchSettings(
            baseVersion = pm.settingsSnapshot().resourceVersion,
            changes = mapOf(
                "product.riskyPerformanceModeEnabled" to SettingValue.BoolValue(true),
            ),
            source = "administrator-policy",
        )
        assertTrue("patch must succeed: $patched", patched is OmniResult.Ok)
        val snap = pm.settingsSnapshot()
        assertTrue(snap.values["product.riskyPerformanceModeEnabled"]?.asBoolOrNull() == true)
        assertEquals("administrator-policy", snap.effective["product.riskyPerformanceModeEnabled"]?.selectedSource)
    }

    @Test
    fun patchSettings_foreignSource_refusedForProductModes() {
        val pm = PolicyManager()
        val refused = pm.patchSettings(
            baseVersion = pm.settingsSnapshot().resourceVersion,
            changes = mapOf(
                "product.researchModeEnabled" to SettingValue.BoolValue(true),
            ),
            source = "request-explicit",
        )
        assertTrue("non-admin source must be refused: $refused", refused is OmniResult.Err)
    }

    @Test
    fun freshManager_productModes_failClosed() {
        val snap = PolicyManager().settingsSnapshot()
        assertFalse(snap.values["product.researchModeEnabled"]?.asBoolOrNull() == true)
        assertFalse(snap.values["product.riskyPerformanceModeEnabled"]?.asBoolOrNull() == true)
    }
}

