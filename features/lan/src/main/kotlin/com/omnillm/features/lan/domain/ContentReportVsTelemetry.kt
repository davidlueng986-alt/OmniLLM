package com.omnillm.features.lan.domain

import com.omnillm.core.canonical.generated.AccessScope

/**
 * Content report is **not** telemetry (SEC-PRIVACY §2/§7, FEAT-LAN report boundary).
 *
 * - Telemetry: optional aggregated operational events; controlled by privacy.telemetryMode
 * - AI content report: user-initiated discrete data flow with review/consent on LOCAL_UI
 *
 * LAN may grant content-reports.propose / read-own / manage-own with explicit approval,
 * but never treats reports as telemetry upload, and never grants review-submit over LAN.
 */
object ContentReportVsTelemetry {

    const val TELEMETRY_SETTING_KEY: String = "privacy.telemetryMode"

    /** Catalog scopes that belong to the AI content-report data flow. */
    val CONTENT_REPORT_SCOPES: Set<String> = setOf(
        AccessScope.content_reports_propose.id,
        AccessScope.content_reports_read_own.id,
        AccessScope.content_reports_manage_own.id,
        AccessScope.content_reports_review_submit.id,
    )

    /** Scopes a LAN client may hold after explicit approval (OpenAPI LanGrantableScope). */
    val LAN_GRANTABLE_REPORT_SCOPES: Set<String> = setOf(
        AccessScope.content_reports_propose.id,
        AccessScope.content_reports_read_own.id,
        AccessScope.content_reports_manage_own.id,
    )

    /** Telemetry modes from configuration-catalog (not report states). */
    val TELEMETRY_MODES: Set<String> = setOf("OFF", "LOCAL_ONLY", "EXPLICIT_EXPORT")

    /**
     * Reports must never be bundled into telemetry export payloads by default.
     * Returns false if a proposed telemetry event type looks like a content report.
     */
    fun isTelemetryEventAllowed(eventType: String): Boolean {
        val t = eventType.trim().lowercase()
        if (t.isEmpty()) return false
        // Fail closed: content-report shaped events are not telemetry.
        if (t.contains("content-report") || t.contains("content_report") ||
            t.contains("ai-report") || t == "report"
        ) {
            return false
        }
        return true
    }

    /**
     * Whether a scope is a content-report scope (distinct from metrics / diagnostics / telemetry).
     */
    fun isContentReportScope(scopeId: String): Boolean = scopeId in CONTENT_REPORT_SCOPES

    /**
     * review-submit is LOCAL_UI / non-exported admin only — never on LAN.
     */
    fun mayGrantOnLan(scopeId: String): Boolean =
        scopeId in LAN_GRANTABLE_REPORT_SCOPES

    /**
     * Telemetry mode changes do not grant or imply content-report consent.
     */
    fun telemetryModeImpliesReportConsent(telemetryMode: String): Boolean {
        // Always false — explicit product invariant (SEC-PRIVACY §7).
        require(telemetryMode in TELEMETRY_MODES || telemetryMode.isBlank()) {
            "unknown telemetry mode (fail closed): $telemetryMode"
        }
        return false
    }

    /**
     * Enabling LAN does not enable telemetry export.
     */
    fun lanEnabledImpliesTelemetryExport(lanEnabled: Boolean): Boolean {
        // Always false regardless of lanEnabled.
        return false && lanEnabled
    }
}
