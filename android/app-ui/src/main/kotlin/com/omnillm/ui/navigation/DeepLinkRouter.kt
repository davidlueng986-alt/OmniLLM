package com.omnillm.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * D24: warm-start deep-link router.
 *
 * Single source of truth for the active navigation destination. In-app
 * navigation and external deep links (Activity.onNewIntent on a singleTop
 * activity) converge through [navigate] / [handleDeepLink], so a deep link
 * delivered to an already-warm activity RE-NAVIGATES instead of being
 * silently dropped.
 *
 * Fail-closed: [handleDeepLink] only moves the destination for URIs that
 * resolve through the same opaque-ID validation as cold start
 * ([OmniDestination.fromDeepLinkUri]); unknown schemes / routes leave the
 * current destination untouched.
 */
class DeepLinkRouter(
    initial: OmniDestination = OmniDestination.Home,
) {
    var current by mutableStateOf(initial)
        private set

    /** In-app navigation (bottom bar / drawer / screen CTAs). */
    fun navigate(destination: OmniDestination) {
        current = destination
    }

    /**
     * Handle an external deep-link URI on a warm activity.
     * @return true when the URI resolved to a destination (navigated),
     *   false when it failed validation (fail-closed, current stays).
     */
    fun handleDeepLink(uri: String?): Boolean {
        val destination = OmniDestination.fromDeepLinkUri(uri) ?: return false
        current = destination
        return true
    }
}
