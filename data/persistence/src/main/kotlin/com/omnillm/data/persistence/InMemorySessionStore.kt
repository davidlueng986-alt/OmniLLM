package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process [SessionLedgerPorts] for unit tests.
 *
 * **Not process-crash durable.** Production uses [SqlDelightSessionStore]
 * via [ControlPlaneDatabase].
 */
class InMemorySessionStore(
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : SessionLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    private val lock = Any()
    private val rows = ConcurrentHashMap<String, SessionRecordRow>()

    override val sessions: SessionRecordDao = object : SessionRecordDao {
        override fun findBySessionId(sessionId: String): SessionRecordRow? = rows[sessionId]

        override fun listAll(): List<SessionRecordRow> =
            synchronized(lock) { rows.values.sortedBy { it.updatedAt } }

        override fun listByState(state: String): List<SessionRecordRow> =
            synchronized(lock) {
                rows.values.filter { it.state == state }.sortedBy { it.updatedAt }
            }

        override fun listNonTerminal(): List<SessionRecordRow> =
            synchronized(lock) {
                rows.values.filter { it.state != "CLOSED" }.sortedBy { it.updatedAt }
            }

        override fun upsert(row: SessionRecordRow) {
            require(row.state in SessionLedgerStates.ALL)
            require(row.recoveryDisposition in SessionRecoveryDispositions.ALL)
            rows[row.sessionId] = row
        }

        override fun updateStateAndDisposition(
            sessionId: String,
            state: String,
            recoveryDisposition: String,
            healthy: Boolean,
            pinned: Boolean,
            committedTokenFingerprint: String?,
            deliveredSeq: Long,
            updatedAt: String,
        ): Boolean {
            val existing = rows[sessionId] ?: return false
            rows[sessionId] = existing.copy(
                state = state,
                recoveryDisposition = recoveryDisposition,
                healthy = healthy,
                pinned = pinned,
                committedTokenFingerprint = committedTokenFingerprint,
                deliveredSeq = deliveredSeq,
                updatedAt = updatedAt,
            )
            return true
        }

        override fun delete(sessionId: String): Boolean = rows.remove(sessionId) != null
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T = synchronized(lock) { block() }
    }
}

