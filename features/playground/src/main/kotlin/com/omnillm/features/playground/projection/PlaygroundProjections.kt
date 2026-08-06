package com.omnillm.features.playground.projection

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.playground.api.CapabilityCellView
import com.omnillm.features.playground.api.EvidencedMetricUi
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.features.playground.api.RequestStripUi
import com.omnillm.features.playground.api.TabCapabilityView
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.PortMetricSample
import com.omnillm.runtime.observability.EvidenceSemantics

/**
 * UX action keys for playground (not domain events).
 * Mapped from ux-projection-catalog.yaml where applicable.
 */
enum class PlaygroundUiAction {
    CANCEL,
    WAIT,
    VIEW_DETAILS,
    QUERY_STATUS,
    VIEW_REASON,
    RETRY_WHEN_SAFE,
    RUN_QUALIFICATION,
    CHOOSE_SAFE_OPTION,
    SELECT_ANOTHER_MODEL,
    START_NEW_SESSION,
    DO_NOT_REUSE_SESSION,
    EXPORT_DIAGNOSTICS,
    REDUCE_CONTEXT,
}

enum class UiSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Cancel phase progression (FEAT-PLAYGROUND §2).
 * Order is normative for UI disclosure.
 */
enum class CancelPhase {
    /** Client has issued cancel. */
    REQUESTED,
    /** Control plane accepted cancel intent. */
    ACKNOWLEDGED,
    /** Engine / execution path has stopped generating. */
    EXECUTION_STOPPED,
    /** Durable REQUEST terminal (CANCELLED / …). */
    TERMINAL,
}

/**
 * Projects CapabilityState cells onto distinct UX copy (UX-STATE §6, FEAT-PLAYGROUND §1).
 * UNKNOWN must never be labelled as "unsupported".
 */
object CapabilityUiProjection {

    fun projectCell(
        capabilityId: CapabilityId,
        state: CapabilityState,
        conditions: List<String> = emptyList(),
    ): CapabilityCellView {
        val (labelKey, explanationKey, severity, actions) = when (state) {
            CapabilityState.SUPPORTED -> Quad(
                "capability.supported",
                "capability.supported.detail",
                UiSeverity.INFO,
                emptyList(),
            )
            CapabilityState.UNSUPPORTED -> Quad(
                "capability.unsupported",
                "capability.unsupported.select-supported-operation",
                UiSeverity.ERROR,
                listOf(PlaygroundUiAction.SELECT_ANOTHER_MODEL, PlaygroundUiAction.CHOOSE_SAFE_OPTION),
            )
            CapabilityState.CONDITIONAL -> Quad(
                "capability.conditional",
                "capability.conditional.view-conditions",
                UiSeverity.WARNING,
                listOf(PlaygroundUiAction.VIEW_REASON, PlaygroundUiAction.VIEW_DETAILS),
            )
            CapabilityState.UNKNOWN -> Quad(
                "capability.unknown",
                "capability.unknown.run-qualification",
                UiSeverity.WARNING,
                listOf(PlaygroundUiAction.RUN_QUALIFICATION, PlaygroundUiAction.CHOOSE_SAFE_OPTION),
            )
            CapabilityState.TEMPORARILY_UNAVAILABLE -> Quad(
                "capability.temporarily-unavailable",
                "capability.temporarily-unavailable.retry-when-safe",
                UiSeverity.WARNING,
                listOf(PlaygroundUiAction.VIEW_REASON, PlaygroundUiAction.RETRY_WHEN_SAFE),
            )
        }
        return CapabilityCellView(
            capabilityId = capabilityId,
            state = state,
            labelKey = labelKey,
            explanationKey = explanationKey,
            conditions = if (state == CapabilityState.CONDITIONAL) conditions else emptyList(),
            severity = severity,
            allowedActions = actions,
        )
    }

    /**
     * Required capabilities per tab (feature-capability-map + FEAT-PLAYGROUND §1–5).
     * STRUCTURED_TOOLS surfaces STRUCTURED_OUTPUT / TOOL_CALLING as conditional
     * extensions on TEXT_GENERATION (owned primarily by FEAT-TOOLS).
     */
    fun requiredCapabilities(tab: PlaygroundTab): List<CapabilityId> = when (tab) {
        PlaygroundTab.CHAT -> listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.REQUEST_LIFECYCLE,
            CapabilityId.CANCELLATION,
        )
        PlaygroundTab.EMBEDDINGS -> listOf(
            CapabilityId.EMBEDDING,
            CapabilityId.REQUEST_LIFECYCLE,
            CapabilityId.CANCELLATION,
        )
        PlaygroundTab.VISION_AUDIO -> listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.VISION_INPUT,
            CapabilityId.AUDIO_INPUT,
            CapabilityId.REQUEST_LIFECYCLE,
            CapabilityId.CANCELLATION,
        )
        PlaygroundTab.STRUCTURED_TOOLS -> listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.TOOL_CALLING,
            CapabilityId.REQUEST_LIFECYCLE,
            CapabilityId.CANCELLATION,
        )
    }

    /**
     * A tab is operable when every required capability is SUPPORTED or CONDITIONAL.
     * UNKNOWN / UNSUPPORTED / TEMPORARILY_UNAVAILABLE block submission (fail closed).
     */
    fun projectTab(
        tab: PlaygroundTab,
        cells: List<CapabilityCellView>,
    ): TabCapabilityView {
        val blocking = cells.firstOrNull {
            it.state != CapabilityState.SUPPORTED && it.state != CapabilityState.CONDITIONAL
        }
        return TabCapabilityView(
            tab = tab,
            required = cells,
            operable = blocking == null,
            blockingReasonKey = blocking?.explanationKey,
        )
    }

    private data class Quad(
        val labelKey: String,
        val explanationKey: String,
        val severity: UiSeverity,
        val actions: List<PlaygroundUiAction>,
    )
}

/**
 * Projects REQUEST FSM state onto UX labels/actions (UX-STATE §4, ux-projection-catalog).
 * Does not invent states — unknown REQUEST states fail closed (INV-018).
 */
object RequestUiProjection {

    val MACHINE = StateMachines.REQUEST

    private val TERMINAL: Set<String> = setOf(
        "COMPLETED",
        "FAILED",
        "CANCELLED",
        "ABORTED_UNCERTAIN",
    )

    fun isTerminal(state: String): Boolean =
        state in TERMINAL || (MACHINE.isKnownState(state) && MACHINE.isTerminal(state))

    fun project(handle: InferenceHandle): RequestStripUi {
        val state = handle.state
        val known = MACHINE.isKnownState(state) || state in TERMINAL ||
            state in LEGACY_ALIASES
        val labelKey = labelKeyFor(state, known)
        val severity = severityFor(state, handle.error != null, known)
        val terminal = isTerminal(state)
        val actions = allowedActions(state, handle.cancelPhase, terminal, known)
        val dims = handle.embeddingDimensions
            ?: handle.embeddingVectors?.firstOrNull()?.size
        return RequestStripUi(
            requestId = handle.requestId,
            operationKind = handle.operationKind,
            state = state,
            labelKey = labelKey,
            severity = severity,
            isTerminal = terminal,
            cancelPhase = handle.cancelPhase,
            allowedActions = actions,
            actualModelRevisionId = handle.actualModelRevisionId,
            engineBuildId = handle.engineBuildId,
            backend = handle.backend,
            sessionId = handle.sessionId,
            degraded = handle.degraded,
            degradedReasons = handle.degradedReasons,
            metrics = handle.metrics.map { MetricsUiProjection.project(it) },
            error = handle.error,
            assistantText = handle.assistantText,
            embeddingDimensions = dims,
            embeddingCount = handle.embeddingVectors?.size,
        )
    }

    fun allowedActions(
        state: String,
        cancelPhase: CancelPhase?,
        terminal: Boolean,
        known: Boolean,
    ): List<PlaygroundUiAction> {
        if (!known) return listOf(PlaygroundUiAction.VIEW_DETAILS, PlaygroundUiAction.QUERY_STATUS)
        if (terminal) {
            return when (state) {
                "ABORTED_UNCERTAIN" -> listOf(
                    PlaygroundUiAction.DO_NOT_REUSE_SESSION,
                    PlaygroundUiAction.EXPORT_DIAGNOSTICS,
                    PlaygroundUiAction.QUERY_STATUS,
                )
                "CANCELLED" -> listOf(PlaygroundUiAction.VIEW_DETAILS)
                "FAILED" -> listOf(PlaygroundUiAction.VIEW_DETAILS, PlaygroundUiAction.QUERY_STATUS)
                else -> listOf(PlaygroundUiAction.VIEW_DETAILS)
            }
        }
        val actions = mutableListOf(PlaygroundUiAction.VIEW_DETAILS)
        when (state) {
            "RECONCILING" -> {
                actions += PlaygroundUiAction.WAIT
                actions += PlaygroundUiAction.QUERY_STATUS
            }
            "STREAMING", "STARTING", "QUEUED", "RESERVED", "PREPARED",
            "PLANNING", "CLAIMED", "RECEIVED", "COMMITTING",
            -> {
                if (cancelPhase == null || cancelPhase == CancelPhase.REQUESTED) {
                    actions += PlaygroundUiAction.CANCEL
                } else {
                    actions += PlaygroundUiAction.WAIT
                }
            }
            else -> actions += PlaygroundUiAction.QUERY_STATUS
        }
        return actions.distinct()
    }

    fun labelKeyFor(state: String, known: Boolean = true): String {
        if (!known && state !in LEGACY_ALIASES) return "request.unknown-state"
        return when (state) {
            "RECEIVED", "CLAIMED", "PLANNING" -> "request.preparing"
            "QUEUED" -> "request.queued"
            "RESERVED", "COMMITTING", "PREPARED", "STARTING" -> "request.preparing"
            "STREAMING" -> "request.generating"
            "TERMINATING" -> "request.terminating"
            "RECONCILING" -> "request.reconciling"
            "COMPLETED" -> "request.completed"
            "FAILED" -> "request.failed"
            "CANCELLED" -> "request.cancelled"
            "ABORTED_UNCERTAIN" -> "request.uncertain"
            else -> "request.unknown-state"
        }
    }

    private fun severityFor(state: String, hasError: Boolean, known: Boolean): UiSeverity {
        if (!known) return UiSeverity.ERROR
        return when (state) {
            "COMPLETED" -> UiSeverity.INFO
            "FAILED", "ABORTED_UNCERTAIN" -> UiSeverity.ERROR
            "CANCELLED", "RECONCILING", "TERMINATING" -> UiSeverity.WARNING
            else -> if (hasError) UiSeverity.WARNING else UiSeverity.INFO
        }
    }

    /** Transport / port aliases that may appear before full REQUEST bind. */
    private val LEGACY_ALIASES: Set<String> = setOf(
        "ACCEPTED",
        "RUNNING",
        "CANCELLING",
    )
}

/**
 * Cancel phase → label keys (FEAT-PLAYGROUND §2 cancel disclosure).
 */
object CancelPhaseProjection {

    fun labelKey(phase: CancelPhase): String = when (phase) {
        CancelPhase.REQUESTED -> "request.cancel.requested"
        CancelPhase.ACKNOWLEDGED -> "request.cancel.acknowledged"
        CancelPhase.EXECUTION_STOPPED -> "request.cancel.execution-stopped"
        CancelPhase.TERMINAL -> "request.cancel.terminal"
    }

    fun advance(from: CancelPhase): CancelPhase = when (from) {
        CancelPhase.REQUESTED -> CancelPhase.ACKNOWLEDGED
        CancelPhase.ACKNOWLEDGED -> CancelPhase.EXECUTION_STOPPED
        CancelPhase.EXECUTION_STOPPED -> CancelPhase.TERMINAL
        CancelPhase.TERMINAL -> CancelPhase.TERMINAL
    }

    fun isTerminal(phase: CancelPhase): Boolean = phase == CancelPhase.TERMINAL
}

/**
 * Metric projection with evidence labels (CORE-OBSERVABILITY).
 * UNKNOWN never displays as a concrete number.
 */
object MetricsUiProjection {

    fun project(sample: PortMetricSample): EvidencedMetricUi {
        val displayNumeric = EvidenceSemantics.allowsNumericDisplay(sample.evidenceLabel) &&
            sample.value != null
        return EvidencedMetricUi(
            metricId = sample.metricId,
            value = if (sample.evidenceLabel == EvidenceLabel.UNKNOWN) null else sample.value,
            unit = sample.unit,
            evidenceLabel = sample.evidenceLabel,
            sampledAtEpochMs = sample.sampledAtEpochMs,
            source = sample.source,
            confidence = sample.confidence,
            displayNumeric = displayNumeric,
        )
    }
}
