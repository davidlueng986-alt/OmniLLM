package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-10 contract test: LanPairingChallengeCreateRequest uses the spec field
 * names challenge_id / requested_scopes / client_display_hint / ttl_seconds
 * (:2775-2799) — the old `scopes` name and handler-fabricated challengeId
 * violated the contract.
 */
class WireLanPairingContractTest {

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun lanPairingChallengeRequest_wireUsesSpecFieldNames() {
        val dto = LanPairingChallengeCreateRequestDto(
            command = CommandRequestDto(uuid, "lan-1", digest64),
            challengeId = uuid,
            requestedScopes = listOf("inference.create", "models.read"),
            clientDisplayHint = "living-room",
            ttlSeconds = 300,
        )
        val json = HttpJson.codec.encodeToString(LanPairingChallengeCreateRequestDto.serializer(), dto)
        assertTrue("challenge_id required: $json", json.contains("\"challenge_id\":\"$uuid\""))
        assertTrue("requested_scopes required: $json", json.contains("\"requested_scopes\""))
        assertTrue("client_display_hint must serialize: $json", json.contains("\"client_display_hint\":\"living-room\""))
        assertTrue("legacy scopes must NOT leak: $json", !json.contains("\"scopes\""))
    }

    @Test
    fun lanPairingChallengeRequest_decodesSpecShapedJson() {
        val json =
            """{"command":{"command_id":"$uuid","idempotency_key":"k","canonical_input_digest":"$digest64"},"challenge_id":"$uuid","requested_scopes":["models.read"],"client_display_hint":"office","ttl_seconds":300}"""
        val dto = HttpJson.codec.decodeFromString(LanPairingChallengeCreateRequestDto.serializer(), json)
        assertEquals(uuid, dto.challengeId)
        assertEquals(listOf("models.read"), dto.requestedScopes)
        assertEquals("office", dto.clientDisplayHint)
        assertEquals(300, dto.ttlSeconds)
    }
}
