package com.omnillm.core.ports.security

import com.omnillm.core.state.generated.StateMachines
import java.util.concurrent.ConcurrentHashMap

/**
 * Monotonic revocation epoch + REVOCATION FSM (SEC-AUTH-NET, INV-017,
 * access-control-catalog invariant, state-machines.yaml#REVOCATION).
 *
 * Bumping the epoch fences active streams, queues, sessions and commands
 * bound to the previous epoch. New work must observe the current epoch.
 */
data class RevocationScope(
    /** Principal / token / trust subject being revoked. */
    val subjectId: String,
    val kind: RevocationSubjectKind,
) {
    init {
        require(subjectId.isNotBlank()) { "subjectId must be non-blank" }
    }
}

enum class RevocationSubjectKind {
    PRINCIPAL,
    TOKEN,
    CLIENT_REGISTRATION,
    TRUST_MODE,
    ACL,
}

data class RevocationRecord(
    val scope: RevocationScope,
    val epoch: Long,
    val state: String,
    val reason: String?,
    val actorPrincipalId: String?,
    val updatedAtEpochMs: Long,
) {
    init {
        require(epoch >= 0L) { "epoch must be non-negative" }
        require(StateMachines.REVOCATION.isKnownState(state)) {
            "unknown REVOCATION state: $state"
        }
    }
}

fun RevocationScope.storageKey(): String = "${kind.name}\u0000$subjectId"

/**
 * Durable revocation-subject epoch store (SEC-006 / INV-017).
 *
 * Survives process restart so token principal/token fences remain fail-closed.
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010)
 * (`:data:persistence` adapter). Tests: [InMemoryRevocationEpochStore].
 */
interface RevocationEpochStore {
    fun get(scopeKey: String): RevocationRecord?

    fun listAll(): List<RevocationRecord>

    fun upsert(record: RevocationRecord)

    fun delete(scopeKey: String): Boolean
}

class InMemoryRevocationEpochStore : RevocationEpochStore {
    private val records = ConcurrentHashMap<String, RevocationRecord>()

    override fun get(scopeKey: String): RevocationRecord? = records[scopeKey]

    override fun listAll(): List<RevocationRecord> = records.values.toList()

    override fun upsert(record: RevocationRecord) {
        records[record.scope.storageKey()] = record
    }

    override fun delete(scopeKey: String): Boolean = records.remove(scopeKey) != null

    fun clear() {
        records.clear()
    }
}
