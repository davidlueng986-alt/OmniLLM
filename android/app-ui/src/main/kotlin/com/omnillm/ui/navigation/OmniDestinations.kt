package com.omnillm.ui.navigation

/**
 * Navigation tree aligned with UX-IA.
 *
 * Deep-link parameters must be opaque resource IDs only
 * (installation / revision / job / request / client / report) — never file paths or secrets.
 */
sealed class OmniDestination(
    val route: String,
    val isPrimary: Boolean = false,
) {
    data object Home : OmniDestination("home", isPrimary = true)
    data object ModelHub : OmniDestination("modelhub", isPrimary = true)
    data object Playground : OmniDestination("playground", isPrimary = true)
    data object ServerClients : OmniDestination("server_clients", isPrimary = true)
    data object Dashboard : OmniDestination("dashboard", isPrimary = true)

    data object Settings : OmniDestination("settings")
    data object Diagnostics : OmniDestination("diagnostics")
    data object Onboarding : OmniDestination("onboarding")
    data object ContentReport : OmniDestination("content_report")
    /** Expert routing / fallback policy controls (FEAT-ROUTING). */
    data object Routing : OmniDestination("routing")
    /** Benchmark research workspace (FEAT-BENCHMARK). */
    data object Benchmark : OmniDestination("benchmark")
    /** FEAT-LAN management (also available as Server tab); deep-link `lan`. */
    data object Lan : OmniDestination("lan")

    data class ModelDetail(val installationId: String) :
        OmniDestination("modelhub/detail/$installationId")

    data class JobDetail(val jobId: String) :
        OmniDestination("jobs/$jobId")

    data class RequestDetail(val requestId: String) :
        OmniDestination("requests/$requestId")

    data class ReportDetail(val reportId: String) :
        OmniDestination("content_report/$reportId")

    companion object {
        /**
         * Lazy: nested data objects are not always initialized when the companion
         * runs (eager listOf(Home, …) can capture nulls → bottom-bar crash).
         */
        val primaryRail: List<OmniDestination> by lazy {
            listOf(
                Home,
                ModelHub,
                Playground,
                ServerClients,
                Dashboard,
            )
        }

        val moreDestinations: List<OmniDestination> by lazy {
            listOf(
                Settings,
                Diagnostics,
                Onboarding,
                ContentReport,
                Lan,
                Routing,
                Benchmark,
            )
        }

        /**
         * Parse a full deep-link URI (`omnillm://app/<path>` or host-style
         * `https://…/<path>`). D24: warm-start re-navigation (onNewIntent)
         * shares this resolver with cold start — one validation path,
         * fail-closed on unknown schemes / routes.
         */
        fun fromDeepLinkUri(uri: String?): OmniDestination? {
            if (uri.isNullOrBlank()) return null
            val lower = uri.lowercase()
            val path: String = when {
                lower.startsWith("omnillm://") ->
                    stripQueryFragment(uri.substringAfter("//").substringAfter('/', missingDelimiterValue = ""))
                lower.startsWith("omnillm:") ->
                    stripQueryFragment(uri.substringAfter("omnillm:"))
                lower.startsWith("https://") || lower.startsWith("http://") ->
                    stripQueryFragment(uri.substringAfter("//").substringAfter('/', missingDelimiterValue = ""))
                else -> return null
            }
            return fromDeepLinkPath(path)
        }

        private fun stripQueryFragment(value: String): String =
            value.substringBefore('?').substringBefore('#')

        /**
         * Parse deep-link path segments. Only opaque IDs are accepted.
         * Fail closed on unknown routes (INV-018 spirit for navigation).
         */
        fun fromDeepLinkPath(path: String?): OmniDestination? {
            if (path.isNullOrBlank()) return null
            val trimmed = path.trim().trimStart('/')
            val parts = trimmed.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return Home
            return when (parts[0]) {
                "home" -> Home
                "modelhub" -> {
                    if (parts.size >= 3 && parts[1] == "detail") {
                        val id = parts[2]
                        if (isOpaqueId(id)) ModelDetail(id) else null
                    } else {
                        ModelHub
                    }
                }
                "playground" -> Playground
                "server_clients", "server" -> ServerClients
                "lan" -> Lan
                "dashboard" -> Dashboard
                "settings" -> Settings
                "diagnostics" -> Diagnostics
                "onboarding", "setup" -> Onboarding
                "content_report" -> {
                    if (parts.size >= 2) {
                        val id = parts[1]
                        if (isOpaqueId(id)) ReportDetail(id) else null
                    } else {
                        ContentReport
                    }
                }
                "routing" -> Routing
                "benchmark" -> Benchmark
                "jobs" -> {
                    val id = parts.getOrNull(1) ?: return null
                    if (isOpaqueId(id)) JobDetail(id) else null
                }
                "requests" -> {
                    val id = parts.getOrNull(1) ?: return null
                    if (isOpaqueId(id)) RequestDetail(id) else null
                }
                else -> null
            }
        }

        /** Opaque IDs: hex digests, UUIDs, or catalog opaque tokens — not paths. */
        fun isOpaqueId(value: String): Boolean {
            if (value.isBlank() || value.length > 128) return false
            if (value.contains('/') || value.contains('\\') || value.contains("..")) return false
            // UUID
            if (UUID_RE.matches(value.lowercase())) return true
            // 64-char hex digest
            if (HEX64.matches(value.lowercase())) return true
            // Generic opaque token (alnum + limited punctuation)
            return OPAQUE_TOKEN.matches(value)
        }

        private val UUID_RE =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val OPAQUE_TOKEN = Regex("^[A-Za-z0-9._:-]{1,128}$")
    }
}
