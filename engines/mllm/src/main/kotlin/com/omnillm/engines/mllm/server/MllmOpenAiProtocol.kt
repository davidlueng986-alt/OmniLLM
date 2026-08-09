package com.omnillm.engines.mllm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible chat-completions protocol spoken by the upstream mllm
 * in-app Go server (`mllm_server.aar`; source: mllm-cli/pkg/server/handlers.go,
 * mllm-cli/pkg/server/server.go, mllm-cli/pkg/api/types.go).
 *
 * Contract (verified against upstream mllm v2.0.0 + mllm-chat v2.0, 2026-08-09):
 * - `POST /v1/chat/completions` on `127.0.0.1:8080` (port fixed upstream).
 * - Request JSON: `model` (must match the server-registered session name =
 *   model directory name), `messages` [{role, content}], `stream: true`,
 *   optional `session_id` (server-side KV session), `id` (request id).
 * - Response: SSE `data: <chunk>` lines; chunk = OpenAI-style JSON with
 *   `choices[0].delta.content` and optional `finish_reason`; stream ends with
 *   `data: [DONE]`.
 * - Cancellation: closing the HTTP connection breaks the server stream loop
 *   (server polls `r.Context()`); there is no explicit cancel RPC.
 */
object MllmOpenAiProtocol {

    /** Upstream-fixed loopback host. */
    const val HOST: String = "127.0.0.1"

    /** Upstream-fixed port (GoMobile server logs "[GoMobile] HTTP Server listening on 8080"). */
    const val PORT: Int = 8080

    const val CHAT_COMPLETIONS_PATH: String = "/v1/chat/completions"

    /** SSE stream terminator emitted by the server. */
    const val SSE_DONE: String = "[DONE]"

    /** Stop reasons observed from the server protocol. */
    const val STOP_REASON_COMPLETED: String = "COMPLETED"
    const val STOP_REASON_LENGTH: String = "LENGTH_LIMIT"
    const val STOP_REASON_CANCELLED: String = "CANCELLED"
    const val STOP_REASON_ERROR: String = "ERROR"

    fun chatCompletionsUrl(host: String = HOST, port: Int = PORT): String =
        "http://$host:$port$CHAT_COMPLETIONS_PATH"

    /**
     * Build the OpenAI-compatible request body. [modelName] must be the name
     * the server registered for the loaded model (model directory name —
     * upstream demo default "qwen3").
     */
    fun buildChatBody(
        modelName: String,
        promptUtf8: String,
        requestId: String,
        sessionId: String? = null,
        systemPrompt: String? = null,
    ): String = buildJsonObject {
        put("model", modelName)
        put("stream", true)
        put("id", requestId)
        sessionId?.let { put("session_id", it) }
        put(
            "messages",
            buildJsonArray {
                if (!systemPrompt.isNullOrBlank()) {
                    add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                }
                add(buildJsonObject { put("role", "user"); put("content", promptUtf8) })
            },
        )
    }.toString()

    /**
     * Parsed SSE `data:` payload (one line). [Json] is recreated per call for
     * test isolation; negligible cost for chat-sized chunks.
     */
    sealed class SseEvent {
        /** A decoded text fragment (delta.content). */
        data class Delta(val text: String) : SseEvent()

        /** Terminal: [reason] is one of the STOP_REASON_* constants. */
        data class Stop(val reason: String) : SseEvent()

        /** Non-fatal protocol oddity (unparseable chunk etc.). */
        data class Warning(val message: String) : SseEvent()

        /** Not a delivery event (empty data, non-delta chunk, [DONE] handled separately). */
        data object Ignore : SseEvent()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse one SSE data line. `[DONE]` maps to [SseEvent.Stop] with
     * [STOP_REASON_COMPLETED]. finish_reason `stop`/`length` also map to Stop.
     */
    fun parseSseData(data: String): SseEvent {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return SseEvent.Ignore
        if (trimmed == SSE_DONE) return SseEvent.Stop(STOP_REASON_COMPLETED)

        val root: JsonObject = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Exception) {
            return SseEvent.Warning("unparseable server chunk")
        }

        val choices: JsonArray = root["choices"]?.jsonArray ?: return SseEvent.Ignore
        if (choices.isEmpty()) return SseEvent.Ignore
        val choice = choices[0].jsonObject

        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
            when (reason) {
                "stop" -> return SseEvent.Stop(STOP_REASON_COMPLETED)
                "length" -> return SseEvent.Stop(STOP_REASON_LENGTH)
            }
        }

        val delta = choice["delta"]?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
            ?: return SseEvent.Ignore
        return SseEvent.Delta(content)
    }
}
