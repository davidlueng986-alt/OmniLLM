package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction

/**
 * SQLDelight-backed [SessionLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Table: `sessions` — control-plane Session records + recovery disposition.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 *
 * Does **not** persist native KV bytes (DATA-OWNERSHIP: non-durable unless engine
 * snapshot protocol exists).
 */
class SqlDelightSessionStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : SessionLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val sessions: SessionRecordDao = object : SessionRecordDao {
        override fun findBySessionId(sessionId: String): SessionRecordRow? =
            database.sessionsQueries
                .selectBySessionId(sessionId)
                .executeAsOneOrNull()
                ?.toSessionRow()

        override fun listAll(): List<SessionRecordRow> =
            database.sessionsQueries
                .listAll()
                .executeAsList()
                .map { it.toSessionRow() }

        override fun listByState(state: String): List<SessionRecordRow> {
            require(state in SessionLedgerStates.ALL) { "unknown session state: $state" }
            return database.sessionsQueries
                .listByState(state)
                .executeAsList()
                .map { it.toSessionRow() }
        }

        override fun listNonTerminal(): List<SessionRecordRow> =
            database.sessionsQueries
                .listNonTerminal()
                .executeAsList()
                .map { it.toSessionRow() }

        override fun upsert(row: SessionRecordRow) {
            require(row.state in SessionLedgerStates.ALL) { "unknown session state: ${row.state}" }
            require(row.recoveryDisposition in SessionRecoveryDispositions.ALL) {
                "unknown recovery disposition: ${row.recoveryDisposition}"
            }
            database.sessionsQueries.upsertSession(
                session_id = row.sessionId,
                session_epoch = row.sessionEpoch,
                owner_key = row.ownerKey,
                principal_id = row.principalId,
                loaded_model_id = row.loadedModelId,
                model_revision_id = row.modelRevisionId,
                engine_build_id = row.engineBuildId,
                backend = row.backend,
                device_execution_fingerprint = row.deviceExecutionFingerprint,
                template_epoch = row.templateEpoch,
                tokenizer_epoch = row.tokenizerEpoch,
                load_configuration_digest = row.loadConfigurationDigest,
                tokenizer_digest = row.tokenizerDigest,
                context_config = row.contextConfig,
                committed_token_fingerprint = row.committedTokenFingerprint,
                state = row.state,
                allocation_id = row.allocationId,
                revocation_epoch = row.revocationEpoch,
                runtime_epoch = row.runtimeEpoch,
                recovery_disposition = row.recoveryDisposition,
                healthy = if (row.healthy) 1L else 0L,
                pinned = if (row.pinned) 1L else 0L,
                delivered_seq = row.deliveredSeq,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
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
            require(state in SessionLedgerStates.ALL) { "unknown session state: $state" }
            require(recoveryDisposition in SessionRecoveryDispositions.ALL) {
                "unknown recovery disposition: $recoveryDisposition"
            }
            if (findBySessionId(sessionId) == null) return false
            database.sessionsQueries.updateStateAndDisposition(
                state = state,
                recovery_disposition = recoveryDisposition,
                healthy = if (healthy) 1L else 0L,
                pinned = if (pinned) 1L else 0L,
                committed_token_fingerprint = committedTokenFingerprint,
                delivered_seq = deliveredSeq,
                updated_at = updatedAt,
                session_id = sessionId,
            )
            return true
        }

        override fun delete(sessionId: String): Boolean {
            if (findBySessionId(sessionId) == null) return false
            database.sessionsQueries.deleteBySessionId(sessionId)
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Sessions.toSessionRow(): SessionRecordRow =
    SessionRecordRow(
        sessionId = session_id,
        sessionEpoch = session_epoch,
        ownerKey = owner_key,
        principalId = principal_id,
        loadedModelId = loaded_model_id,
        modelRevisionId = model_revision_id,
        engineBuildId = engine_build_id,
        backend = backend,
        deviceExecutionFingerprint = device_execution_fingerprint,
        templateEpoch = template_epoch,
        tokenizerEpoch = tokenizer_epoch,
        loadConfigurationDigest = load_configuration_digest,
        tokenizerDigest = tokenizer_digest,
        contextConfig = context_config,
        committedTokenFingerprint = committed_token_fingerprint,
        state = state,
        allocationId = allocation_id,
        revocationEpoch = revocation_epoch,
        runtimeEpoch = runtime_epoch,
        recoveryDisposition = recovery_disposition,
        healthy = healthy != 0L,
        pinned = pinned != 0L,
        deliveredSeq = delivered_seq,
        createdAt = created_at,
        updatedAt = updated_at,
    )

