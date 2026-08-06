package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Request Registry + durable Command ledger claim-or-return.
 *
 * Authority: ADR-004/005, CORE-ORCHESTRATOR, REL-RECOVERY, command-conformance-fixtures.
 */
class RequestRegistryTest {

    private val digestA = Sha256Digest.parse("a".repeat(64))
    private val digestB = Sha256Digest.parse("b".repeat(64))
    private val principal = PrincipalId.parse("principal-1")
    private val clock = { "2026-08-03T12:00:00Z" }

    private fun newSystem(): Triple<RequestRegistry, CommandLedger, *> =
        RequestRegistryModule.createInMemory(clock)

    // ------------------------------------------------------------------
    // Request claim-or-return
    // ------------------------------------------------------------------

    @Test
    fun requestClaim_newThenIdenticalReturnsExisting() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("33333333-3333-3333-3333-333333333333")
        val key = IdempotencyKey.parse("idem-req-1")

        val first = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digestA,
            requestId = requestId,
        )
        assertTrue(first is ClaimOutcome.New)
        assertEquals("RECEIVED", first.getOrNull()!!.state)

        val second = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digestA,
            requestId = requestId,
        )
        assertTrue(second is ClaimOutcome.Existing)
        assertEquals(requestId.value, second.getOrNull()!!.requestId)
        assertEquals("RECEIVED", second.getOrNull()!!.state)
        assertFalse(second.isNew)
    }

    @Test
    fun requestClaim_sameKeyDifferentHash_isConflict() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("44444444-4444-4444-4444-444444444444")
        val key = IdempotencyKey.parse("idem-req-2")

        val first = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digestA,
            requestId = requestId,
        )
        assertTrue(first is ClaimOutcome.New)

        // Same key, different digest (and different requestId — classic replay-with-mutate).
        val conflict = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digestB,
            requestId = RequestId.parse("55555555-5555-5555-5555-555555555555"),
        )
        assertTrue(conflict is ClaimOutcome.Conflict)
        val err = (conflict as ClaimOutcome.Conflict).error
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, err.code)
        assertEquals("query-existing-or-use-a-new-key", err.requiredClientAction)
    }

    @Test
    fun requestClaim_sameRequestIdDifferentDigest_isConflict() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("66666666-6666-6666-6666-666666666666")
        registry.claim(
            principal = principal,
            operationKind = "EMBEDDING",
            idempotencyKey = IdempotencyKey.parse("idem-req-3"),
            canonicalHash = digestA,
            requestId = requestId,
        )
        val conflict = registry.claim(
            principal = principal,
            operationKind = "EMBEDDING",
            idempotencyKey = IdempotencyKey.parse("idem-req-3"),
            canonicalHash = digestB,
            requestId = requestId,
        )
        assertTrue(conflict is ClaimOutcome.Conflict)
    }

    @Test
    fun queryRequest_afterReplyLoss_returnsDurableRow() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("77777777-7777-7777-7777-777777777777")
        registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-req-4"),
            canonicalHash = digestA,
            requestId = requestId,
        )
        // Reply loss: client queries by requestId — does not mint a new id.
        val queried = registry.queryRequest(requestId)
        assertNotNull(queried)
        assertEquals(digestA.hex, queried!!.canonicalRequestDigest)
        assertNull(registry.queryRequest(RequestId.parse("88888888-8888-8888-8888-888888888888")))
    }

    // ------------------------------------------------------------------
    // Terminal + attempt tracking
    // ------------------------------------------------------------------

    @Test
    fun recordTerminal_andQuery_exactlyOnce() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("99999999-9999-9999-9999-999999999999")
        registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-term-1"),
            canonicalHash = digestA,
            requestId = requestId,
        )

        val terminal = registry.recordTerminal(
            requestId = requestId,
            terminalState = "COMPLETED",
            terminalSeq = 3,
            outputDigest = digestA,
        )
        assertTrue(terminal is OmniResult.Ok)
        assertEquals("COMPLETED", (terminal as OmniResult.Ok).value.terminalState)

        val request = registry.queryRequest(requestId)!!
        assertEquals("COMPLETED", request.state)

        val again = registry.recordTerminal(
            requestId = requestId,
            terminalState = "COMPLETED",
            terminalSeq = 3,
            outputDigest = digestA,
        )
        assertTrue(again is OmniResult.Ok)

        val conflict = registry.recordTerminal(
            requestId = requestId,
            terminalState = "FAILED",
            terminalSeq = 4,
            errorCode = "INTERNAL",
        )
        assertTrue(conflict is OmniResult.Err)
        assertEquals(
            OmniErrorCode.STATE_CONFLICT,
            (conflict as OmniResult.Err).error.code,
        )
    }

    @Test
    fun attemptTracking_beginAndEnd() {
        val (registry, _, _) = newSystem()
        val requestId = RequestId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-att-1"),
            canonicalHash = digestA,
            requestId = requestId,
        )

        val a1 = registry.beginAttempt(requestId, runtimeEpoch = 1L)
        assertTrue(a1 is OmniResult.Ok)
        assertEquals(1, (a1 as OmniResult.Ok).value.attemptNo)

        val a2 = registry.beginAttempt(requestId, runtimeEpoch = 1L, workerInstanceId = "w-1")
        assertTrue(a2 is OmniResult.Ok)
        assertEquals(2, (a2 as OmniResult.Ok).value.attemptNo)

        val ended = registry.endAttempt(requestId, attemptNo = 1, state = "ENDED")
        assertTrue(ended is OmniResult.Ok)
        assertEquals("ENDED", (ended as OmniResult.Ok).value.state)
        assertNotNull(ended.value.endedAt)

        val listed = registry.listAttempts(requestId)
        assertEquals(2, listed.size)
        assertEquals(1, listed[0].attemptNo)
        assertEquals("ENDED", listed[0].state)
        assertNull(listed[1].endedAt)
    }

    // ------------------------------------------------------------------
    // Command claim/result
    // ------------------------------------------------------------------

    @Test
    fun commandClaim_orReturn_andQueryAfterReplyLoss() {
        val (_, commands, _) = newSystem()
        val commandId = CommandId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val key = IdempotencyKey.parse("idem-cmd-1")

        val first = commands.claim(
            principal = principal,
            operationKind = "cancelRequest",
            idempotencyKey = key,
            canonicalHash = digestA,
            commandId = commandId,
        )
        assertTrue(first is ClaimOutcome.New)

        // Reply loss: query by commandId.
        val queried = commands.queryCommand(commandId)
        assertNotNull(queried)
        assertEquals(commandId.value, queried!!.commandId)

        val again = commands.claim(
            principal = principal,
            operationKind = "cancelRequest",
            idempotencyKey = key,
            canonicalHash = digestA,
            commandId = commandId,
        )
        assertTrue(again is ClaimOutcome.Existing)
        assertEquals(commandId.value, again.getOrNull()!!.commandId)
    }

    @Test
    fun commandClaim_sameKeyDifferentDigest_isConflict() {
        val (_, commands, _) = newSystem()
        commands.claim(
            principal = principal,
            operationKind = "patchSettings",
            idempotencyKey = IdempotencyKey.parse("idem-cmd-2"),
            canonicalHash = digestA,
            commandId = CommandId.parse("cccccccc-cccc-cccc-cccc-cccccccccccc"),
        )
        val conflict = commands.claim(
            principal = principal,
            operationKind = "patchSettings",
            idempotencyKey = IdempotencyKey.parse("idem-cmd-2"),
            canonicalHash = digestB,
            commandId = CommandId.parse("dddddddd-dddd-dddd-dddd-dddddddddddd"),
        )
        assertTrue(conflict is ClaimOutcome.Conflict)
        assertEquals(
            OmniErrorCode.IDEMPOTENCY_CONFLICT,
            (conflict as ClaimOutcome.Conflict).error.code,
        )
    }

    @Test
    fun commandRecordResult_beforeReply_thenQueryPreservesResult() {
        val (_, commands, _) = newSystem()
        val commandId = CommandId.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        commands.claim(
            principal = principal,
            operationKind = "createJob",
            idempotencyKey = IdempotencyKey.parse("idem-cmd-3"),
            canonicalHash = digestA,
            commandId = commandId,
        )
        commands.updateState(commandId, "CLAIMED")
        commands.updateState(commandId, "RUNNING")

        val recorded = commands.recordResult(
            commandId = commandId,
            state = "SUCCEEDED",
            resultJson = """{"jobId":"j-1"}""",
            affectedResourceId = "j-1",
        )
        assertTrue(recorded is OmniResult.Ok)
        assertEquals("SUCCEEDED", (recorded as OmniResult.Ok).value.state)
        assertTrue((recorded as OmniResult.Ok).value.resourceVersion >= 1L)

        // After "reply loss", query returns durable original — no blind replay.
        val queried = commands.queryCommand(commandId)!!
        assertEquals("SUCCEEDED", queried.state)
        assertEquals("""{"jobId":"j-1"}""", queried.resultJson)

        val reClaim = commands.claim(
            principal = principal,
            operationKind = "createJob",
            idempotencyKey = IdempotencyKey.parse("idem-cmd-3"),
            canonicalHash = digestA,
            commandId = commandId,
        )
        assertTrue(reClaim is ClaimOutcome.Existing)
        assertEquals("SUCCEEDED", reClaim.getOrNull()!!.state)
    }

    @Test
    fun commandRecordResult_secondDifferentTerminal_isStateConflict() {
        val (_, commands, _) = newSystem()
        val commandId = CommandId.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        commands.claim(
            principal = principal,
            operationKind = "deleteAsset",
            idempotencyKey = IdempotencyKey.parse("idem-cmd-4"),
            canonicalHash = digestA,
            commandId = commandId,
        )
        commands.recordResult(commandId, "SUCCEEDED", resultJson = "{}")
        val second = commands.recordResult(commandId, "FAILED", errorCode = "INTERNAL")
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (second as OmniResult.Err).error.code)
    }
}
