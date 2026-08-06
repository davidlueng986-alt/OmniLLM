package com.omnillm.features.dashboard.projection

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.dashboard.api.ActionableItemUi
import com.omnillm.features.dashboard.api.DashboardSeverity
import com.omnillm.features.dashboard.api.DashboardSnapshot
import com.omnillm.features.dashboard.api.DashboardUiPhase
import com.omnillm.features.dashboard.api.EvidencedMetricUi
import com.omnillm.features.dashboard.api.HealthSubjectUi
import com.omnillm.features.dashboard.api.LastMeasurementRunUi
import com.omnillm.features.dashboard.api.PerformanceStripUi
import com.omnillm.features.dashboard.api.RequestRowUi
import com.omnillm.features.dashboard.api.ResourceAccountingUi
import com.omnillm.features.dashboard.api.ResourceDimensionUi
import com.omnillm.features.dashboard.api.RuntimeHealthUi
import com.omnillm.features.dashboard.api.TraceSummaryUi
import com.omnillm.features.dashboard.ports.ActiveRequestFact
import com.omnillm.features.dashboard.ports.LastMeasurementFact
import com.omnillm.features.dashboard.ports.LedgerEvidence
import com.omnillm.runtime.governor.ResourceLedger
import com.omnillm.runtime.observability.EvidenceSemantics
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthView
import com.omnillm.runtime.observability.MetricId
import com.omnillm.runtime.observability.MetricSample
import com.omnillm.runtime.observability.MetricSnapshot
import com.omnillm.runtime.observability.RequestTrace
import com.omnillm.runtime.observability.ServiceHealthSnapshot

/**
 * Pure projections for FEAT-DASHBOARD (FEATURE-SYSTEM, UX-STATE-CATALOG).
 *
 * - Canonical RUNTIME / REQUEST states only
 * - Evidence labels on every numeric UI field
 * - UNKNOWN never displayed as 0
 * - Reason → stable action suggestions (do not bypass trust/Governor)
 */
object DashboardProjection {

    private val RUNTIME_STATES: Set<String> = StateMachines.RUNTIME.states
    private val REQUEST_STATES: Set<String> = StateMachines.REQUEST.states

    // ------------------------------------------------------------------
    // Evidence-labelled metrics
    // ------------------------------------------------------------------

    fun projectMetric(sample: MetricSample, nowEpochMs: Long): EvidencedMetricUi {
        val allows = EvidenceSemantics.allowsNumericDisplay(sample.evidenceLabel)
        return EvidencedMetricUi(
            name = sample.name,
            displayValue = if (allows) sample.value else null,
            unit = sample.unit,
            evidenceLabel = sample.evidenceLabel,
            sampledAtEpochMs = sample.sampledAtEpochMs,
            ageMs = (nowEpochMs - sample.sampledAtEpochMs).coerceAtLeast(0L),
            source = sample.source,
            dimensions = sample.dimensions,
            allowsNumericDisplay = allows,
        )
    }

    /**
     * UNKNOWN sample: no numeric display, never invent 0
     * (FEAT-DASHBOARD acceptance §2).
     */
    fun unknownMetric(
        name: String,
        unit: String,
        sampledAtEpochMs: Long,
        nowEpochMs: Long,
        dimensions: Map<String, String> = emptyMap(),
    ): EvidencedMetricUi =
        EvidencedMetricUi(
            name = name,
            displayValue = null,
            unit = unit,
            evidenceLabel = EvidenceLabel.UNKNOWN,
            sampledAtEpochMs = sampledAtEpochMs,
            ageMs = (nowEpochMs - sampledAtEpochMs).coerceAtLeast(0L),
            dimensions = dimensions,
            allowsNumericDisplay = false,
        )

    fun projectMetrics(snapshot: MetricSnapshot, nowEpochMs: Long): List<EvidencedMetricUi> =
        snapshot.samples.map { projectMetric(it, nowEpochMs) }

    // ------------------------------------------------------------------
    // Health
    // ------------------------------------------------------------------

    fun projectHealth(snap: ServiceHealthSnapshot, nowEpochMs: Long): RuntimeHealthUi {
        require(snap.runtimeState in RUNTIME_STATES || snap.runtimeState == "UNKNOWN") {
            "unknown RUNTIME state (fail closed): ${snap.runtimeState}"
        }
        return RuntimeHealthUi(
            runtimeState = snap.runtimeState,
            resourceVersion = snap.resourceVersion,
            overallLevel = snap.overallLevel,
            degradedReasons = snap.degradedReasons,
            subjects = snap.subjects.map { projectSubject(it, nowEpochMs) },
            sampledAtEpochMs = snap.sampledAtEpochMs,
        )
    }

    fun projectSubject(view: HealthView, nowEpochMs: Long): HealthSubjectUi =
        HealthSubjectUi(
            kind = view.subject.kind.name,
            subjectId = view.subject.subjectId,
            level = view.level,
            reasonCodes = view.reasonCodes,
            sinceEpochMs = view.sinceEpochMs,
            sampledAtEpochMs = view.sampledAtEpochMs,
            evidenceLabel = view.evidenceLabel,
            ageMs = view.ageMs(nowEpochMs),
            affectedCapabilities = view.affectedCapabilities,
            automaticActions = view.automaticActions,
            recommendedActions = view.recommendedActions,
            diagnosticSummary = view.diagnosticSummary,
            source = view.source,
        )

    // ------------------------------------------------------------------
    // Resources (conservation-aware)
    // ------------------------------------------------------------------

    /**
     * Project multi-dimensional ledger into UI rows.
     * Per FEAT-DASHBOARD §2/§3 and CORE-RESOURCE conservation.
     */
    fun projectResources(
        ledger: ResourceLedger?,
        evidence: LedgerEvidence,
        nowEpochMs: Long,
    ): ResourceAccountingUi? {
        if (ledger == null) return null
        val inv = ledger.checkInvariants()
        val conservationOk = inv is com.omnillm.core.canonical.generated.OmniResult.Ok
        val dims = RESOURCE_DIMENSIONS.map { dim ->
            projectDimension(dim, ledger, evidence, nowEpochMs, conservationOk)
        }
        return ResourceAccountingUi(
            dimensions = dims,
            conservationOk = conservationOk,
            sampledAtEpochMs = evidence.sampledAtEpochMs,
            evidenceLabel = evidence.evidenceLabel,
        )
    }

    private fun projectDimension(
        dim: ResourceDimensionDef,
        ledger: ResourceLedger,
        evidence: LedgerEvidence,
        nowEpochMs: Long,
        conservationOk: Boolean,
    ): ResourceDimensionUi {
        fun gauge(name: String, value: Long): EvidencedMetricUi {
            // REPORTED worker samples must not silently lower floors; label preserved.
            val allows = EvidenceSemantics.allowsNumericDisplay(evidence.evidenceLabel)
            return EvidencedMetricUi(
                name = name,
                displayValue = if (allows) value.toDouble() else null,
                unit = dim.unit,
                evidenceLabel = evidence.evidenceLabel,
                sampledAtEpochMs = evidence.sampledAtEpochMs,
                ageMs = (nowEpochMs - evidence.sampledAtEpochMs).coerceAtLeast(0L),
                source = evidence.source,
                allowsNumericDisplay = allows,
                dimensions = mapOf("resourceDimension" to dim.key),
            )
        }
        val capacity = dim.read(ledger.capacity)
        val reserved = dim.read(ledger.reserved)
        val allocated = dim.read(ledger.allocated)
        val free = dim.read(ledger.free)
        val safety = dim.read(ledger.safetyMargin)
        val blocked = if (!conservationOk) {
            "RESOURCE_ACCOUNTING_DIVERGENCE"
        } else if (free < safety) {
            "SAFETY_MARGIN_PRESSURE"
        } else {
            null
        }
        return ResourceDimensionUi(
            dimension = dim.key,
            capacity = gauge("resource.capacity_bytes", capacity),
            reserved = gauge(MetricId.RESOURCE_RESERVED_BYTES.wireName, reserved),
            allocated = gauge(MetricId.RESOURCE_ALLOCATED_BYTES.wireName, allocated),
            free = gauge("resource.free_bytes", free),
            safetyMargin = gauge("resource.safety_margin_bytes", safety),
            conservationOk = conservationOk,
            blockedReasonCode = blocked,
        )
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    fun projectRequest(fact: ActiveRequestFact): RequestRowUi {
        require(fact.phase in REQUEST_STATES) {
            "unknown REQUEST state (fail closed): ${fact.phase}"
        }
        return RequestRowUi(
            requestId = fact.requestId,
            correlationId = fact.correlationId,
            principalId = fact.principalId,
            phase = fact.phase,
            labelKey = requestLabelKey(fact.phase),
            severity = requestSeverity(fact.phase),
            engineBuildId = fact.engineBuildId,
            modelRevisionId = fact.modelRevisionId,
            cancelAllowed = fact.cancelAllowed || isCancellable(fact.phase),
            queuePosition = fact.queuePosition,
            deadlineMonotonic = fact.deadlineMonotonic,
            replyLossRecoverable = fact.replyLossRecoverable || fact.phase == "RECONCILING",
        )
    }

    fun requestLabelKey(phase: String): String = when (phase) {
        "RECEIVED", "CLAIMED", "PLANNING" -> "request.preparing"
        "QUEUED" -> "request.queued"
        "RESERVED", "COMMITTING", "PREPARED", "STARTING" -> "request.preparing"
        "STREAMING" -> "request.generating"
        "RECONCILING" -> "request.reconciling"
        "TERMINATING" -> "request.terminating"
        "COMPLETED" -> "request.completed"
        "FAILED" -> "request.failed"
        "CANCELLED" -> "request.cancelled"
        "ABORTED_UNCERTAIN" -> "request.uncertain"
        else -> "request.unknown"
    }

    fun requestSeverity(phase: String): DashboardSeverity = when (phase) {
        "ABORTED_UNCERTAIN", "FAILED" -> DashboardSeverity.ERROR
        "RECONCILING", "TERMINATING" -> DashboardSeverity.WARNING
        else -> DashboardSeverity.INFO
    }

    fun isCancellable(phase: String): Boolean =
        phase in setOf(
            "RECEIVED", "CLAIMED", "PLANNING", "QUEUED", "RESERVED",
            "COMMITTING", "PREPARED", "STARTING", "STREAMING", "RECONCILING",
        )

    fun isTerminal(phase: String): Boolean =
        phase in setOf("COMPLETED", "FAILED", "CANCELLED", "ABORTED_UNCERTAIN")

    // ------------------------------------------------------------------
    // Traces
    // ------------------------------------------------------------------

    fun projectTrace(trace: RequestTrace): TraceSummaryUi {
        val last = trace.events.lastOrNull()
        return TraceSummaryUi(
            correlationId = trace.correlationId,
            requestId = trace.requestId,
            jobId = trace.jobId,
            principalId = trace.principalId,
            eventCount = trace.events.size,
            lastEventName = last?.name,
            lastPhase = last?.phase,
            engineBuildId = trace.engineBuildId,
            modelRevisionId = trace.modelRevisionId,
        )
    }

    // ------------------------------------------------------------------
    // Performance (operational only)
    // ------------------------------------------------------------------

    fun projectPerformance(
        metrics: List<EvidencedMetricUi>,
    ): PerformanceStripUi {
        fun first(name: String): EvidencedMetricUi? =
            metrics.firstOrNull { it.name == name }
        return PerformanceStripUi(
            ttftMs = first(MetricId.REQUEST_TTFT_MS.wireName),
            tokensPerSecond = first(MetricId.REQUEST_TOKENS_PER_SECOND.wireName),
            queueMs = first(MetricId.REQUEST_QUEUE_MS.wireName),
            errorCount = first(MetricId.REQUEST_ERROR_COUNT.wireName),
            thermalStatus = first(MetricId.THERMAL_STATUS.wireName),
            operationalOnly = true,
        )
    }

    // ------------------------------------------------------------------
    // Actionable reasons (FEAT-DASHBOARD §4)
    // ------------------------------------------------------------------

    fun projectActions(
        health: RuntimeHealthUi,
        resources: ResourceAccountingUi?,
        requests: List<RequestRowUi>,
    ): List<ActionableItemUi> {
        val items = mutableListOf<ActionableItemUi>()

        for (code in health.degradedReasons) {
            items += ActionableItemUi(
                reasonCode = code,
                labelKey = reasonLabelKey(code),
                severity = DashboardSeverity.WARNING,
                recommendedActionKeys = reasonActions(code),
                affectedCapabilities = health.subjects
                    .filter { code in it.reasonCodes }
                    .flatMap { it.affectedCapabilities }
                    .distinct(),
            )
        }

        for (subject in health.subjects) {
            if (subject.level == HealthLevel.HEALTHY) continue
            for (code in subject.reasonCodes) {
                if (items.any { it.reasonCode == code }) continue
                items += ActionableItemUi(
                    reasonCode = code,
                    labelKey = reasonLabelKey(code),
                    severity = when (subject.level) {
                        HealthLevel.UNAVAILABLE -> DashboardSeverity.ERROR
                        HealthLevel.DEGRADED, HealthLevel.UNKNOWN -> DashboardSeverity.WARNING
                        HealthLevel.HEALTHY -> DashboardSeverity.INFO
                    },
                    recommendedActionKeys = subject.recommendedActions.ifEmpty {
                        reasonActions(code)
                    },
                    affectedCapabilities = subject.affectedCapabilities,
                )
            }
        }

        if (resources != null && !resources.conservationOk) {
            items += ActionableItemUi(
                reasonCode = "RESOURCE_ACCOUNTING_DIVERGENCE",
                labelKey = "action.resource-accounting-divergence",
                severity = DashboardSeverity.ERROR,
                recommendedActionKeys = listOf("export-diagnostics", "restart-runtime"),
                requiresLocalAdmin = true,
            )
        }
        resources?.dimensions
            ?.filter { it.blockedReasonCode != null }
            ?.forEach { dim ->
                val code = dim.blockedReasonCode!!
                if (items.none { it.reasonCode == code && it.labelKey.contains(dim.dimension) }) {
                    items += ActionableItemUi(
                        reasonCode = code,
                        labelKey = "action.resource-blocked.${dim.dimension}",
                        severity = DashboardSeverity.WARNING,
                        recommendedActionKeys = listOf(
                            "reduce-context",
                            "close-model",
                            "wait-thermal",
                        ),
                    )
                }
            }

        for (req in requests) {
            if (req.phase == "ABORTED_UNCERTAIN") {
                items += ActionableItemUi(
                    reasonCode = "ABORTED_UNCERTAIN",
                    labelKey = "request.uncertain",
                    severity = DashboardSeverity.ERROR,
                    recommendedActionKeys = listOf("query-status", "export-diagnostics", "do-not-reuse-session"),
                )
            }
        }

        return items.distinctBy { it.reasonCode to it.labelKey }
    }

    fun reasonLabelKey(code: String): String = when (code) {
        "THERMAL_THROTTLE", "thermal" -> "action.wait-thermal"
        "BACKEND_FALLBACK" -> "action.backend-fallback"
        "TRUST_DRAIN" -> "action.trust-drain"
        "WORKER_LOSS" -> "action.worker-loss"
        "RESOURCE_ACCOUNTING_DIVERGENCE" -> "action.resource-accounting-divergence"
        "SAFETY_MARGIN_PRESSURE" -> "action.resource-pressure"
        else -> "action.reason.$code"
    }

    fun reasonActions(code: String): List<String> = when (code) {
        "THERMAL_THROTTLE", "thermal" -> listOf("wait-thermal", "reduce-context")
        "BACKEND_FALLBACK" -> listOf("view-reason", "accept-degraded")
        "TRUST_DRAIN" -> listOf("use-safe-mode", "review-risk")
        "WORKER_LOSS" -> listOf("retry-when-safe", "export-diagnostics")
        "SAFETY_MARGIN_PRESSURE" -> listOf("reduce-context", "close-model", "wait")
        "RESOURCE_ACCOUNTING_DIVERGENCE" -> listOf("export-diagnostics", "restart-runtime")
        "ABORTED_UNCERTAIN" -> listOf("query-status", "export-diagnostics")
        else -> listOf("view-reason")
    }

    // ------------------------------------------------------------------
    // Full snapshot
    // ------------------------------------------------------------------

    fun projectSnapshot(
        snapshotVersion: Long,
        health: ServiceHealthSnapshot,
        metricSnapshot: MetricSnapshot,
        ledger: ResourceLedger?,
        ledgerEvidence: LedgerEvidence,
        requests: List<ActiveRequestFact>,
        traces: List<RequestTrace>,
        nowEpochMs: Long,
        lastMeasurement: LastMeasurementFact? = null,
    ): DashboardSnapshot {
        val healthUi = projectHealth(health, nowEpochMs)
        val metricsUi = projectMetrics(metricSnapshot, nowEpochMs)
        val resourcesUi = projectResources(ledger, ledgerEvidence, nowEpochMs)
        val requestRows = requests.map { projectRequest(it) }
        val traceRows = traces.map { projectTrace(it) }
        val actions = projectActions(healthUi, resourcesUi, requestRows)
        val performance = projectPerformance(metricsUi)
        val lastRunUi = lastMeasurement?.let { projectLastMeasurement(it, nowEpochMs) }
        val empty = healthUi.subjects.isEmpty() &&
            metricsUi.isEmpty() &&
            requestRows.isEmpty() &&
            lastRunUi == null &&
            (resourcesUi == null || resourcesUi.dimensions.all { it.capacity.displayValue == 0.0 })
        return DashboardSnapshot(
            snapshotVersion = snapshotVersion,
            sampledAtEpochMs = nowEpochMs,
            health = healthUi,
            resources = resourcesUi,
            performance = performance,
            requests = requestRows,
            traces = traceRows,
            actions = actions,
            metrics = metricsUi,
            isOperationallyEmpty = empty,
            lastMeasurementRun = lastRunUi,
        )
    }

    fun projectLastMeasurement(
        fact: LastMeasurementFact,
        nowEpochMs: Long,
    ): LastMeasurementRunUi {
        fun metric(name: String, value: Double?, unit: String): EvidencedMetricUi? {
            if (value == null && fact.evidenceLabel == EvidenceLabel.UNKNOWN) {
                return unknownMetric(name, unit, fact.startedAtEpochMs, nowEpochMs)
            }
            if (value == null) return null
            val allows = EvidenceSemantics.allowsNumericDisplay(fact.evidenceLabel)
            return EvidencedMetricUi(
                name = name,
                displayValue = if (allows) value else null,
                unit = unit,
                evidenceLabel = fact.evidenceLabel,
                sampledAtEpochMs = fact.completedAtEpochMs ?: fact.startedAtEpochMs,
                ageMs = (nowEpochMs - (fact.completedAtEpochMs ?: fact.startedAtEpochMs))
                    .coerceAtLeast(0L),
                source = fact.fixtureSource,
                allowsNumericDisplay = allows,
            )
        }
        return LastMeasurementRunUi(
            runId = fact.runId,
            profileId = fact.profileId,
            runSeq = fact.runSeq,
            outcome = fact.outcome,
            startedAtEpochMs = fact.startedAtEpochMs,
            completedAtEpochMs = fact.completedAtEpochMs,
            ttftMs = metric("ttft", fact.ttftMs, "ms"),
            throughputTokensPerSec = metric("tokens_per_sec", fact.throughputTokensPerSec, "tok/s"),
            endToEndLatencyMs = metric("e2e_latency", fact.endToEndLatencyMs, "ms"),
            evidenceLabel = fact.evidenceLabel,
            methodVersion = fact.methodVersion,
            fixtureSource = fact.fixtureSource,
            deviationReasons = fact.deviationReasons,
        )
    }

    /**
     * Derive screen phase from load flags + snapshot content
     * (empty / loading / error / degraded / ready).
     */
    fun resolveUiPhase(
        hasSnapshot: Boolean,
        loading: Boolean,
        error: Boolean,
        snapshot: DashboardSnapshot?,
    ): DashboardUiPhase {
        if (loading) return DashboardUiPhase.LOADING
        if (error && !hasSnapshot) return DashboardUiPhase.ERROR
        if (!hasSnapshot) return DashboardUiPhase.EMPTY
        if (error) return DashboardUiPhase.ERROR
        val s = snapshot ?: return DashboardUiPhase.EMPTY
        if (s.isOperationallyEmpty &&
            s.health.overallLevel == HealthLevel.UNKNOWN &&
            s.health.runtimeState in setOf("STOPPED", "UNKNOWN")
        ) {
            return DashboardUiPhase.EMPTY
        }
        val degraded =
            s.health.runtimeState == "DEGRADED" ||
                s.health.overallLevel == HealthLevel.DEGRADED ||
                s.health.overallLevel == HealthLevel.UNAVAILABLE ||
                s.health.runtimeState == "FAULTED"
        return if (degraded) DashboardUiPhase.DEGRADED else DashboardUiPhase.READY
    }

    // ------------------------------------------------------------------
    // Resource dimension catalog (maps ResourceVector fields)
    // ------------------------------------------------------------------

    private data class ResourceDimensionDef(
        val key: String,
        val unit: String,
        val read: (ResourceVector) -> Long,
    )

    private val RESOURCE_DIMENSIONS: List<ResourceDimensionDef> = listOf(
        ResourceDimensionDef("cpuAnonBytes", "bytes") { it.cpuAnonBytes },
        ResourceDimensionDef("cpuFileBytes", "bytes") { it.cpuFileBytes },
        ResourceDimensionDef("sharedMemoryChargeBytes", "bytes") { it.sharedMemoryChargeBytes },
        ResourceDimensionDef("gpuDedicatedBytes", "bytes") { it.gpuDedicatedBytes },
        ResourceDimensionDef("gpuSharedBytes", "bytes") { it.gpuSharedBytes },
        ResourceDimensionDef("npuBytes", "bytes") { it.npuBytes },
        ResourceDimensionDef("nativeThreads", "count") { it.nativeThreads },
        ResourceDimensionDef("fileDescriptors", "count") { it.fileDescriptors },
        ResourceDimensionDef("temporaryDiskBytes", "bytes") { it.temporaryDiskBytes },
        ResourceDimensionDef("networkBytesInFlight", "bytes") { it.networkBytesInFlight },
    )
}
