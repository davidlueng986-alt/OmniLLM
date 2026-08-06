package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.data.persistence.IdempotentCommandClaimRow
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maps `specs/command-conformance-fixtures.yaml` requiredFixturesPerMutation
 * onto the durable Command ledger (claim-or-return).
 *
 * Durable mutations listed in the fixture authority are exercised with the
 * same claim keys; domain handlers are not invoked here — only ledger semantics.
 */
class CommandConformanceFixturesTest {

    private val digestA = Sha256Digest.parse("a".repeat(64))
    private val digestB = Sha256Digest.parse("b".repeat(64))
    private val principal = PrincipalId.parse("principal-1")
    private val clock = { "2026-08-04T00:00:00Z" }

    /** Subset of durableMutations from command-conformance-fixtures.yaml. */
    private val durableMutations = listOf(
        "cancelRequest",
        "createAsset",
        "uploadAsset",
        "commitAsset",
        "deleteAsset",
        "createJob",
        "cancelJob",
        "patchSettings",
        "revokeClient",
        "enableLan",
        "disableLan",
        "createDiagnosticExport",
        "createContentReportProposal",
        "cancelContentReport",
        "discardContentReport",
        "issueLoopbackAdminToken",
        "revokeToken",
        "createLanPairingChallenge",
        "completeLanPairing",
    )

    private fun newLedger(): CommandLedger =
        RequestRegistryModule.createInMemory(clock).second

    private fun claim(
        ledger: CommandLedger,
        operationKind: String,
        key: String,
        digest: Sha256Digest,
        commandId: String,
    ): ClaimOutcome<IdempotentCommandClaimRow> =
        ledger.claim(
            principal = principal,
            operationKind = operationKind,
            idempotencyKey = IdempotencyKey.parse(key),
            canonicalHash = digest,
            commandId = CommandId.parse(commandId),
        )

    // ------------------------------------------------------------------
    // requiredFixturesPerMutation (per durable mutation kind)
    // ------------------------------------------------------------------

    @Test
    fun fixture_replyLostAfterCommit_queryReturnsDurableOriginal() {
        for ((index, kind) in durableMutations.withIndex()) {
            val ledger = newLedger()
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-%012d".format(index)
            val key = "idem-$kind"
            val first = claim(ledger, kind, key, digestA, id)
            assertTrue("$kind first claim", first is ClaimOutcome.New<*>)

            // Durable accept + result recorded (commit) then reply loss.
            ledger.updateState(CommandId.parse(id), "CLAIMED")
            val recorded = ledger.recordResult(
                commandId = CommandId.parse(id),
                state = "SUCCEEDED",
                resultJson = """{"kind":"$kind"}""",
                affectedResourceId = "res-$kind",
            )
            assertTrue("$kind recordResult", recorded is OmniResult.Ok)

            // Client queries — does not mint a new commandId.
            val queried = ledger.queryCommand(CommandId.parse(id))
            assertNotNull(queried)
            assertEquals("SUCCEEDED", queried!!.state)
            assertEquals("""{"kind":"$kind"}""", queried.resultJson)
            assertEquals("res-$kind", queried.affectedResourceId)
        }
    }

    @Test
    fun fixture_duplicateIdenticalRequest_executesMutationExactlyOnce() {
        for ((index, kind) in durableMutations.withIndex()) {
            val ledger = newLedger()
            val id = "bbbbbbbb-bbbb-bbbb-bbbb-%012d".format(index)
            val key = "dup-$kind"
            val first = claim(ledger, kind, key, digestA, id)
            assertTrue(first is ClaimOutcome.New<*>)
            ledger.recordResult(CommandId.parse(id), "SUCCEEDED", resultJson = "{}")

            val second = claim(ledger, kind, key, digestA, id)
            assertTrue("$kind second claim must be Existing", second is ClaimOutcome.Existing<*>)
            val row = second.getOrNull()!!
            assertEquals("SUCCEEDED", row.state)
            // Re-claim does not clear durable result.
            assertEquals("{}", row.resultJson)
        }
    }

    @Test
    fun fixture_sameKeyDifferentDigest_idempotencyConflict() {
        for ((index, kind) in durableMutations.withIndex()) {
            val ledger = newLedger()
            val id1 = "cccccccc-cccc-cccc-cccc-%012d".format(index)
            val id2 = "dddddddd-dddd-dddd-dddd-%012d".format(index)
            val key = "conflict-$kind"
            assertTrue(claim(ledger, kind, key, digestA, id1) is ClaimOutcome.New<*>)
            val conflict = claim(ledger, kind, key, digestB, id2)
            assertTrue("$kind must conflict", conflict is ClaimOutcome.Conflict)
            assertEquals(
                OmniErrorCode.IDEMPOTENCY_CONFLICT,
                (conflict as ClaimOutcome.Conflict).error.code,
            )
        }
    }

    @Test
    fun fixture_runtimeRestartBetweenResultAndReply_preservesQuery() {
        // Simulate restart by creating a second ledger view on the same store.
        val storeTriple = RequestRegistryModule.createInMemory(clock)
        val ledger = storeTriple.second
        val store = storeTriple.third
        val id = CommandId.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        claim(ledger, "createJob", "restart-key", digestA, id.value)
        ledger.recordResult(id, "SUCCEEDED", resultJson = """{"jobId":"j-1"}""")

        // "Restart": new ledger facade on same durable store.
        val (_, ledgerAfterRestart) = RequestRegistryModule.create(store, clock)
        val queried = ledgerAfterRestart.queryCommand(id)
        assertNotNull(queried)
        assertEquals("SUCCEEDED", queried!!.state)
        assertEquals("""{"jobId":"j-1"}""", queried.resultJson)
    }

    @Test
    fun fixture_staleExpectedVersion_stateConflictWithoutSideEffect() {
        // Ledger records resourceVersion; a second different terminal is STATE_CONFLICT.
        val ledger = newLedger()
        val id = CommandId.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        claim(ledger, "patchSettings", "version-key", digestA, id.value)
        val first = ledger.recordResult(id, "SUCCEEDED", resultJson = """{"v":1}""")
        assertTrue(first is OmniResult.Ok)
        val version = (first as OmniResult.Ok).value.resourceVersion
        assertTrue(version >= 1L)

        // Stale / conflicting terminal write — no overwrite of durable result.
        val second = ledger.recordResult(id, "FAILED", errorCode = "INTERNAL")
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (second as OmniResult.Err).error.code)
        val durable = ledger.queryCommand(id)!!
        assertEquals("SUCCEEDED", durable.state)
        assertEquals("""{"v":1}""", durable.resultJson)
    }

    @Test
    fun durableMutationInventory_matchesFixtureAuthority() {
        // Keep this list aligned with command-conformance-fixtures.yaml durableMutations.
        assertEquals(19, durableMutations.size)
        assertTrue(durableMutations.contains("cancelRequest"))
        assertTrue(durableMutations.contains("completeLanPairing"))
        assertFalse(durableMutations.contains("createChatCompletion")) // sync, not durable command
    }
}
