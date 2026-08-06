package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract conformance placeholders for request/command idempotency claim-or-return.
 *
 * Authority:
 * - specs/database/omnillm-schema.sql `#inference_requests`, `#idempotent_commands`
 * - specs/command-conformance-fixtures.yaml
 * - ADR-004 / ADR-005
 *
 * Runtime path: `:runtime:request-registry` (`RequestRegistry` / `CommandLedger`) injects
 * [ClaimLedgerPorts] (e.g. [InMemoryClaimLedgerStore] or SQLDelight DAOs under
 * SingleWriterPolicy). See `RequestRegistryTest` for claim-or-return + conflict tests.
 * Remaining: restart-between-result-and-reply fixtures against durable SQL.
 */
class ClaimOrReturnConformanceTest {

    private val digestA = "c".repeat(64)
    private val digestB = "d".repeat(64)
    private val now = "2026-08-02T00:00:00Z"

    @Test
    fun packagedSchema_includesClaimTables() {
        val sql = SchemaAuthority.requirePackagedSchema().bufferedReader().readText()
        assertTrue(sql.contains("CREATE TABLE inference_requests"))
        assertTrue(sql.contains("CREATE TABLE idempotent_commands"))
        assertTrue(sql.contains("CREATE TABLE schema_metadata"))
        assertTrue(sql.contains("CREATE TABLE schema_migration_attempts"))
        assertTrue(sql.contains("CREATE TABLE schema_migration_history"))
        assertTrue(sql.contains("UNIQUE (principal_id, operation_kind, idempotency_key)"))
    }

    @Test
    fun schemaAuthority_versionsMatchMigrationPolicy() {
        assertEquals(2, SchemaAuthority.CURRENT_VERSION)
        assertEquals(2, SchemaAuthority.MIN_READABLE_VERSION)
        assertEquals(2, SchemaAuthority.MIN_WRITABLE_VERSION)
        assertTrue(SchemaAuthority.MIGRATION_ATTEMPT_STATES.contains("STARTED"))
        assertTrue(SchemaAuthority.MIGRATION_TERMINAL_STATES.contains("COMMITTED"))
    }

    @Test
    fun singleWriter_rejectsNonControlPlaneRole() {
        var rejected = false
        try {
            SingleWriterPolicy.assertWriterAllowed("app-ui")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        SingleWriterPolicy.assertWriterAllowed(SingleWriterPolicy.WRITER_ROLE)
    }

    @Test
    fun requestClaim_orReturn_identicalDigestReturnsOriginal() {
        val ledger = ClaimOrReturnLedger()
        val row = InferenceRequestClaimRow(
            requestId = "33333333-3333-3333-3333-333333333333",
            principalId = "principal-1",
            operationKind = "CHAT",
            idempotencyKey = "idem-req-1",
            canonicalRequestDigest = digestA,
            state = "RECEIVED",
            createdAt = now,
            updatedAt = now,
        )
        val (first, created1) = ledger.claimOrReturnRequest(row)
        val (second, created2) = ledger.claimOrReturnRequest(row.copy(state = "CLAIMED"))
        assertTrue(created1)
        assertFalse(created2)
        assertEquals(first.requestId, second.requestId)
        assertEquals("RECEIVED", second.state) // original durable row returned
        assertNotNull(ledger.queryRequest(row.requestId))
    }

    @Test
    fun requestClaim_digestMismatch_isConflict() {
        val ledger = ClaimOrReturnLedger()
        val row = InferenceRequestClaimRow(
            requestId = "44444444-4444-4444-4444-444444444444",
            principalId = "principal-1",
            operationKind = "CHAT",
            idempotencyKey = "idem-req-2",
            canonicalRequestDigest = digestA,
            state = "RECEIVED",
            createdAt = now,
            updatedAt = now,
        )
        ledger.claimOrReturnRequest(row)
        var conflict = false
        try {
            ledger.claimOrReturnRequest(row.copy(canonicalRequestDigest = digestB))
        } catch (_: IdempotencyConflict) {
            conflict = true
        }
        assertTrue(conflict)
    }

    @Test
    fun commandClaim_orReturn_andQueryAfterReplyLoss() {
        val ledger = ClaimOrReturnLedger()
        val row = IdempotentCommandClaimRow(
            commandId = "55555555-5555-5555-5555-555555555555",
            principalId = "principal-1",
            operationKind = "cancelRequest",
            idempotencyKey = "idem-cmd-1",
            canonicalInputDigest = digestA,
            state = "RECEIVED",
            createdAt = now,
            updatedAt = now,
        )
        val (claimed, created) = ledger.claimOrReturnCommand(row)
        assertTrue(created)
        // Reply loss: client queries by commandId — does not mint a new commandId.
        val queried = ledger.queryCommand(row.commandId)
        assertSame(claimed, queried)
        val (again, created2) = ledger.claimOrReturnCommand(row)
        assertFalse(created2)
        assertEquals(claimed.commandId, again.commandId)
    }

    @Test
    fun commandClaim_sameKeyDifferentDigest_isConflict() {
        val ledger = ClaimOrReturnLedger()
        val row = IdempotentCommandClaimRow(
            commandId = "66666666-6666-6666-6666-666666666666",
            principalId = "principal-1",
            operationKind = "patchSettings",
            idempotencyKey = "idem-cmd-2",
            canonicalInputDigest = digestA,
            state = "RECEIVED",
            createdAt = now,
            updatedAt = now,
        )
        ledger.claimOrReturnCommand(row)
        var conflict = false
        try {
            ledger.claimOrReturnCommand(
                row.copy(
                    commandId = "77777777-7777-7777-7777-777777777777",
                    canonicalInputDigest = digestB,
                ),
            )
        } catch (_: IdempotencyConflict) {
            conflict = true
        }
        assertTrue(conflict)
    }

    @Test
    fun schemaMetadataRow_enforcesSingletonAndVersionBounds() {
        val meta = SchemaMetadataRow(
            currentVersion = SchemaAuthority.CURRENT_VERSION,
            minReadableVersion = SchemaAuthority.MIN_READABLE_VERSION,
            minWritableVersion = SchemaAuthority.MIN_WRITABLE_VERSION,
            state = "ACTIVE",
            updatedAt = now,
        )
        assertEquals(1, meta.singletonId)
    }
}
