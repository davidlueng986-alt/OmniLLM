package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-02/03 contract test: token issuance + metadata wire shapes must match
 * `specs/openapi/omnillm.openapi.yaml` field names
 * (client_id / display_name / expires_in_seconds / revocation_epoch /
 * issued_at / last_seen_at / state) — the old label/ttl_seconds/revoked
 * names silently dropped client payloads.
 */
class TokenWireContractTest {

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun issueRequest_wireUsesSpecFieldNames() {
        val dto = TokenIssueRequestDto(
            command = CommandRequestDto(uuid, "tok-1", digest64),
            clientId = "client-abc",
            displayName = "Developer token",
            scopes = listOf("inference.create"),
            expiresInSeconds = 3600,
        )
        val json = HttpJson.codec.encodeToString(TokenIssueRequestDto.serializer(), dto)
        assertTrue("wire must carry client_id: $json", json.contains("\"client_id\":\"client-abc\""))
        assertTrue("wire must carry display_name: $json", json.contains("\"display_name\":\"Developer token\""))
        assertTrue("wire must carry expires_in_seconds: $json", json.contains("\"expires_in_seconds\":3600"))
        assertTrue("legacy label must NOT leak onto the wire: $json", !json.contains("label"))
        assertTrue("legacy ttl_seconds must NOT leak onto the wire: $json", !json.contains("ttl_seconds"))
    }

    @Test
    fun issueRequest_decodesSpecShapedJson() {
        val json =
            """{"command":{"command_id":"$uuid","idempotency_key":"k","canonical_input_digest":"$digest64"},"client_id":"c1","display_name":"d1","scopes":["inference.create"],"expires_in_seconds":60}"""
        val dto = HttpJson.codec.decodeFromString(TokenIssueRequestDto.serializer(), json)
        assertEquals("c1", dto.clientId)
        assertEquals("d1", dto.displayName)
        assertEquals(60L, dto.expiresInSeconds)
    }

    @Test
    fun issueResult_wireCarriesClientIdAndRevocationEpoch() {
        val dto = TokenIssueResultDto(
            tokenId = uuid,
            clientId = "client-abc",
            token = "plaintext-once",
            scopes = listOf("inference.create"),
            expiresAt = "2026-08-05T00:00:00Z",
            revocationEpoch = 7,
        )
        val json = HttpJson.codec.encodeToString(TokenIssueResultDto.serializer(), dto)
        assertTrue(json.contains("\"client_id\":\"client-abc\""))
        assertTrue(json.contains("\"revocation_epoch\":7"))
        assertTrue(json.contains("\"token\":"))
        // D23b: TokenIssueResult is additionalProperties:false — loopback_only
        // must never leak onto the wire.
        assertTrue("legacy loopback_only must NOT leak: $json", !json.contains("loopback_only"))
    }

    @Test
    fun tokenInfo_wireCarriesSpecMetadataFields() {
        val dto = TokenInfoDto(
            tokenId = uuid,
            clientId = "client-abc",
            state = "ACTIVE",
            scopes = listOf("inference.read-own"),
            issuedAt = "2026-08-04T00:00:00Z",
            expiresAt = "2026-08-05T00:00:00Z",
            revocationEpoch = 0,
            lastSeenAt = "2026-08-04T01:00:00Z",
        )
        val json = HttpJson.codec.encodeToString(TokenInfoDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("client_id"))
        assertTrue(obj.containsKey("state"))
        assertTrue(obj.containsKey("issued_at"))
        assertTrue(obj.containsKey("last_seen_at"))
        assertTrue(obj.containsKey("revocation_epoch"))
        assertEquals("ACTIVE", (obj["state"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue("legacy revoked boolean must NOT leak: $json", !json.contains("\"revoked\""))
    }

    @Test
    fun tokenInfo_decodesSpecShapedJson() {
        val json =
            """{"token_id":"$uuid","client_id":"c1","state":"REVOKED","scopes":[],"issued_at":"2026-08-04T00:00:00Z","expires_at":"2026-08-05T00:00:00Z","revocation_epoch":3,"last_seen_at":null}"""
        val dto = HttpJson.codec.decodeFromString(TokenInfoDto.serializer(), json)
        assertEquals("REVOKED", dto.state)
        assertEquals("c1", dto.clientId)
        assertEquals(3L, dto.revocationEpoch)
    }

    @Test
    fun tokenPage_itemsAreTokenInfo() {
        val page = TokenPageDto(
            items = listOf(
                TokenInfoDto(
                    tokenId = uuid,
                    clientId = "c1",
                    state = "ACTIVE",
                    scopes = emptyList(),
                    issuedAt = "2026-08-04T00:00:00Z",
                    expiresAt = "2026-08-05T00:00:00Z",
                ),
            ),
        )
        val json = HttpJson.codec.encodeToString(TokenPageDto.serializer(), page)
        assertTrue(json.contains("\"state\":\"ACTIVE\""))
        assertTrue(json.contains("\"client_id\":\"c1\""))
    }
}
