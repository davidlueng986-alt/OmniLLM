package com.omnillm.android.workers

/**
 * Bounded worker journal frames for reply-loss reconciliation
 * (ADR-004/005, runtime-recovery-fixtures).
 *
 * Journal is **not** a domain DB write — frames are ephemeral / process-local
 * evidence that runtime queries after WORKER_DIED or restart.
 * Free-form JSON must not drive trust placement (ARCH-TRUST-TOPOLOGY §5).
 */
data class WorkerJournalFrame(
    val sequence: Long,
    val kind: WorkerJournalKind,
    val commitId: String? = null,
    val operationId: String? = null,
    val runtimeEpoch: Long,
    val bootId: String,
    val monotonicMs: Long,
    /** Opaque catalog error code string when terminal failure. */
    val errorCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(bootId.isNotEmpty()) { "bootId must be non-empty" }
        require(monotonicMs >= 0L) { "monotonicMs must be non-negative" }
        require(attributes.size <= MAX_ATTR_ENTRIES) {
            "journal attributes exceed cap ($MAX_ATTR_ENTRIES)"
        }
        attributes.forEach { (k, v) ->
            require(k.length <= MAX_ATTR_KEY) { "journal attr key too long" }
            require(v.length <= MAX_ATTR_VALUE) { "journal attr value too long" }
        }
    }

    companion object {
        const val MAX_ATTR_ENTRIES: Int = 16
        const val MAX_ATTR_KEY: Int = 64
        const val MAX_ATTR_VALUE: Int = 256
        /** Max frames retained in-process for late query. */
        const val MAX_FRAMES: Int = 64
    }
}

enum class WorkerJournalKind {
    ATTACHED,
    COMMAND_ACCEPTED,
    COMMAND_REJECTED,
    SIDE_EFFECT_CLAIMED,
    TERMINAL_OK,
    TERMINAL_ERROR,
    SUPERVISOR_DIED,
    DRAINING,
    EXITING,
}

/**
 * Ring buffer of journal frames. Thread-safe enough for single-writer worker loops.
 */
class WorkerJournalBuffer(
    private val capacity: Int = WorkerJournalFrame.MAX_FRAMES,
) {
    private val lock = Any()
    private val frames = ArrayDeque<WorkerJournalFrame>(capacity)
    private var nextSequence = 0L

    fun append(
        kind: WorkerJournalKind,
        runtimeEpoch: Long,
        bootId: String,
        monotonicMs: Long,
        commitId: String? = null,
        operationId: String? = null,
        errorCode: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ): WorkerJournalFrame {
        synchronized(lock) {
            val frame = WorkerJournalFrame(
                sequence = nextSequence++,
                kind = kind,
                commitId = commitId,
                operationId = operationId,
                runtimeEpoch = runtimeEpoch,
                bootId = bootId,
                monotonicMs = monotonicMs,
                errorCode = errorCode,
                attributes = attributes,
            )
            if (frames.size >= capacity) {
                frames.removeFirst()
            }
            frames.addLast(frame)
            return frame
        }
    }

    fun snapshot(): List<WorkerJournalFrame> = synchronized(lock) {
        frames.toList()
    }

    fun findByCommitId(commitId: String): List<WorkerJournalFrame> =
        snapshot().filter { it.commitId == commitId }
}
