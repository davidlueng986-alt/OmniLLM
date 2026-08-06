package com.omnillm.features.tools.api

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ToolExecutionOwner
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ValidationStatusCode

/** UI / host-facing structured request outcome. */
data class StructuredResultView(
    val requestId: String,
    val state: String,
    val actualMode: StructuredMode,
    val validationStatus: ValidationStatusCode,
    val schemaDigest: String,
    val schemaName: String,
    val attempt: Int,
    val maxAttempts: Int,
    val maxRepairAttempts: Int,
    val actualModelRevisionId: String,
    val engineBuildId: String,
    val fallbackPolicy: FallbackPolicy,
    val fallbackApplied: Boolean,
    val structuredJson: String? = null,
    val isTerminal: Boolean = false,
    val error: OmniError? = null,
    /** Privacy-safe trace fields only (FEAT-TOOLS §6). */
    val traceFields: Map<String, String> = emptyMap(),
)

data class ToolProposalView(
    val proposalId: String,
    val requestId: String,
    val toolId: String,
    val schemaDigest: String,
    val attempt: Int,
    val state: ToolProposalState,
    val argumentBytes: Int,
    /** Always redacted unless explicit local export. */
    val argumentsRedacted: String,
    val executionOwner: ToolExecutionOwner = ToolExecutionOwner.HOST,
    val requiredHostPermission: String = "",
    val createdAtEpochMs: Long,
)

data class ToolCallingResultView(
    val requestId: String,
    val state: String,
    val actualMode: StructuredMode,
    val actualModelRevisionId: String,
    val engineBuildId: String,
    val fallbackPolicy: FallbackPolicy,
    val fallbackApplied: Boolean,
    val proposals: List<ToolProposalView>,
    val assistantText: String? = null,
    val isTerminal: Boolean = false,
    val error: OmniError? = null,
    val traceFields: Map<String, String> = emptyMap(),
)

data class ToolResultSubmitView(
    val requestId: String,
    val proposalId: String,
    val state: ToolProposalState,
    val claimStatus: String,
    val resultPayloadDigest: String,
    val isError: Boolean,
    val error: OmniError? = null,
)

data class CapabilityCellView(
    val capabilityId: CapabilityId,
    val state: CapabilityState,
    val offeredMode: StructuredMode?,
    val operable: Boolean,
    val labelKey: String,
)

data class ToolsNegotiationView(
    val modelRevisionId: String,
    val cells: List<CapabilityCellView>,
    val operable: Boolean,
    val blockingReasonKey: String?,
    val offeredMode: StructuredMode,
)

data class ToolsSnapshot(
    val lastStructured: StructuredResultView? = null,
    val lastToolCalling: ToolCallingResultView? = null,
    val openProposals: List<ToolProposalView> = emptyList(),
    val lastError: OmniError? = null,
)
