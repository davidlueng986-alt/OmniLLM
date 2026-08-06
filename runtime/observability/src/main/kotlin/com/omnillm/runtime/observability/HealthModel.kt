package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel

/**
 * Health level vocabulary from CORE-OBSERVABILITY §3:
 * `HEALTHY / DEGRADED / UNAVAILABLE / UNKNOWN`.
 *
 * Engine crash does not necessarily fault the whole service; repeated crash,
 * trust failure, DB integrity, or resource accounting divergence may escalate.
 */
enum class HealthLevel {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
    ;

    companion object {
        fun fromCatalogName(name: String): HealthLevel? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): HealthLevel =
            fromCatalogName(name)
                ?: throw IllegalArgumentException("Unknown HealthLevel: $name")
    }

    /**
     * Severity rank for aggregation (higher = worse).
     * UNKNOWN is worse than HEALTHY but does not dominate DEGRADED/UNAVAILABLE
     * when a concrete degraded/unavailable signal exists.
     */
    val severityRank: Int
        get() = when (this) {
            HEALTHY -> 0
            UNKNOWN -> 1
            DEGRADED -> 2
            UNAVAILABLE -> 3
        }
}

/**
 * Observation subjects from CORE-OBSERVABILITY §1.
 * Each has a health / state view.
 */
enum class HealthSubjectKind {
    SERVICE,
    ENGINE_MODULE,
    LOADED_MODEL,
    SESSION,
    REQUEST,
    JOB,
    CLIENT_PRINCIPAL,
    STORAGE,
    CATALOG,
    DEVICE,
    THERMAL,
    RESOURCE_GOVERNOR,
    ;

    companion object {
        fun fromName(name: String): HealthSubjectKind? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * Identity of a health-reporting subject.
 * [subjectId] is opaque (engineBuildId, sessionId, etc.).
 */
data class HealthSubject(
    val kind: HealthSubjectKind,
    val subjectId: String,
) {
    init {
        require(subjectId.isNotBlank()) { "subjectId must be non-blank" }
    }
}

/**
 * Reasoned health view (CORE-OBSERVABILITY §3, OpenAPI Health projection).
 *
 * Always carries evidence freshness so Dashboard can answer
 * "measured / estimated / stale / unknown".
 */
data class HealthView(
    val subject: HealthSubject,
    val level: HealthLevel,
    val reasonCodes: List<String> = emptyList(),
    val sinceEpochMs: Long,
    val sampledAtEpochMs: Long,
    val evidenceLabel: EvidenceLabel,
    val affectedCapabilities: List<String> = emptyList(),
    val automaticActions: List<String> = emptyList(),
    val recommendedActions: List<String> = emptyList(),
    /** Optional free-text diagnostic summary — already redacted. */
    val diagnosticSummary: String? = null,
    val source: String? = null,
) {
    init {
        require(sinceEpochMs >= 0L) { "sinceEpochMs must be non-negative" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        require(diagnosticSummary == null || diagnosticSummary.length <= 4096) {
            "diagnosticSummary max 4096 bytes/chars (canonical-types)"
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED health must disclose source"
            }
        }
        for (code in reasonCodes) {
            require(code.isNotBlank()) { "reasonCodes must be non-blank" }
        }
    }

    fun ageMs(nowEpochMs: Long): Long =
        (nowEpochMs - sampledAtEpochMs).coerceAtLeast(0L)
}

/**
 * Aggregate service-level health (OpenAPI `/health` projection inputs).
 *
 * [runtimeState] uses the Runtime FSM labels from state-machines.yaml
 * (STOPPED/STARTING/RECOVERING/READY/DEGRADED/DRAINING/FAULTED/…).
 * This is distinct from [HealthLevel] on individual subjects.
 */
data class ServiceHealthSnapshot(
    val runtimeState: String,
    val resourceVersion: Long,
    val overallLevel: HealthLevel,
    val degradedReasons: List<String>,
    val subjects: List<HealthView>,
    val sampledAtEpochMs: Long,
) {
    init {
        require(runtimeState.isNotBlank()) { "runtimeState must be non-blank" }
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
    }
}

/**
 * Pure aggregation of subject health into an overall level.
 *
 * - Any UNAVAILABLE → UNAVAILABLE
 * - Else any DEGRADED → DEGRADED
 * - Else any UNKNOWN (and no concrete healthy-only) → UNKNOWN if no HEALTHY either,
 *   or HEALTHY if all known are HEALTHY and some UNKNOWN remain mixed → DEGRADED
 *   only when policy chooses; default: UNKNOWN present with HEALTHY others → DEGRADED
 *   when affected capabilities listed, else UNKNOWN if *all* unknown, else HEALTHY.
 */
object HealthAggregator {

    fun overallLevel(views: Collection<HealthView>): HealthLevel {
        if (views.isEmpty()) return HealthLevel.UNKNOWN
        var hasHealthy = false
        var hasUnknown = false
        var hasDegraded = false
        var hasUnavailable = false
        for (v in views) {
            when (v.level) {
                HealthLevel.HEALTHY -> hasHealthy = true
                HealthLevel.UNKNOWN -> hasUnknown = true
                HealthLevel.DEGRADED -> hasDegraded = true
                HealthLevel.UNAVAILABLE -> hasUnavailable = true
            }
        }
        return when {
            hasUnavailable -> HealthLevel.UNAVAILABLE
            hasDegraded -> HealthLevel.DEGRADED
            hasUnknown && hasHealthy -> HealthLevel.DEGRADED
            hasUnknown -> HealthLevel.UNKNOWN
            hasHealthy -> HealthLevel.HEALTHY
            else -> HealthLevel.UNKNOWN
        }
    }

    fun collectReasons(views: Collection<HealthView>): List<String> =
        views
            .filter { it.level == HealthLevel.DEGRADED || it.level == HealthLevel.UNAVAILABLE }
            .flatMap { it.reasonCodes }
            .distinct()
            .sorted()
}
