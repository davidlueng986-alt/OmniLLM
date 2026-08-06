package com.omnillm.runtime.session

/**
 * Pure pool eligibility policy (CORE-SESSION §7, INV-007).
 *
 * - Partition: owner + revision + load key + template/tokenizer epoch
 * - Never re-pool POISONED
 * - ORPHANED is reconciler-only (no automatic reuse)
 * - LRU/TTL only when unpinned, no active request, safe to close
 */
object SessionPoolPolicy {

    /** States that may never appear as free pool candidates. */
    val NEVER_POOL_STATES: Set<String> = setOf(
        "NEW",
        "DRAINING",
        "POISONED",
        "ORPHANED",
        "CLOSING",
        "CLOSED",
    )

    fun isNeverPoolState(state: String): Boolean = state in NEVER_POOL_STATES

    /**
     * Whether [record] may enter or remain in the free pool under [query].
     * Fail closed on owner/partition/epoch mismatch.
     */
    fun matchesCandidate(record: SessionRecord, query: PoolCandidateQuery): Boolean {
        if (!record.allowsAutoReuse()) return false
        if (isNeverPoolState(record.aggregateState)) return false
        if (record.aggregateState == "POISONED") return false // INV-007
        if (record.poolKey != query.poolKey) return false
        // Owner partition: automatic candidates must not cross owner.
        if (record.ownerKey != query.poolKey.ownerKey) return false
        if (query.requireHealthy && !record.healthy) return false
        // Revocation fences pooled state (INV-017): candidate must not be older fence.
        if (record.descriptor.revocationEpoch < query.revocationEpoch) return false
        val requiredFp = query.requiredCommittedFingerprint
        if (requiredFp != null && record.descriptor.committedFingerprint != requiredFp) {
            return false
        }
        return true
    }

    /**
     * After a state transition, whether the session must leave the pool.
     * [blockNewUse] action on drain/poison/orphan always removes from pool.
     */
    fun mustLeavePoolOnState(state: String): Boolean =
        state != "ACTIVE" || isNeverPoolState(state)

    /**
     * Returning a session to the free pool is allowed only when fully eligible.
     * POISONED / ORPHANED / partial-mutation uncertainty never re-pool (INV-007).
     */
    fun mayEnterPool(record: SessionRecord): Boolean {
        if (record.aggregateState == "POISONED") return false
        if (record.aggregateState == "ORPHANED") return false
        if (mustLeavePoolOnState(record.aggregateState)) return false
        return record.isPoolEligible()
    }

    /**
     * LRU/TTL close is safe only when unpinned, no active ops, and state is ACTIVE
     * (then drain path) or already DRAINING with zero ops.
     */
    fun mayLruOrTtlEvict(record: SessionRecord): Boolean {
        if (record.pinned) return false
        if (record.activeOperationCount != 0) return false
        if (record.aggregateState == "POISONED") return false
        if (record.aggregateState == "ORPHANED") return false
        return record.aggregateState == "ACTIVE" || record.aggregateState == "DRAINING"
    }
}
