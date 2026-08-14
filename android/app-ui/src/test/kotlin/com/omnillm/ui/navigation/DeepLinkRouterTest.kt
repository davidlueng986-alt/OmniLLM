package com.omnillm.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D24 regression: a deep link delivered to an already-warm singleTop activity
 * (Activity.onNewIntent) must RE-NAVIGATE. Before the fix MainActivity only
 * re-set the intent (`setIntent`) and the navigation state inside OmniNavHost
 * never moved — warm-start deep links were silently dropped.
 *
 * RED note (C-06 convention in this module): this test compiles against the
 * intended seam `DeepLinkRouter` + `OmniDestination.fromDeepLinkUri` — the
 * missing seam IS the defect. The test cannot compile until the wiring exists;
 * after the fix it proves warm-start deep links resolve, navigate, and fail
 * closed on unknown / non-opaque paths.
 */
class DeepLinkRouterTest {

    private val opaqueId =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun warmStartDeepLink_navigatesToResolvedDetail() {
        val router = DeepLinkRouter(OmniDestination.Home)

        val handled = router.handleDeepLink("omnillm://app/modelhub/detail/$opaqueId")

        assertTrue("warm-start deep link must be handled (D24)", handled)
        assertEquals(
            "navigation state must move to the resolved detail destination (D24)",
            OmniDestination.ModelDetail(opaqueId),
            router.current,
        )
    }

    @Test
    fun warmStartDeepLink_plainRoute_navigates() {
        val router = DeepLinkRouter(OmniDestination.Home)

        assertTrue(router.handleDeepLink("omnillm://app/dashboard"))

        assertEquals(OmniDestination.Dashboard, router.current)
    }

    @Test
    fun unknownPath_failClosed_keepsCurrentDestination() {
        val router = DeepLinkRouter(OmniDestination.Home)

        val handled = router.handleDeepLink("omnillm://app/modelhub/detail/../etc/passwd")

        assertFalse("non-opaque ids must fail closed (D24)", handled)
        assertEquals(OmniDestination.Home, router.current)
    }

    @Test
    fun unknownRoute_failClosed_keepsCurrentDestination() {
        val router = DeepLinkRouter(OmniDestination.Home)

        assertFalse(router.handleDeepLink("omnillm://app/not-a-route"))
        assertEquals(OmniDestination.Home, router.current)
    }

    @Test
    fun nonOmniScheme_isIgnored() {
        val router = DeepLinkRouter(OmniDestination.Home)

        assertFalse(router.handleDeepLink("https://example.com/not/ours"))
        assertEquals(OmniDestination.Home, router.current)
    }

    @Test
    fun fromDeepLinkUri_parsesAppSchemeAndHostStyle() {
        assertEquals(
            OmniDestination.ModelDetail(opaqueId),
            OmniDestination.fromDeepLinkUri("omnillm://app/modelhub/detail/$opaqueId"),
        )
        assertEquals(
            OmniDestination.ModelHub,
            OmniDestination.fromDeepLinkUri("https://omnillm.app/modelhub"),
        )
        assertNull(
            "host-only deep link has no path and must fail closed",
            OmniDestination.fromDeepLinkUri("omnillm://app"),
        )
        assertNull(OmniDestination.fromDeepLinkUri(null))
    }
}
