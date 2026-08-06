package com.omnillm.features.tools.ports

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.policy.StoredToolResultClaim
import com.omnillm.features.tools.policy.StructuredModePolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature-level ports into the runtime control plane for FEAT-TOOLS.
 *
 * Feature packs compose these ports only — never open domain DB writers or
 * native engines (INV-001, ADR-010).
 */

interface ToolsCapabilityPort {
    fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState

    /**
     * Offered structured mode for the cell (engine/model/workload).
     * Default UNSUPPORTED when unknown — fail closed.
     */
    fun offeredStructuredMode(
        modelRevisionId: String,
        workloadEnvelope: String = "default",
    ): StructuredModePolicy.EngineStructuredCell
}

/**
 * Scope / auth gate (LAN fail-closed on missing auth).
 * Catalog scopes only (`specs/access-control-catalog.yaml`).
 */
interface ToolsScopePort {
    fun allows(principal: PrincipalId, scope: AccessScope): Boolean

    /** Host principal authorized to submit ToolResult for this request. */
    fun maySubmitToolResult(principal: PrincipalId, requestId: String): Boolean
}

/** Inference surface for structured / tool-calling (runtime Orchestrator). */
interface ToolsInferencePort {
    suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
        maxRepairAttempts: Int,
    ): OmniResult<StructuredInferenceHandle>

    suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
    ): OmniResult<ToolCallingHandle>

    suspend fun cancel(principal: PrincipalId, requestId: String): OmniResult<Unit>

    suspend fun query(principal: PrincipalId, requestId: String): OmniResult<ToolsQueryHandle>
}

data class StructuredInferenceHandle(
    val requestId: String,
    val state: String,
    val actualMode: StructuredMode,
    val validationStatus: String,
    val actualModelRevisionId: String,
    val engineBuildId: String = "",
    val attempt: Int = 1,
    val structuredJson: String? = null,
    val error: OmniError? = null,
    val isTerminal: Boolean = false,
)

data class ToolCallingHandle(
    val requestId: String,
    val state: String,
    val actualMode: StructuredMode,
    val actualModelRevisionId: String,
    val engineBuildId: String = "",
    val proposals: List<ToolProposal> = emptyList(),
    val assistantText: String? = null,
    val error: OmniError? = null,
    val isTerminal: Boolean = false,
)

data class ToolsQueryHandle(
    val requestId: String,
    val state: String,
    val actualMode: StructuredMode? = null,
    val actualModelRevisionId: String? = null,
    val proposals: List<ToolProposal> = emptyList(),
    val isTerminal: Boolean = false,
    val error: OmniError? = null,
)

/**
 * Durable-ish proposal / result ledger abstraction (control-plane backed in prod).
 * In-memory impl for tests / single-process host.
 */
interface ToolProposalLedgerPort {
    fun putProposal(proposal: ToolProposal)

    fun getProposal(proposalId: String): ToolProposal?

    fun listProposals(requestId: String): List<ToolProposal>

    fun updateProposalState(proposalId: String, state: ToolProposalState): ToolProposal?

    fun putResultClaim(claim: StoredToolResultClaim)

    fun getResultClaim(proposalId: String, idempotencyKey: String): StoredToolResultClaim?
}

data class ToolsFeaturePorts(
    val inference: ToolsInferencePort,
    val capabilities: ToolsCapabilityPort,
    val scopes: ToolsScopePort,
    val ledger: ToolProposalLedgerPort,
)

/** LOCAL_ADMIN (wildcard) — for LOCAL_UI only. */
object LocalAdminToolsScopePort : ToolsScopePort {
    override fun allows(principal: PrincipalId, scope: AccessScope): Boolean {
        if (principal.value != LocalUiPrincipal.ID.value) return false
        return AccessControlCatalog.profileAllowsScope(AccessProfile.LOCAL_ADMIN, scope)
    }

    override fun maySubmitToolResult(principal: PrincipalId, requestId: String): Boolean =
        allows(principal, AccessScope.inference_create)
}

/**
 * Static capability map for tests / unqualified default (UNKNOWN until evidence).
 */
class StaticToolsCapabilityPort(
    private val states: MutableMap<Pair<String, CapabilityId>, CapabilityState> = mutableMapOf(),
    private val modes: MutableMap<String, StructuredMode> = mutableMapOf(),
) : ToolsCapabilityPort {
    fun set(modelRevisionId: String, capability: CapabilityId, state: CapabilityState) {
        states[modelRevisionId to capability] = state
    }

    fun setMode(modelRevisionId: String, mode: StructuredMode) {
        modes[modelRevisionId] = mode
    }

    override fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState =
        states[modelRevisionId to capability] ?: CapabilityState.UNKNOWN

    override fun offeredStructuredMode(
        modelRevisionId: String,
        workloadEnvelope: String,
    ): StructuredModePolicy.EngineStructuredCell {
        val mode = modes[modelRevisionId] ?: StructuredMode.UNSUPPORTED
        val capState = state(CapabilityId.STRUCTURED_OUTPUT, modelRevisionId)
        return StructuredModePolicy.EngineStructuredCell(
            offeredMode = mode,
            capabilityState = capState,
            workloadEnvelope = workloadEnvelope,
        )
    }
}

/** Thread-safe in-memory ledger (test / single-process). Not a DB writer (ADR-010). */
class InMemoryToolProposalLedger : ToolProposalLedgerPort {
    private val proposals = ConcurrentHashMap<String, ToolProposal>()
    private val results = ConcurrentHashMap<String, StoredToolResultClaim>()

    private fun resultKey(proposalId: String, idempotencyKey: String) =
        "$proposalId\u0000$idempotencyKey"

    override fun putProposal(proposal: ToolProposal) {
        proposals[proposal.proposalId] = proposal
    }

    override fun getProposal(proposalId: String): ToolProposal? = proposals[proposalId]

    override fun listProposals(requestId: String): List<ToolProposal> =
        proposals.values.filter { it.requestId == requestId }.sortedBy { it.createdAtEpochMs }

    override fun updateProposalState(proposalId: String, state: ToolProposalState): ToolProposal? {
        val cur = proposals[proposalId] ?: return null
        val next = cur.copy(state = state)
        proposals[proposalId] = next
        return next
    }

    override fun putResultClaim(claim: StoredToolResultClaim) {
        results[resultKey(claim.proposalId, claim.idempotencyKey)] = claim
    }

    override fun getResultClaim(proposalId: String, idempotencyKey: String): StoredToolResultClaim? =
        results[resultKey(proposalId, idempotencyKey)]
}
