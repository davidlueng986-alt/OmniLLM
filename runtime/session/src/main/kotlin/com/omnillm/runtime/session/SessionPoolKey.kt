package com.omnillm.runtime.session

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.state.domain.OwnerKey

/**
 * Pool partition key (CORE-SESSION §7).
 *
 * Partition = owner + revision + load key + template/tokenizer epochs.
 * [LoadKey] already embeds modelRevisionId, templateEpoch, tokenizerEpoch,
 * engineBuildId, backend, device fingerprint and load configuration digest.
 *
 * Automatic candidates, prefix hints and reuse must not cross [ownerKey];
 * explicit sharing requires a separate feature + ACL.
 */
data class SessionPoolKey(
    val ownerKey: OwnerKey,
    val modelRevisionId: ModelRevisionId,
    val loadKey: LoadKey,
) {
    init {
        require(loadKey.modelRevisionId == modelRevisionId) {
            "SessionPoolKey.modelRevisionId must equal loadKey.modelRevisionId"
        }
    }

    val templateEpoch: Long get() = loadKey.templateEpoch
    val tokenizerEpoch: Long get() = loadKey.tokenizerEpoch

    companion object {
        fun of(ownerKey: OwnerKey, loadKey: LoadKey): SessionPoolKey =
            SessionPoolKey(
                ownerKey = ownerKey,
                modelRevisionId = loadKey.modelRevisionId,
                loadKey = loadKey,
            )
    }
}

/**
 * Criteria for selecting a pooled session candidate (CORE-SESSION §7).
 * Candidate must match partition, health, state, context capability and revocation epoch.
 */
data class PoolCandidateQuery(
    val poolKey: SessionPoolKey,
    val revocationEpoch: Long,
    /** When true, only sessions with healthy=true are returned. */
    val requireHealthy: Boolean = true,
    /**
     * When non-null, candidate committed fingerprint must equal this value
     * (exact prefix reuse). Committed token count alone is insufficient.
     */
    val requiredCommittedFingerprint: String? = null,
) {
    init {
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }
}
