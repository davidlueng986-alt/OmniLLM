package com.omnillm.features.diagnostics.projection

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.diagnostics.api.DiagnosticJobHandle
import com.omnillm.features.diagnostics.api.DiagnosticsSnapshot
import com.omnillm.features.diagnostics.api.EvidencedMetricView
import com.omnillm.features.diagnostics.api.RedactionAllowlistExport
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.features.diagnostics.domain.DiagnosticReasonModel
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthView
import com.omnillm.runtime.observability.MetricSample

/**
 * Projects JOB + bundle + health into diagnostics UX phase
 * (UX-STATE-CATALOG, FEAT-DIAGNOSTICS empty/loading/error/degraded).
 */
object DiagnosticStateProjection {

    private val JOB_STATES: Set<String> = StateMachines.JOB.states

    fun projectUiPhase(
        bundles: List<DiagnosticBundleSnapshot>,
        activeJob: JobRecord?,
        health: HealthView?,
        lastError: OmniError?,
        hasPreview: Boolean,
    ): String {
        activeJob?.let {
            require(it.state in JOB_STATES) {
                "unknown JOB state (fail closed): ${it.state}"
            }
        }

        if (lastError != null && activeJob == null &&
            bundles.none { it.state == DiagnosticBundleStates.READY }
        ) {
            return DiagnosticUiPhases.ERROR
        }

        if (activeJob != null) {
            when (activeJob.state) {
                "CANCELLED" -> return DiagnosticUiPhases.CANCELLED
                "FAILED" -> return DiagnosticUiPhases.ERROR
                "SUCCEEDED" -> {
                    val ready = bundles.any {
                        it.jobId == activeJob.jobId.value &&
                            it.state == DiagnosticBundleStates.READY
                    }
                    if (ready) {
                        return if (isDegradedHealth(health)) {
                            DiagnosticUiPhases.DEGRADED
                        } else {
                            DiagnosticUiPhases.CONTENT
                        }
                    }
                    // SUCCEEDED but not sealed — still loading seal path
                    return DiagnosticUiPhases.LOADING
                }
                else -> return DiagnosticUiPhases.LOADING
            }
        }

        val readyBundles = bundles.filter { it.state == DiagnosticBundleStates.READY }
        if (readyBundles.isNotEmpty()) {
            return if (isDegradedHealth(health)) {
                DiagnosticUiPhases.DEGRADED
            } else {
                DiagnosticUiPhases.CONTENT
            }
        }

        // Most-recent terminal bundle without READY content.
        if (bundles.any { it.state == DiagnosticBundleStates.CANCELLED }) {
            return DiagnosticUiPhases.CANCELLED
        }
        if (bundles.any { it.state == DiagnosticBundleStates.FAILED }) {
            return DiagnosticUiPhases.ERROR
        }

        if (hasPreview) return DiagnosticUiPhases.PREVIEW

        return DiagnosticUiPhases.EMPTY
    }

    fun projectJobHandle(
        record: JobRecord,
        bundleId: String?,
        createdNew: Boolean = false,
    ): DiagnosticJobHandle =
        DiagnosticJobHandle(
            jobId = record.jobId.value,
            bundleId = bundleId,
            kind = record.kind.name,
            state = record.state,
            resourceVersion = record.resourceVersion,
            createdNew = createdNew,
            cancelRequested = record.cancelRequested,
            progressPhase = record.progress.currentPhase,
            progressRatio = record.progress.ratioOrNull(),
            error = record.error,
        )

    fun projectMetric(sample: MetricSample): EvidencedMetricView {
        val value = if (sample.evidenceLabel == EvidenceLabel.UNKNOWN) {
            // Catalog rule: UNKNOWN must never coerce to zero display as measured.
            // Keep the numeric only when producer supplied it still labelled UNKNOWN.
            sample.value
        } else {
            sample.value
        }
        return EvidencedMetricView(
            name = sample.name,
            value = value,
            unit = sample.unit,
            evidenceLabel = sample.evidenceLabel,
            sampledAtEpochMs = sample.sampledAtEpochMs,
            source = sample.source,
            methodVersion = sample.methodVersion,
            dimensions = sample.dimensions,
        )
    }

    fun projectUnknownMetric(name: String, unit: String, sampledAtEpochMs: Long): EvidencedMetricView =
        EvidencedMetricView(
            name = name,
            value = null,
            unit = unit,
            evidenceLabel = EvidenceLabel.UNKNOWN,
            sampledAtEpochMs = sampledAtEpochMs,
        )

    fun projectSnapshot(
        snapshotVersion: Long,
        bundles: List<DiagnosticBundleSnapshot>,
        activeJob: JobRecord?,
        bundleIdForJob: String?,
        plan: DiagnosticExportPlan?,
        metrics: List<EvidencedMetricView>,
        health: HealthView?,
        reason: DiagnosticReasonModel?,
        lastError: OmniError?,
        hasPreview: Boolean,
        redactionAllowlist: RedactionAllowlistExport? = null,
    ): DiagnosticsSnapshot {
        val phase = projectUiPhase(bundles, activeJob, health, lastError, hasPreview)
        val degraded = mutableListOf<String>()
        if (isDegradedHealth(health)) {
            degraded += health?.reasonCodes.orEmpty()
            if (degraded.isEmpty()) degraded += "service.degraded"
        }
        // Metrics with LAST_SAMPLED / UNKNOWN contribute to degraded strip when content shown.
        if (phase == DiagnosticUiPhases.CONTENT || phase == DiagnosticUiPhases.DEGRADED) {
            val stale = metrics.count {
                it.evidenceLabel == EvidenceLabel.LAST_SAMPLED ||
                    it.evidenceLabel == EvidenceLabel.UNKNOWN
            }
            if (stale > 0 && phase == DiagnosticUiPhases.CONTENT && isDegradedHealth(health)) {
                // already DEGRADED via health
            }
        }
        return DiagnosticsSnapshot(
            snapshotVersion = snapshotVersion,
            uiPhase = phase,
            exportPlan = plan,
            activeJob = activeJob?.let { projectJobHandle(it, bundleIdForJob) },
            bundles = bundles.filter { it.state != DiagnosticBundleStates.DELETED },
            metrics = metrics,
            serviceHealthLevel = health?.level?.name,
            serviceHealthEvidence = health?.evidenceLabel,
            degradedReasons = degraded,
            reason = reason,
            lastError = lastError ?: activeJob?.error,
            redactionAllowlist = redactionAllowlist,
        )
    }

    fun jobStateLabelKey(state: String): String = when (state) {
        "QUEUED" -> "job.queued"
        "RUNNING" -> "job.running"
        "PAUSED_WAITING_INPUT" -> "job.paused-waiting-input"
        "PAUSED_WAITING_NETWORK" -> "job.paused-waiting-network"
        "PAUSED_WAITING_FOREGROUND" -> "job.paused-waiting-foreground"
        "RECOVERING" -> "job.recovering"
        "SUCCEEDED" -> "job.succeeded"
        "FAILED" -> "job.failed"
        "CANCELLED" -> "job.cancelled"
        else -> "job.unknown"
    }

    fun bundleStateLabelKey(state: String): String = when (state) {
        DiagnosticBundleStates.DRAFT -> "diagnostics.bundle.draft"
        DiagnosticBundleStates.COLLECTING -> "diagnostics.bundle.collecting"
        DiagnosticBundleStates.SEALING -> "diagnostics.bundle.sealing"
        DiagnosticBundleStates.READY -> "diagnostics.bundle.ready"
        DiagnosticBundleStates.FAILED -> "diagnostics.bundle.failed"
        DiagnosticBundleStates.CANCELLED -> "diagnostics.bundle.cancelled"
        DiagnosticBundleStates.EXPIRED -> "diagnostics.bundle.expired"
        DiagnosticBundleStates.DELETED -> "diagnostics.bundle.deleted"
        else -> "diagnostics.bundle.unknown"
    }

    fun uiPhaseLabelKey(phase: String): String = when (phase) {
        DiagnosticUiPhases.EMPTY -> "diagnostics.ui.empty"
        DiagnosticUiPhases.PREVIEW -> "diagnostics.ui.preview"
        DiagnosticUiPhases.LOADING -> "diagnostics.ui.loading"
        DiagnosticUiPhases.CONTENT -> "diagnostics.ui.content"
        DiagnosticUiPhases.DEGRADED -> "diagnostics.ui.degraded"
        DiagnosticUiPhases.ERROR -> "diagnostics.ui.error"
        DiagnosticUiPhases.CANCELLED -> "diagnostics.ui.cancelled"
        else -> "diagnostics.ui.unknown"
    }

    private fun isDegradedHealth(health: HealthView?): Boolean {
        if (health == null) return false
        return health.level == HealthLevel.DEGRADED ||
            health.level == HealthLevel.UNKNOWN
    }
}
