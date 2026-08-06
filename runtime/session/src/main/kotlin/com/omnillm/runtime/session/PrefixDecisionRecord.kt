package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.PrefixDecision

/**
 * Stored prefix decision for a session turn (CORE-SESSION §3).
 *
 * Engine applies chat template, tokenizes and builds a full token fingerprint
 * before deciding. Plan only describes the decision + resource delta;
 * commit executes mutation. [committedTokenCount] alone never proves exact prefix.
 */
data class PrefixDecisionRecord(
    val decision: PrefixDecision,
    /** Full token fingerprint after template + tokenize (exact prefix proof). */
    val tokenFingerprint: String?,
    /** Informational count — insufficient alone for EXACT_* decisions. */
    val committedTokenCount: Long? = null,
    /** False while only planned; true after commit mutation applied. */
    val committed: Boolean = false,
    val plannedAtMonotonic: Long = 0L,
) {
    init {
        committedTokenCount?.let {
            require(it >= 0L) { "committedTokenCount must be non-negative" }
        }
        require(plannedAtMonotonic >= 0L) { "plannedAtMonotonic must be non-negative" }
        if (decision == PrefixDecision.EXACT_SAME_SESSION ||
            decision == PrefixDecision.EXACT_CROSS_SESSION
        ) {
            // Exact decisions require a fingerprint when committed.
            if (committed) {
                require(!tokenFingerprint.isNullOrBlank()) {
                    "exact prefix commit requires non-blank tokenFingerprint"
                }
            }
        }
    }

    fun markCommitted(): PrefixDecisionRecord = copy(committed = true)
}
