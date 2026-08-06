package com.omnillm.data.persistence

/**
 * Control-plane DAO for durable Session records (CORE-SESSION / DATA-OWNERSHIP).
 *
 * Implementations must only be opened by the runtime control plane writer
 * (ADR-010 / [SingleWriterPolicy]). UI / workers / companion must not hold writers.
 *
 * Authority table: `sessions` (SQLDelight control-plane projection).
 */

interface SessionRecordDao {
    fun findBySessionId(sessionId: String): SessionRecordRow?

    fun listAll(): List<SessionRecordRow>

    fun listByState(state: String): List<SessionRecordRow>

    /** Non-CLOSED sessions for restart rehydrate. */
    fun listNonTerminal(): List<SessionRecordRow>

    /** Insert or replace full control-plane Session row. */
    fun upsert(row: SessionRecordRow)

    fun updateStateAndDisposition(
        sessionId: String,
        state: String,
        recoveryDisposition: String,
        healthy: Boolean,
        pinned: Boolean,
        committedTokenFingerprint: String?,
        deliveredSeq: Long,
        updatedAt: String,
    ): Boolean

    fun delete(sessionId: String): Boolean
}

/**
 * Bundled session-ledger ports for control-plane sole writer (ADR-010).
 */
interface SessionLedgerPorts : ControlPlaneWriter {
    val sessions: SessionRecordDao
    val tx: ClaimLedgerTransaction
}
