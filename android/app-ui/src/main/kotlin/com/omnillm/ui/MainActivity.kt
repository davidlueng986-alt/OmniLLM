package com.omnillm.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.omnillm.ui.navigation.DeepLinkRouter
import com.omnillm.ui.navigation.OmniDestination
import com.omnillm.ui.navigation.OmniNavHost
import com.omnillm.ui.session.UiSession

/**
 * Main UI process activity (INV-001).
 *
 * Binds Admin only via [com.omnillm.ui.admin.AdminRuntimeConnection].
 * Never loads native engines or opens domain DB writers.
 *
 * Deep links use opaque IDs only (UX-IA §3):
 * `omnillm://app/<path>` e.g. `modelhub/detail/{installationId}`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: UiSession

    /**
     * D24: shared navigation state. In-app navigation and warm-start deep
     * links (onNewIntent) converge on this router; Compose observes it.
     */
    private val router = DeepLinkRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = (application as? OmniApplication)?.uiSession
            ?: UiSession(applicationContext).also { it.start() }

        val initial = resolveDestination(intent) ?: defaultDestination()
        router.navigate(initial)

        setContent {
            val uiSession = remember { session }
            DisposableEffect(uiSession) {
                uiSession.start()
                onDispose { /* keep connection for process lifetime; stop in onDestroy */ }
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                OmniNavHost(
                    session = uiSession,
                    router = router,
                    initialDestination = initial,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // D24: warm-start deep links RE-NAVIGATE through the shared router with
        // the same opaque-ID validation as cold start (fail-closed on unknown
        // paths — invalid deep links leave the current destination untouched).
        resolveDestination(intent)?.let(router::navigate)
    }

    override fun onStart() {
        super.onStart()
        if (::session.isInitialized) {
            session.adminConnection.connect()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized && isFinishing) {
            // Keep Admin bound for configuration changes; full stop only when finishing.
            if (!isChangingConfigurations) {
                // Session owned by Application when present.
                if ((application as? OmniApplication)?.uiSession == null) {
                    session.stop()
                }
            }
        }
        super.onDestroy()
    }

    private fun defaultDestination(): OmniDestination =
        if (session.onboardingComplete) {
            OmniDestination.Home
        } else {
            // First launch intent split: Home still reachable; Setup is primary CTA (UX-IA §2).
            OmniDestination.Home
        }

    private fun resolveDestination(intent: Intent?): OmniDestination? {
        if (intent == null) return null
        val data = intent.data ?: return null
        // Accept omnillm://app/... or https host-style with path only.
        val path = data.path?.trimStart('/')
            ?: data.schemeSpecificPart?.substringAfter("app/")?.substringBefore('?')
        return path?.takeIf { it.isNotBlank() }?.let { OmniDestination.fromDeepLinkPath(it) }
            // D24: full-URI fallback keeps warm/cold resolution on one parser.
            ?: OmniDestination.fromDeepLinkUri(data.toString())
    }
}
