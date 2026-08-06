package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks per-subject health views and builds service snapshots
 * (CORE-OBSERVABILITY §3, OpenAPI Health).
 */
class HealthRegistry(
    private val clockWallMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val subjects = linkedMapOf<HealthSubject, HealthView>()
    private val resourceVersion = AtomicLong(0L)
    @Volatile
    private var runtimeState: String = "UNKNOWN"

    fun setRuntimeState(state: String) {
        require(state.isNotBlank()) { "runtimeState must be non-blank" }
        runtimeState = state
        resourceVersion.incrementAndGet()
    }

    fun runtimeState(): String = runtimeState

    fun upsert(view: HealthView) = synchronized(lock) {
        subjects[view.subject] = view
        resourceVersion.incrementAndGet()
    }

    /**
     * Convenience builder for a subject transition.
     */
    fun report(
        kind: HealthSubjectKind,
        subjectId: String,
        level: HealthLevel,
        reasonCodes: List<String> = emptyList(),
        evidenceLabel: EvidenceLabel = EvidenceLabel.MEASURED,
        affectedCapabilities: List<String> = emptyList(),
        automaticActions: List<String> = emptyList(),
        recommendedActions: List<String> = emptyList(),
        diagnosticSummary: String? = null,
        source: String? = null,
        sinceEpochMs: Long? = null,
        sampledAtEpochMs: Long? = null,
    ) {
        val now = clockWallMs()
        val subject = HealthSubject(kind, subjectId)
        val existing = synchronized(lock) { subjects[subject] }
        val since = sinceEpochMs
            ?: if (existing != null && existing.level == level) existing.sinceEpochMs else now
        upsert(
            HealthView(
                subject = subject,
                level = level,
                reasonCodes = reasonCodes,
                sinceEpochMs = since,
                sampledAtEpochMs = sampledAtEpochMs ?: now,
                evidenceLabel = evidenceLabel,
                affectedCapabilities = affectedCapabilities,
                automaticActions = automaticActions,
                recommendedActions = recommendedActions,
                diagnosticSummary = diagnosticSummary?.let { Redactor.redactFreeText(it) },
                source = source,
            ),
        )
    }

    fun get(subject: HealthSubject): HealthView? = synchronized(lock) { subjects[subject] }

    fun all(): List<HealthView> = synchronized(lock) { subjects.values.toList() }

    fun clear(subject: HealthSubject) = synchronized(lock) {
        if (subjects.remove(subject) != null) {
            resourceVersion.incrementAndGet()
        }
    }

    fun snapshot(): ServiceHealthSnapshot = synchronized(lock) {
        val views = subjects.values.toList()
        val now = clockWallMs()
        ServiceHealthSnapshot(
            runtimeState = runtimeState,
            resourceVersion = resourceVersion.get(),
            overallLevel = HealthAggregator.overallLevel(views),
            degradedReasons = HealthAggregator.collectReasons(views),
            subjects = views,
            sampledAtEpochMs = now,
        )
    }
}
