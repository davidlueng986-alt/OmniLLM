package com.omnillm.android.runtimeservice.http

import com.omnillm.interfaces.http.HttpJson
import com.omnillm.interfaces.http.TokenIssueRequestDto
import com.omnillm.interfaces.http.TokenIssueResultDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D23b wire-contract drift: `TokenIssueResult` (spec :2685-2714) is
 * `additionalProperties: false` and has NO `loopback_only` property — the DTO
 * emits it anyway, so strict spec clients reject the 201 response.
 */
class TokenIssueResultWireTest {

    private val principal = HttpPrincipal(
        principalId = "d23b-principal",
        tokenId = "tok-d23b",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val digest64 = "a".repeat(64)

    @Test
    fun tokenIssueResult_wireMustNotEmitLoopbackOnly() {
        val dto = TokenIssueResultDto(
            tokenId = UUID.randomUUID().toString(),
            clientId = "client-x",
            token = "plaintext-once",
            scopes = listOf("inference.create"),
            expiresAt = "2026-08-05T00:00:00Z",
            revocationEpoch = 0L,
        )
        val json = HttpJson.codec.encodeToString(TokenIssueResultDto.serializer(), dto)
        assertFalse(
            "TokenIssueResult is additionalProperties:false — loopback_only must not leak: $json",
            json.contains("loopback_only"),
        )
    }

    @Test
    fun tokenIssueResult_handlerIssuesWithoutLoopbackOnly() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
        val r = h.issueLoopbackAdminToken(
            principal,
            TokenIssueRequestDto(
                command = com.omnillm.interfaces.http.CommandRequestDto(
                    commandId = UUID.randomUUID().toString(),
                    idempotencyKey = "d23b-${UUID.randomUUID()}",
                    canonicalInputDigest = digest64,
                ),
                clientId = "client-y",
                displayName = "d23b token",
                scopes = listOf("inference.create"),
                expiresInSeconds = 3600,
            ),
        )
        assertTrue("issuance must succeed: $r", r is com.omnillm.interfaces.http.HttpHandlerResult.Ok)
        val wire = HttpJson.codec.encodeToString(
            TokenIssueResultDto.serializer(),
            (r as com.omnillm.interfaces.http.HttpHandlerResult.Ok).body,
        )
        assertFalse("handler response must not carry loopback_only: $wire", wire.contains("loopback_only"))
        val obj = Json.parseToJsonElement(wire).jsonObject
        assertTrue(obj.containsKey("token_id"))
        assertTrue(obj.containsKey("scopes"))
    }
}
