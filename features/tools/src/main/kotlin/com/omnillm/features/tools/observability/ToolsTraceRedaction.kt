package com.omnillm.features.tools.observability

import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ValidationStatusCode
import com.omnillm.runtime.observability.Redactor

/**
 * Privacy-safe tool / structured traces (FEAT-TOOLS §6 / §7.6).
 *
 * Default metrics & diagnostics keep only IDs, digests, mode, attempt,
 * validation code, and timing — **not** full arguments/results.
 * Full payload export requires explicit local opt-in (not this default path).
 *
 * Content-report telemetry is a different capability (CONTENT_REPORTING);
 * tool traces must not be conflated with AI content reports (report ≠ telemetry).
 */
object ToolsTraceRedaction {

    /** Fields allowed in default external metrics / diagnostic summaries. */
    val DEFAULT_ALLOWLIST: Set<String> = setOf(
        "requestId",
        "proposalId",
        "toolId",
        "schemaId",
        "schemaDigest",
        "mode",
        "actualMode",
        "attempt",
        "validationCode",
        "proposalState",
        "timingMs",
        "executionOwner",
        "toolCount",
        "nodeCount",
        "maxDepthSeen",
        "schemaBytes",
        "fallbackPolicy",
        "actualModelRevisionId",
        "engineBuildId",
        "capability",
        // Explicitly NOT: arguments, resultBody, prompt, secrets, QR long-lived tokens
    )

    /**
     * Build a redacted field map for a proposal. Arguments are never included.
     */
    fun proposalTraceFields(
        proposal: ToolProposal,
        mode: StructuredMode? = null,
        validationCode: ValidationStatusCode? = null,
        timingMs: Long? = null,
        schemaId: String? = null,
    ): Map<String, String> {
        val raw = linkedMapOf(
            "requestId" to proposal.requestId,
            "proposalId" to proposal.proposalId,
            "toolId" to proposal.toolId,
            "schemaDigest" to proposal.schemaDigest,
            "attempt" to proposal.attempt.toString(),
            "proposalState" to proposal.state.name,
            "argumentBytes" to proposal.argumentsByteLength.toString(),
        )
        mode?.let { raw["actualMode"] = it.name }
        validationCode?.let { raw["validationCode"] = it.name }
        timingMs?.let { raw["timingMs"] = it.toString() }
        schemaId?.let { raw["schemaId"] = it }
        // Drop non-allowlisted (argumentBytes kept out of DEFAULT to avoid leakage heuristics).
        return Redactor.projectAllowlist(raw, DEFAULT_ALLOWLIST, redactValues = false)
    }

    fun structuredTraceFields(
        requestId: String,
        schemaDigest: String,
        mode: StructuredMode,
        validationCode: ValidationStatusCode,
        attempt: Int,
        timingMs: Long? = null,
        schemaId: String? = null,
        nodeCount: Int? = null,
        maxDepthSeen: Int? = null,
        schemaBytes: Int? = null,
    ): Map<String, String> {
        val raw = linkedMapOf(
            "requestId" to requestId,
            "schemaDigest" to schemaDigest,
            "actualMode" to mode.name,
            "mode" to mode.name,
            "validationCode" to validationCode.name,
            "attempt" to attempt.toString(),
        )
        timingMs?.let { raw["timingMs"] = it.toString() }
        schemaId?.let { raw["schemaId"] = it }
        nodeCount?.let { raw["nodeCount"] = it.toString() }
        maxDepthSeen?.let { raw["maxDepthSeen"] = it.toString() }
        schemaBytes?.let { raw["schemaBytes"] = it.toString() }
        return Redactor.projectAllowlist(raw, DEFAULT_ALLOWLIST, redactValues = false)
    }

    /**
     * Explicit local export may include arguments only when [includeSensitive] is true.
     * Default path always redacts.
     */
    fun maybeArguments(
        argumentsJson: String,
        includeSensitive: Boolean,
    ): String =
        if (includeSensitive) argumentsJson else Redactor.REDACTED

    fun maybeResultBody(
        resultBody: String?,
        includeSensitive: Boolean,
    ): String? {
        if (resultBody == null) return null
        return if (includeSensitive) resultBody else Redactor.REDACTED
    }

    /**
     * Pairing / QR payloads for LAN must never carry long-lived secrets (SEC-AUTH-NET §4).
     * Tools feature does not mint QR; this guard documents fail-closed checks if a host
     * mistakenly attaches secrets to tool metadata.
     */
    fun rejectLongLivedSecretInMetadata(metadata: Map<String, String>): String? {
        val forbiddenKeys = setOf(
            "bearer", "token", "refreshToken", "apiKey", "privateKey", "password", "secret",
        )
        for ((k, v) in metadata) {
            val lk = k.lowercase()
            if (forbiddenKeys.any { lk.contains(it) }) {
                return "long-lived secret key forbidden in tool metadata: $k"
            }
            if (Redactor.containsSecretPattern(v)) {
                return "secret-like value forbidden in tool metadata: $k"
            }
        }
        return null
    }

    fun stateLabel(state: ToolProposalState): String = state.name
}
