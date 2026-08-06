package com.omnillm.features.tools.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.tools.domain.SchemaLimits
import com.omnillm.features.tools.domain.ToolDefinition
import com.omnillm.features.tools.domain.ToolExecutionOwner
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ToolResult
import com.omnillm.features.tools.domain.digestPayload

/**
 * Tool non-execution + host-result policy (FEAT-TOOLS §3–§5 / §7.3–§7.5).
 *
 * OmniLLM never runs shell, arbitrary URL, file, or host commands.
 * Only an authorized host may submit [ToolResult].
 */
object ToolExecutionPolicy {

    /** Platform always refuses to execute tool side effects. */
    fun platformExecutionOwner(): ToolExecutionOwner = ToolExecutionOwner.PLATFORM_FORBIDDEN

    fun refusePlatformExecution(toolId: String): OmniError =
        OmniError.FORBIDDEN(
            message = "OmniLLM does not execute host tools; only authorized host may submit ToolResult",
            details = mapOf(
                "toolId" to toolId,
                "executionOwner" to ToolExecutionOwner.PLATFORM_FORBIDDEN.name,
                "requiredOwner" to ToolExecutionOwner.HOST.name,
            ),
        )

    /**
     * Admit a model-emitted proposal for recording (not execution).
     */
    fun admitProposal(
        proposal: ToolProposal,
        definitions: Map<String, ToolDefinition>,
        allowlist: Set<String>?,
        limits: SchemaLimits = SchemaLimits.DEFAULT,
    ): OmniResult<ToolProposal> {
        val def = definitions[proposal.toolId]
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown toolId in proposal",
                    details = mapOf("toolId" to proposal.toolId),
                ),
            )
        if (allowlist != null && proposal.toolId !in allowlist) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "tool not on allowlist",
                    details = mapOf("toolId" to proposal.toolId),
                ),
            )
        }
        val argBytes = proposal.argumentsByteLength
        val cap = minOf(def.maxArgumentBytes, limits.maxArgumentBytes)
        if (argBytes > cap) {
            return OmniResult.err(
                OmniError.TRANSPORT_TOO_LARGE(
                    message = "tool arguments exceed byte cap",
                    details = mapOf(
                        "toolId" to proposal.toolId,
                        "argumentBytes" to argBytes.toString(),
                        "maxArgumentBytes" to cap.toString(),
                    ),
                ),
            )
        }
        if (def.schemaDigest != proposal.schemaDigest && proposal.schemaDigest.isNotBlank()) {
            // Mismatch is invalid; do not execute, do not silently rewrite.
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "proposal schemaDigest does not match tool definition",
                    details = mapOf(
                        "toolId" to proposal.toolId,
                        "expectedDigest" to def.schemaDigest,
                        "actualDigest" to proposal.schemaDigest,
                    ),
                ),
            )
        }
        return OmniResult.ok(proposal.copy(state = ToolProposalState.PROPOSED))
    }

    /**
     * Claim-or-return for host ToolResult (FEAT-TOOLS §7.4).
     * Same proposal + idempotency + digest ⇒ existing; same key different digest ⇒ conflict.
     */
    fun claimToolResult(
        existing: StoredToolResultClaim?,
        incoming: ToolResult,
        proposal: ToolProposal?,
        hostAuthorized: Boolean,
    ): ToolResultClaimOutcome {
        if (!hostAuthorized) {
            return ToolResultClaimOutcome.Denied(
                OmniError.FORBIDDEN(
                    message = "host not authorized to submit ToolResult",
                    details = mapOf(
                        "proposalId" to incoming.proposalId,
                        "executionOwner" to ToolExecutionOwner.HOST.name,
                    ),
                ),
            )
        }
        if (proposal == null) {
            return ToolResultClaimOutcome.Denied(
                OmniError.NOT_FOUND(
                    message = "tool proposal not found",
                    details = mapOf("proposalId" to incoming.proposalId),
                ),
            )
        }
        if (proposal.requestId != incoming.requestId) {
            return ToolResultClaimOutcome.Denied(
                OmniError.INVALID_REQUEST(
                    message = "ToolResult requestId does not match proposal",
                    details = mapOf(
                        "proposalRequestId" to proposal.requestId,
                        "resultRequestId" to incoming.requestId,
                    ),
                ),
            )
        }
        when (proposal.state) {
            ToolProposalState.UNCERTAIN -> {
                // FEAT-TOOLS §7.5 — do not invent opposite state.
                return ToolResultClaimOutcome.Denied(
                    OmniError.ABORTED_UNCERTAIN(
                        message = "host tool outcome unprovable; session requires confirmation or new session",
                        details = mapOf(
                            "proposalId" to proposal.proposalId,
                            "state" to ToolProposalState.UNCERTAIN.name,
                        ),
                    ),
                )
            }
            ToolProposalState.CANCELLED -> {
                return ToolResultClaimOutcome.Denied(
                    OmniError.STATE_CONFLICT(
                        message = "proposal cancelled; cannot commit ToolResult",
                        details = mapOf("proposalId" to proposal.proposalId),
                    ),
                )
            }
            ToolProposalState.CONFLICT -> {
                return ToolResultClaimOutcome.Denied(
                    OmniError.IDEMPOTENCY_CONFLICT(
                        message = "proposal already in conflict state",
                        details = mapOf("proposalId" to proposal.proposalId),
                    ),
                )
            }
            ToolProposalState.PROPOSED,
            ToolProposalState.HOST_CLAIMED,
            ToolProposalState.RESULT_COMMITTED,
            -> Unit
        }

        if (existing != null) {
            val sameKey = existing.idempotencyKey == incoming.idempotencyKey &&
                existing.proposalId == incoming.proposalId &&
                existing.requestId == incoming.requestId
            if (sameKey) {
                return if (existing.resultPayloadDigest == incoming.resultPayloadDigest &&
                    existing.attempt == incoming.attempt
                ) {
                    ToolResultClaimOutcome.Existing(existing)
                } else {
                    ToolResultClaimOutcome.Conflict(
                        OmniError.IDEMPOTENCY_CONFLICT(
                            message = "tool result idempotency conflict (different payload)",
                            details = mapOf(
                                "proposalId" to incoming.proposalId,
                                "idempotencyKey" to incoming.idempotencyKey,
                            ),
                        ),
                    )
                }
            }
        }

        val body = incoming.resultBodyJson
        if (body != null) {
            val expected = digestPayload(body)
            if (expected != incoming.resultPayloadDigest) {
                return ToolResultClaimOutcome.Denied(
                    OmniError.INVALID_REQUEST(
                        message = "resultPayloadDigest does not match body",
                        details = mapOf(
                            "expected" to expected,
                            "actual" to incoming.resultPayloadDigest,
                        ),
                    ),
                )
            }
        }

        return ToolResultClaimOutcome.Accepted(
            StoredToolResultClaim(
                requestId = incoming.requestId,
                proposalId = incoming.proposalId,
                attempt = incoming.attempt,
                idempotencyKey = incoming.idempotencyKey,
                resultPayloadDigest = incoming.resultPayloadDigest,
                isError = incoming.isError,
                submittedAtEpochMs = incoming.submittedAtEpochMs,
            ),
        )
    }

    /**
     * Mark proposal uncertain when host outcome cannot be proven after reply loss.
     * Session must not auto-commit opposite KV (FEAT-TOOLS §4).
     */
    fun markUncertain(proposal: ToolProposal): ToolProposal =
        proposal.copy(state = ToolProposalState.UNCERTAIN)
}

data class StoredToolResultClaim(
    val requestId: String,
    val proposalId: String,
    val attempt: Int,
    val idempotencyKey: String,
    val resultPayloadDigest: String,
    val isError: Boolean,
    val submittedAtEpochMs: Long,
)

sealed class ToolResultClaimOutcome {
    data class Accepted(val claim: StoredToolResultClaim) : ToolResultClaimOutcome()
    data class Existing(val claim: StoredToolResultClaim) : ToolResultClaimOutcome()
    data class Conflict(val error: OmniError) : ToolResultClaimOutcome()
    data class Denied(val error: OmniError) : ToolResultClaimOutcome()
}
