package com.omnillm.android.workers

/**
 * Narrow internal command surface from runtime → same-UID worker.
 *
 * Not part of public HTTP/AIDL catalogs. Transport is private Binder
 * (EngineWorkerService); semantics still align with Plan→Reserve→Commit→Execute
 * and epoch fencing (ADR-002, ARCH-TRUST-TOPOLOGY §3).
 *
 * Worker must not open catalog writable paths or secrets (ADR-010).
 */
sealed class WorkerCommand {
    abstract val runtimeEpoch: Long
    abstract val bootId: String
    abstract val requestId: String
    abstract val monotonicDeadlineMs: Long

    /**
     * Handshake after bind: attach supervisor and epoch.
     * Plan/commit payloads are **not** delivered until attach succeeds.
     */
    data class AttachSupervisor(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        override val monotonicDeadlineMs: Long,
        val placementClass: String,
    ) : WorkerCommand() {
        init {
            require(requestId.isNotEmpty())
            require(bootId.isNotEmpty())
            require(runtimeEpoch >= 0L)
            require(placementClass.isNotEmpty())
        }
    }

    /**
     * Deliver a commit that is already INTENT_RECORDED on the durable ledger
     * (DATA-OWNERSHIP / runtime-recovery-fixtures). Worker claims one-shot nonce
     * before side effects.
     */
    data class CommitLoad(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        override val monotonicDeadlineMs: Long,
        val commitId: String,
        val oneShotNonce: String,
        val installationId: String,
        val modelRevisionId: String,
        val placementClass: String,
        val privilegedLoadTicketId: String,
        /** Opaque FD tokens / sealed PFD parcel keys — never raw client paths. */
        val contentFdTokens: List<String>,
        val engineBuildId: String,
    ) : WorkerCommand() {
        init {
            require(commitId.isNotEmpty())
            require(oneShotNonce.isNotEmpty())
            require(installationId.isNotEmpty())
            require(modelRevisionId.isNotEmpty())
            require(placementClass.isNotEmpty())
            require(privilegedLoadTicketId.isNotEmpty())
            require(engineBuildId.isNotEmpty())
            require(contentFdTokens.all { it.isNotEmpty() })
        }
    }

    data class Cancel(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        override val monotonicDeadlineMs: Long,
        val operationId: String,
        val commitId: String? = null,
    ) : WorkerCommand() {
        init {
            require(operationId.isNotEmpty())
        }
    }

    data class QueryJournal(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        override val monotonicDeadlineMs: Long,
        val commitId: String? = null,
    ) : WorkerCommand()

    data class DrainAndExit(
        override val runtimeEpoch: Long,
        override val bootId: String,
        override val requestId: String,
        override val monotonicDeadlineMs: Long,
    ) : WorkerCommand()
}

sealed class WorkerCommandResult {
    data class Accepted(
        val journalSequence: Long,
        val attributes: Map<String, String> = emptyMap(),
    ) : WorkerCommandResult()

    data class Rejected(
        /** Catalog error code (e.g. TRUST_PLACEMENT_REQUIRED, INVALID_REQUEST). */
        val errorCode: String,
        val message: String? = null,
    ) : WorkerCommandResult()

    data class JournalSnapshot(
        val frames: List<WorkerJournalFrame>,
    ) : WorkerCommandResult()

    data class Terminal(
        val ok: Boolean,
        val errorCode: String? = null,
        val journalSequence: Long,
    ) : WorkerCommandResult()
}
