package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-07 contract test: paged/carrier responses carry the required spec
 * fields — ModelPage/JobPage/ClientPage/TokenPage snapshot_version,
 * ClientInfo display_name + revocation_epoch, and ChatCompletionResponse /
 * EmbeddingResponse usage + omnillm.
 */
class WirePagedResponsesContractTest {

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun modelPage_wireCarriesSnapshotVersion() {
        val page = ModelPageDto(items = emptyList(), snapshotVersion = 7)
        val json = HttpJson.codec.encodeToString(ModelPageDto.serializer(), page)
        assertTrue("snapshot_version required by ModelPage: $json", json.contains("\"snapshot_version\":7"))
    }

    @Test
    fun jobPage_wireCarriesSnapshotVersion() {
        val page = JobPageDto(items = emptyList(), snapshotVersion = 7)
        val json = HttpJson.codec.encodeToString(JobPageDto.serializer(), page)
        assertTrue("snapshot_version required by JobPage: $json", json.contains("\"snapshot_version\":7"))
    }

    @Test
    fun clientPage_wireCarriesSnapshotVersionAndClientInfoRequiredFields() {
        val page = ClientPageDto(
            items = listOf(
                ClientInfoDto(
                    clientId = "client-1",
                    displayName = "LAN phone",
                    state = "ACTIVE",
                    scopes = listOf("inference.create"),
                    lastSeenAt = "2026-08-04T01:00:00Z",
                    revocationEpoch = 3,
                ),
            ),
            snapshotVersion = 7,
        )
        val json = HttpJson.codec.encodeToString(ClientPageDto.serializer(), page)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("snapshot_version required by ClientPage: $json", obj.containsKey("snapshot_version"))
        assertTrue("display_name required by ClientInfo: $json", json.contains("\"display_name\":\"LAN phone\""))
        assertTrue("revocation_epoch required by ClientInfo: $json", json.contains("\"revocation_epoch\":3"))
        assertTrue("client_id required by ClientInfo: $json", json.contains("\"client_id\":\"client-1\""))
    }

    @Test
    fun tokenPage_wireCarriesSnapshotVersion() {
        val page = TokenPageDto(items = emptyList(), snapshotVersion = 9)
        val json = HttpJson.codec.encodeToString(TokenPageDto.serializer(), page)
        assertTrue("snapshot_version required by TokenPage: $json", json.contains("\"snapshot_version\":9"))
    }

    @Test
    fun chatCompletionResponse_wireCarriesUsageAndOmnillm() {
        val dto = ChatCompletionResponseDto(
            id = "chatcmpl-1",
            created = 1L,
            model = "demo",
            choices = listOf(
                ChatCompletionChoiceDto(
                    message = AssistantMessageDto(role = "assistant", content = "hi"),
                    finishReason = "stop",
                ),
            ),
            usage = UsageDto(promptTokens = 5, completionTokens = 3, totalTokens = 8),
            omnillm = OmniExecutionInfoDto(
                requestId = uuid,
                actualModelRevisionId = digest64,
                engineBuildId = "engine-1",
                backend = "cpu",
                degradations = listOf("degraded-x"),
            ),
        )
        val json = HttpJson.codec.encodeToString(ChatCompletionResponseDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("usage required by ChatCompletionResponse: $json", obj.containsKey("usage"))
        assertTrue("omnillm required by ChatCompletionResponse: $json", obj.containsKey("omnillm"))
        assertTrue(json.contains("\"prompt_tokens\":5"))
        assertTrue(json.contains("\"request_id\":\"$uuid\""))
        assertTrue(json.contains("\"actual_model_revision_id\":\"$digest64\""))
    }

    @Test
    fun embeddingResponse_wireCarriesUsageAndOmnillm() {
        val dto = EmbeddingResponseDto(
            model = "demo",
            data = listOf(EmbeddingDataDto(embedding = listOf(0.1, 0.2))),
            usage = UsageDto(promptTokens = 4, totalTokens = 4),
            omnillm = OmniExecutionInfoDto(requestId = uuid),
        )
        val json = HttpJson.codec.encodeToString(EmbeddingResponseDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("usage required by EmbeddingResponse: $json", obj.containsKey("usage"))
        assertTrue("omnillm required by EmbeddingResponse: $json", obj.containsKey("omnillm"))
    }
}
