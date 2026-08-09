package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.IdempotentCommandClaimRow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight claim ledger: claim-or-return + process-death visible terminal.
 *
 * Authority: ADR-004/005, DATA-OWNERSHIP, REL-RECOVERY, omnillm-schema claim tables.
 */
class SqlDelightClaimLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private val now = "2026-08-06T00:00:00Z"
    private val clock = { now }

    @Test
    fun requestClaim_orReturn_identicalDigestReturnsOriginal() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.claims
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
            ports.tx.inTransaction { ports.requests.insert(row) }
            val byId = ports.requests.findByRequestId(row.requestId)
            val byClaim = ports.requests.findByClaimKey(
                row.principalId,
                row.operationKind,
                row.idempotencyKey,
            )
            assertNotNull(byId)
            assertEquals(byId!!.requestId, byClaim!!.requestId)
            assertEquals("RECEIVED", byClaim.state)
            assertEquals(digestA, byClaim.canonicalRequestDigest)
        }
    }

    @Test
    fun commandClaim_andQueryAfterTerminal() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.claims
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
            ports.tx.inTransaction { ports.commands.insert(row) }
            assertTrue(
                ports.commands.updateResult(
                    commandId = row.commandId,
                    state = "SUCCEEDED",
                    resultJson = """{"ok":true}""",
                    errorCode = null,
                    affectedResourceId = null,
                    reconciliationDisposition = null,
                    resourceVersion = 1,
                    updatedAt = now,
                ),
            )
            val queried = ports.commands.findByCommandId(row.commandId)
            assertEquals("SUCCEEDED", queried!!.state)
            assertEquals("""{"ok":true}""", queried.resultJson)
        }
    }

    @Test
    fun processDeath_requestTerminal_stillQueryableAfterReopen() {
        val file = tmp.newFile("claims-restart.db")
        val requestId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val commandId = "ffffffff-1111-2222-3333-444444444444"

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val ports = db.claims
            ports.tx.inTransaction {
                ports.requests.insert(
                    InferenceRequestClaimRow(
                        requestId = requestId,
                        principalId = "principal-1",
                        operationKind = "CHAT",
                        idempotencyKey = "idem-restart-1",
                        canonicalRequestDigest = digestA,
                        state = "RECEIVED",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                assertTrue(
                    ports.terminals.insertIfAbsent(
                        RequestTerminalRow(
                            requestId = requestId,
                            terminalState = "COMPLETED",
                            outputDigest = digestB,
                            errorCode = null,
                            terminalSeq = 1L,
                            completedAt = now,
                        ),
                    ),
                )
                ports.requests.updateState(
                    requestId = requestId,
                    state = "COMPLETED",
                    updatedAt = now,
                    resourceVersion = 1L,
                )
                ports.commands.insert(
                    IdempotentCommandClaimRow(
                        commandId = commandId,
                        principalId = "principal-1",
                        operationKind = "patchSettings",
                        idempotencyKey = "idem-cmd-restart",
                        canonicalInputDigest = digestA,
                        state = "RECEIVED",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ports.commands.updateResult(
                    commandId = commandId,
                    state = "SUCCEEDED",
                    resultJson = """{"v":2}""",
                    errorCode = null,
                    affectedResourceId = "settings",
                    reconciliationDisposition = null,
                    resourceVersion = 1L,
                    updatedAt = now,
                )
            }
        }

        // Process death: reinject store from same file.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val ports = db.claims
            val req = ports.requests.findByRequestId(requestId)
            assertNotNull(req)
            assertEquals("COMPLETED", req!!.state)
            val terminal = ports.terminals.findByRequestId(requestId)
            assertNotNull(terminal)
            assertEquals("COMPLETED", terminal!!.terminalState)
            assertEquals(digestB, terminal.outputDigest)
            assertEquals(1L, terminal.terminalSeq)

            val cmd = ports.commands.findByCommandId(commandId)
            assertNotNull(cmd)
            assertEquals("SUCCEEDED", cmd!!.state)
            assertEquals("""{"v":2}""", cmd.resultJson)

            // Claim key still returns durable original (no blind re-claim as new).
            val byClaim = ports.requests.findByClaimKey(
                "principal-1",
                "CHAT",
                "idem-restart-1",
            )
            assertEquals(requestId, byClaim!!.requestId)
        }
    }

    @Test
    fun insertIfAbsent_secondTerminal_returnsFalse() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val ports = db.claims
            val requestId = "12121212-1212-1212-1212-121212121212"
            ports.tx.inTransaction {
                ports.requests.insert(
                    InferenceRequestClaimRow(
                        requestId = requestId,
                        principalId = "p",
                        operationKind = "CHAT",
                        idempotencyKey = "k",
                        canonicalRequestDigest = digestA,
                        state = "RECEIVED",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val first = RequestTerminalRow(
                    requestId = requestId,
                    terminalState = "FAILED",
                    errorCode = "E1",
                    terminalSeq = 0L,
                    completedAt = now,
                )
                assertTrue(ports.terminals.insertIfAbsent(first))
                assertFalse(
                    ports.terminals.insertIfAbsent(
                        first.copy(terminalState = "COMPLETED", terminalSeq = 1L),
                    ),
                )
                assertEquals("FAILED", ports.terminals.findByRequestId(requestId)!!.terminalState)
            }
        }
    }

    @Test
    fun unknownRequest_updateState_returnsFalse() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            assertFalse(
                db.claims.requests.updateState(
                    requestId = "missing",
                    state = "CLAIMED",
                    updatedAt = now,
                    resourceVersion = 1,
                ),
            )
            assertNull(db.claims.terminals.findByRequestId("missing"))
        }
    }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}

