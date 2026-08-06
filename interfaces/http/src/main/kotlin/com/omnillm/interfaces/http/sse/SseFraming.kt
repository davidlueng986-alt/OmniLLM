package com.omnillm.interfaces.http.sse

import com.omnillm.interfaces.http.SseEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * SSE framing helpers (CORE-INTERFACE §4).
 *
 * Rules:
 * - Pre-stream errors → HTTP JSON (caller must not enter this path after failure).
 * - After first event write, errors become terminal SSE events — never a late HTTP status.
 * - Stateless Session default: disconnect ends observer; Session is not auto-reused.
 * - Delivery: socket write ≠ app delivery (ADR-011); clients must query on loss.
 */
object SseFraming {
    val CONTENT_TYPE: ContentType = ContentType.parse("text/event-stream")

    /** OpenAI-compatible terminal marker for chat.completion.chunk streams. */
    const val OPENAI_DONE: String = "[DONE]"

    /** OmniLLM terminal event name for durable request event streams. */
    const val EVENT_TERMINAL: String = "terminal"

    fun frame(event: SseEvent): String = buildString {
        if (event.id != null) {
            append("id: ").append(event.id).append('\n')
        }
        if (event.event != null) {
            append("event: ").append(event.event).append('\n')
        }
        for (line in event.data.split('\n')) {
            append("data: ").append(line).append('\n')
        }
        append('\n')
    }

    /**
     * Commit SSE response and stream events. Terminal event ends the write loop.
     * Extra headers (e.g. X-OmniLLM-Request-Id) must be set before body start.
     */
    suspend fun writeStream(
        call: ApplicationCall,
        events: Flow<SseEvent>,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        for ((name, value) in extraHeaders) {
            call.response.header(name, value)
        }
        call.response.header("Cache-Control", "no-cache")
        call.response.header("Connection", "keep-alive")
        // Stateless Session default — no sticky session cookie.
        call.response.header("X-OmniLLM-Session-Disposition", "STATELESS")

        call.respondBytesWriter(
            contentType = CONTENT_TYPE,
            status = HttpStatusCode.OK,
        ) {
            events.collect { event ->
                writeStringUtf8(frame(event))
                flush()
                if (event.isTerminal) return@collect
            }
        }
    }

    fun openAiDoneEvent(): SseEvent =
        SseEvent(data = OPENAI_DONE, isTerminal = true)

    fun terminalErrorEvent(jsonPayload: String): SseEvent =
        SseEvent(
            data = jsonPayload,
            event = EVENT_TERMINAL,
            isTerminal = true,
        )
}
