package com.omnillm.android.runtimeservice.http

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D23a wire-contract drift: `CommandResult` (spec :1762-1791) has an optional
 * `error: $ref OmniError` — a FAILED command row in the ledger carries
 * `errorCode`, but getCommand never mapped it, so reply-loss clients could not
 * distinguish WHY a command failed.
 */
class CommandResultErrorWireTest {

    private val principal = HttpPrincipal(
        principalId = "d23a-principal",
        tokenId = "tok-d23a",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    private fun cmd(expectedVersion: Long? = null): CommandRequestDto = CommandRequestDto(
        commandId = uuid(),
        idempotencyKey = "d23a-${uuid()}",
        canonicalInputDigest = "a".repeat(64),
        expectedVersion = expectedVersion,
    )

    @Test
    fun getCommand_failedCommand_carriesLedgerError() = runBlocking {
        val h = handler()
        val command = cmd(expectedVersion = 1L)
        // cancelRequest on a non-existent request records a FAILED ledger row
        // (errorCode NOT_FOUND) and returns an Err to the caller.
        val cancel = h.cancelRequest(principal, uuid(), command)
        assertTrue("cancel of missing request must fail: $cancel", cancel is HttpHandlerResult.Err)

        val q = h.getCommand(principal, command.commandId) as HttpHandlerResult.Ok
        assertEquals("FAILED", q.body.state)
        assertNotNull("FAILED command result must carry the ledger error", q.body.error)
        assertEquals(OmniErrorCode.NOT_FOUND.code, q.body.error!!.code)
        assertTrue("error message must be non-blank", q.body.error!!.message.isNotBlank())
    }

    @Test
    fun getCommand_succeededCommand_hasNoError() = runBlocking {
        val h = handler()
        val command = cmd()
        // revokeToken of a missing token records FAILED; use a real success path:
        // revokeClient? needs a client — use patchSettings-style success via
        // createJob (BENCHMARK) which writes SUCCEEDED.
        val created = h.createJob(
            principal,
            com.omnillm.interfaces.http.JobSpecDto(
                command = command,
                jobId = uuid(),
                kind = "BENCHMARK",
                parameters = kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "model_revision_id" to kotlinx.serialization.json.JsonPrimitive("b".repeat(64)),
                        "engine_build_id" to kotlinx.serialization.json.JsonPrimitive("engine-1"),
                        "backend" to kotlinx.serialization.json.JsonPrimitive("cpu"),
                        "measurement_profile_id" to kotlinx.serialization.json.JsonPrimitive("c".repeat(64)),
                    ),
                ),
            ),
        )
        assertTrue("createJob must succeed: $created", created is HttpHandlerResult.Ok)

        val q = h.getCommand(principal, command.commandId) as HttpHandlerResult.Ok
        assertEquals("SUCCEEDED", q.body.state)
        assertNull("successful command must not carry an error", q.body.error)
    }
}
