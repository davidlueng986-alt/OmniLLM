package com.omnillm.runtime.session

/**
 * Commit checkpoints recorded on a Session (CORE-SESSION §4).
 *
 * Separated so transport write completion is never confused with application
 * consumption (INV-006 / ADR-006).
 *
 * Order for engine/domain progress (not all transports visit every step):
 * [PROMPT_COMMITTED] → [ASSISTANT_PRODUCED] → [ASSISTANT_ACKNOWLEDGED] → [TERMINAL_COMMITTED]
 *
 * [ASSISTANT_ACKNOWLEDGED] advances **only** via AIDL application ACK
 * ([DeliverySemantics.AidlApplicationAck]); SSE socket write completion must not.
 */
enum class CommitCheckpoint {
    PROMPT_COMMITTED,
    ASSISTANT_PRODUCED,
    ASSISTANT_ACKNOWLEDGED,
    TERMINAL_COMMITTED,
    ;

    companion object {
        fun fromCatalogName(name: String): CommitCheckpoint? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): CommitCheckpoint =
            fromCatalogName(name)
                ?: error("Unknown CommitCheckpoint (fail closed): $name")
    }
}

/**
 * Durable-ish in-memory ledger of which checkpoints this session has reached.
 * [assistantAcknowledgedViaAidl] is true only after a valid AIDL application ACK.
 */
data class CheckpointLedger(
    val reached: Set<CommitCheckpoint> = emptySet(),
    val assistantAcknowledgedViaAidl: Boolean = false,
    val aidlStreamEpoch: Long? = null,
    /** Half-open exclusive ACK end: `[seqFrom, seqToExclusive)` (catalog StreamBatch). */
    val aidlAckedSeqToExclusive: Long = 0L,
) {
    init {
        require(aidlAckedSeqToExclusive >= 0L) { "aidlAckedSeqToExclusive must be non-negative" }
        aidlStreamEpoch?.let { require(it >= 0L) { "aidlStreamEpoch must be non-negative" } }
        if (assistantAcknowledgedViaAidl) {
            require(CommitCheckpoint.ASSISTANT_ACKNOWLEDGED in reached) {
                "assistantAcknowledgedViaAidl requires ASSISTANT_ACKNOWLEDGED in reached"
            }
        }
    }

    fun has(checkpoint: CommitCheckpoint): Boolean = checkpoint in reached

    fun withEngineCheckpoint(checkpoint: CommitCheckpoint): CheckpointLedger {
        require(checkpoint != CommitCheckpoint.ASSISTANT_ACKNOWLEDGED) {
            "ASSISTANT_ACKNOWLEDGED requires AIDL application ACK path (INV-006)"
        }
        return copy(reached = reached + checkpoint)
    }

    fun withAidlApplicationAck(
        streamEpoch: Long,
        seqToExclusive: Long,
    ): CheckpointLedger {
        require(streamEpoch >= 0L) { "streamEpoch must be non-negative" }
        require(seqToExclusive >= 0L) { "seqToExclusive must be non-negative" }
        require(CommitCheckpoint.ASSISTANT_PRODUCED in reached) {
            "AIDL ACK requires ASSISTANT_PRODUCED first"
        }
        // Monotonic ACK window within the same stream epoch; new epoch resets.
        val nextAcked = when {
            aidlStreamEpoch == null || streamEpoch > aidlStreamEpoch -> seqToExclusive
            streamEpoch == aidlStreamEpoch -> maxOf(aidlAckedSeqToExclusive, seqToExclusive)
            else -> error("stale AIDL streamEpoch $streamEpoch < current $aidlStreamEpoch")
        }
        return copy(
            reached = reached + CommitCheckpoint.ASSISTANT_ACKNOWLEDGED,
            assistantAcknowledgedViaAidl = true,
            aidlStreamEpoch = streamEpoch,
            aidlAckedSeqToExclusive = nextAcked,
        )
    }
}
