package com.omnillm.runtime.policy

import java.util.concurrent.ConcurrentHashMap

/**
 * Durable revocation-subject epoch store (SEC-006 / INV-017).
 *
 * Survives process restart so token principal/token fences remain fail-closed.
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010).
 */
interface RevocationEpochStore {
    fun get(scopeKey: String): RevocationRecord?

    fun listAll(): List<RevocationRecord>

    fun upsert(record: RevocationRecord)

    fun delete(scopeKey: String): Boolean
}

fun RevocationScope.storageKey(): String = "${kind.name}\u0000$subjectId"

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
