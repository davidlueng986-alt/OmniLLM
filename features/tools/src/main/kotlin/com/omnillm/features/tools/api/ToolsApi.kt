package com.omnillm.features.tools.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.domain.ToolResult

/**
 * FEAT-TOOLS public surface for Local UI / host SDK facades.
 *
 * Plan → Reserve → Commit → Execute remains on the runtime control plane;
 * this API performs schema admission, mode policy, non-execution guarantees,
 * and proposal/result claim orchestration (FEATURE-SYSTEM).
 */
interface ToolsApi {

    suspend fun getSnapshot(principal: PrincipalId): OmniResult<ToolsSnapshot>

    /**
     * Capability negotiation before high-cost structured/tool work (fail closed).
     */
    suspend fun negotiate(
        principal: PrincipalId,
        modelRevisionId: String,
        requireToolCalling: Boolean = false,
    ): OmniResult<ToolsNegotiationView>

    /** Admit schema + mode policy + start structured generation. */
    suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
    ): OmniResult<StructuredResultView>

    /** Admit tools + mode policy + start tool-calling generation (proposals only). */
    suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
    ): OmniResult<ToolCallingResultView>

    /**
     * Record model-emitted proposals without executing them.
     * Used when the host streams proposals from a running request.
     */
    suspend fun recordProposals(
        principal: PrincipalId,
        requestId: String,
        proposals: List<com.omnillm.features.tools.domain.ToolProposal>,
        toolAllowlist: Set<String>? = null,
        definitions: Map<String, com.omnillm.features.tools.domain.ToolDefinition> = emptyMap(),
    ): OmniResult<List<ToolProposalView>>

    /**
     * Host submits ToolResult with claim-or-return semantics (ADR-004/005, FEAT-TOOLS §7.4).
     */
    suspend fun submitToolResult(
        principal: PrincipalId,
        result: ToolResult,
    ): OmniResult<ToolResultSubmitView>

    /** Query proposal after reply loss — never re-execute. */
    suspend fun queryProposal(
        principal: PrincipalId,
        proposalId: String,
    ): OmniResult<ToolProposalView>

    /**
     * Mark proposal UNCERTAIN when host outcome is unprovable.
     * Session must not invent opposite KV state (FEAT-TOOLS §7.5).
     */
    suspend fun markProposalUncertain(
        principal: PrincipalId,
        proposalId: String,
    ): OmniResult<ToolProposalView>

    suspend fun cancel(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<Unit>
}
