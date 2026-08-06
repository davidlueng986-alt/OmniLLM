package com.omnillm.runtime.orchestrator

/**
 * Stable candidate rejection reason codes (CORE-ORCHESTRATOR §2, FEAT-ROUTING §2/§6).
 *
 * Every unelected candidate must retain at least one of: trust / capability /
 * resource / health / policy. Codes are strings so UI/diagnostics can project
 * them without inventing catalog enums.
 */
object CandidateRejectionCodes {
    const val CAPABILITY: String = "CAPABILITY"
    const val REVISION_UNRESOLVED: String = "REVISION_UNRESOLVED"
    const val FALLBACK_NOT_ALLOWED: String = "FALLBACK_NOT_ALLOWED"
    const val TRUST_PLACEMENT: String = "TRUST_PLACEMENT"
    const val ENGINE_BACKEND: String = "ENGINE_BACKEND"
    const val SESSION_COMPATIBILITY: String = "SESSION_COMPATIBILITY"
    const val RESOURCE: String = "RESOURCE"
    const val POLICY: String = "POLICY"
    const val HEALTH: String = "HEALTH"
    const val THERMAL: String = "THERMAL"
    const val REVOCATION: String = "REVOCATION"

    val ALL: Set<String> = setOf(
        CAPABILITY,
        REVISION_UNRESOLVED,
        FALLBACK_NOT_ALLOWED,
        TRUST_PLACEMENT,
        ENGINE_BACKEND,
        SESSION_COMPATIBILITY,
        RESOURCE,
        POLICY,
        HEALTH,
        THERMAL,
        REVOCATION,
    )

    fun isKnown(code: String): Boolean = code in ALL
}

/** One rejection recorded against a routing candidate (explainable routing). */
data class CandidateRejection(
    val candidateId: String,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(candidateId.isNotEmpty()) { "candidateId must be non-empty" }
        require(CandidateRejectionCodes.isKnown(code)) {
            "unknown candidate rejection code (fail closed): $code"
        }
        require(message.isNotEmpty()) { "rejection message must be non-empty" }
    }
}
