package com.omnillm.runtime.session

import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId

/**
 * Source session reference for planInference (CORE-SESSION §2).
 *
 * First request uses [None] — no magic `sourceEpoch=0/-1`.
 * Continuation uses [Existing] with owner partition match required.
 */
sealed class SourceSessionRef {
    /** Model-level plan with no prior session (eliminates API cycle). */
    data object None : SourceSessionRef()

    /**
     * Explicit prior session. Owner, epoch and identity must match the live
     * descriptor at commit time; cross-owner auto reuse is forbidden.
     */
    data class Existing(
        val sessionId: SessionId,
        val sessionEpoch: Long,
        val ownerKey: OwnerKey,
    ) : SourceSessionRef() {
        init {
            require(sessionEpoch >= 0L) { "sessionEpoch must be non-negative" }
        }
    }
}
