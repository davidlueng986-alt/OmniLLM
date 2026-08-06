package com.omnillm.features.tools.projection

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.tools.api.CapabilityCellView
import com.omnillm.features.tools.api.StructuredResultView
import com.omnillm.features.tools.api.ToolCallingResultView
import com.omnillm.features.tools.api.ToolProposalView
import com.omnillm.features.tools.api.ToolsNegotiationView
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ToolDefinition
import com.omnillm.features.tools.domain.ToolExecutionOwner
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ValidationStatusCode
import com.omnillm.features.tools.observability.ToolsTraceRedaction
import com.omnillm.features.tools.ports.StructuredInferenceHandle
import com.omnillm.features.tools.ports.ToolCallingHandle
import com.omnillm.features.tools.policy.StructuredModePolicy

object ToolsProjections {

    fun projectCell(
        capability: CapabilityId,
        state: CapabilityState,
        offeredMode: StructuredMode? = null,
    ): CapabilityCellView {
        val operable = state == CapabilityState.SUPPORTED || state == CapabilityState.CONDITIONAL
        val labelKey = when (state) {
            CapabilityState.SUPPORTED -> "capability.supported"
            CapabilityState.UNSUPPORTED -> "capability.unsupported"
            CapabilityState.CONDITIONAL -> "capability.conditional"
            CapabilityState.UNKNOWN -> "capability.unknown"
            CapabilityState.TEMPORARILY_UNAVAILABLE -> "capability.temporarily-unavailable"
        }
        return CapabilityCellView(
            capabilityId = capability,
            state = state,
            offeredMode = offeredMode,
            operable = operable && state != CapabilityState.UNKNOWN,
            labelKey = labelKey,
        )
    }

    fun projectNegotiation(
        modelRevisionId: String,
        cells: List<CapabilityCellView>,
        offeredMode: StructuredMode,
    ): ToolsNegotiationView {
        val blocked = cells.firstOrNull { !it.operable }
        val reason = when {
            blocked == null -> null
            blocked.state == CapabilityState.UNKNOWN ->
                "capability.unknown.qualify-or-select-known"
            blocked.state == CapabilityState.UNSUPPORTED ->
                "capability.unsupported.select-supported-operation"
            blocked.state == CapabilityState.TEMPORARILY_UNAVAILABLE ->
                "capability.temporarily-unavailable.retry"
            else -> "capability.blocked"
        }
        return ToolsNegotiationView(
            modelRevisionId = modelRevisionId,
            cells = cells,
            operable = blocked == null && offeredMode != StructuredMode.UNSUPPORTED,
            blockingReasonKey = reason,
            offeredMode = offeredMode,
        )
    }

    fun projectProposal(
        proposal: ToolProposal,
        definition: ToolDefinition? = null,
        includeSensitive: Boolean = false,
    ): ToolProposalView =
        ToolProposalView(
            proposalId = proposal.proposalId,
            requestId = proposal.requestId,
            toolId = proposal.toolId,
            schemaDigest = proposal.schemaDigest,
            attempt = proposal.attempt,
            state = proposal.state,
            argumentBytes = proposal.argumentsByteLength,
            argumentsRedacted = ToolsTraceRedaction.maybeArguments(
                proposal.argumentsJson,
                includeSensitive,
            ),
            executionOwner = ToolExecutionOwner.HOST,
            requiredHostPermission = definition?.requiredHostPermission.orEmpty(),
            createdAtEpochMs = proposal.createdAtEpochMs,
        )

    fun projectStructured(
        handle: StructuredInferenceHandle,
        schemaDigest: String,
        schemaName: String,
        modeDecision: StructuredModePolicy.ModeDecision,
        fallbackPolicy: FallbackPolicy,
        fallbackApplied: Boolean,
        validationOverride: ValidationStatusCode? = null,
    ): StructuredResultView {
        val status = validationOverride
            ?: ValidationStatusCode.fromWire(handle.validationStatus)
            ?: if (handle.error == null) ValidationStatusCode.OK else ValidationStatusCode.OUTPUT_INVALID
        return StructuredResultView(
            requestId = handle.requestId,
            state = handle.state,
            actualMode = handle.actualMode,
            validationStatus = status,
            schemaDigest = schemaDigest,
            schemaName = schemaName,
            attempt = handle.attempt,
            maxAttempts = modeDecision.maxAttempts,
            maxRepairAttempts = modeDecision.maxRepairAttempts,
            actualModelRevisionId = handle.actualModelRevisionId,
            engineBuildId = handle.engineBuildId,
            fallbackPolicy = fallbackPolicy,
            fallbackApplied = fallbackApplied,
            structuredJson = handle.structuredJson,
            isTerminal = handle.isTerminal,
            error = handle.error,
            traceFields = ToolsTraceRedaction.structuredTraceFields(
                requestId = handle.requestId,
                schemaDigest = schemaDigest,
                mode = handle.actualMode,
                validationCode = status,
                attempt = handle.attempt,
            ),
        )
    }

    fun projectToolCalling(
        handle: ToolCallingHandle,
        definitions: Map<String, ToolDefinition>,
        fallbackPolicy: FallbackPolicy,
        fallbackApplied: Boolean,
    ): ToolCallingResultView =
        ToolCallingResultView(
            requestId = handle.requestId,
            state = handle.state,
            actualMode = handle.actualMode,
            actualModelRevisionId = handle.actualModelRevisionId,
            engineBuildId = handle.engineBuildId,
            fallbackPolicy = fallbackPolicy,
            fallbackApplied = fallbackApplied,
            proposals = handle.proposals.map { projectProposal(it, definitions[it.toolId]) },
            assistantText = handle.assistantText,
            isTerminal = handle.isTerminal,
            error = handle.error,
            traceFields = buildMap {
                put("requestId", handle.requestId)
                put("actualMode", handle.actualMode.name)
                put("toolCount", handle.proposals.size.toString())
                put("actualModelRevisionId", handle.actualModelRevisionId)
                put("executionOwner", ToolExecutionOwner.HOST.name)
            }.let { raw ->
                com.omnillm.runtime.observability.Redactor.projectAllowlist(
                    raw,
                    ToolsTraceRedaction.DEFAULT_ALLOWLIST,
                    redactValues = false,
                )
            },
        )
}
