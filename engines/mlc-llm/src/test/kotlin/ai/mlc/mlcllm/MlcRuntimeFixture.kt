// TEST-ONLY fixture: faithful JVM re-implementation of the official MLC-LLM
// Android Kotlin runtime API (package `ai.mlc.mlcllm`), mirroring
// `android/mlc4j/src/main/java/ai/mlc/mlcllm/` at the pin
// `2f78caa4db0f90730a11ee3bb5cbd5f23bf67f9f` (mlc-ai/mlc-llm).
//
// Purpose: exercise `MlcRuntimeBridge`'s reflective binding end-to-end on the
// host (JVM) — the real generated mlc4j artifact is Android-only and produced
// per-app by `mlc_llm package`, so it cannot be a Maven test dependency.
// Class names, member signatures and streaming semantics mirror upstream so
// that the same binding code runs against the real artifact in production.
package ai.mlc.mlcllm

import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionRequest
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionRole
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionStreamResponse
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionStreamResponseChoice
import ai.mlc.mlcllm.OpenAIProtocol.ChatTool
import ai.mlc.mlcllm.OpenAIProtocol.CompletionUsage
import ai.mlc.mlcllm.OpenAIProtocol.ResponseFormat
import ai.mlc.mlcllm.OpenAIProtocol.StreamOptions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Behavioral hooks for tests (upstream has no such hooks). */
object MlcFixtureControl {
    @Volatile
    var deltas: List<String> = listOf("Hello", ", ", "world!")

    @Volatile
    var promptTokens: Int = 7

    @Volatile
    var completionTokens: Int = 0

    @Volatile
    var finishReason: String? = "stop"

    @Volatile
    var streamDelayMs: Long = 1

    @Volatile
    var failReload: Boolean = false

    @Volatile
    var lastMessages: List<ChatCompletionMessage> = emptyList()

    @Volatile
    var lastMaxTokens: Int? = null

    @Volatile
    var lastTemperature: Float? = null

    @Volatile
    var lastTopP: Float? = null

    @Volatile
    var lastTopK: Int? = null

    @Volatile
    var lastStop: List<String>? = null

    @Volatile
    var reloadCount: Int = 0

    @Volatile
    var unloadCount: Int = 0

    @Volatile
    var lastModelPath: String? = null

    @Volatile
    var lastModelLib: String? = null
}

/** Mirrors upstream `class MLCEngine` (reload/reset/unload + chat.completions). */
class MLCEngine {
    val chat: Chat = Chat()

    fun reload(modelPath: String, modelLib: String) {
        MlcFixtureControl.reloadCount++
        MlcFixtureControl.lastModelPath = modelPath
        MlcFixtureControl.lastModelLib = modelLib
        if (MlcFixtureControl.failReload) {
            throw IllegalStateException("fixture: reload failed for " + modelLib)
        }
    }

    fun reset() {
        // upstream: jsonFFIEngine.reset() — no-op in fixture
    }

    fun unload() {
        MlcFixtureControl.unloadCount++
    }
}

/** Mirrors upstream `class Chat`. */
class Chat {
    val completions: Completions = Completions()
}

/** Mirrors upstream `class Completions` streaming `chat.completions.create`. */
class Completions {

    suspend fun create(request: ChatCompletionRequest): ReceiveChannel<ChatCompletionStreamResponse> {
        return stream(request)
    }

    // Full overload — same signature/order as upstream (18 args + defaults).
    suspend fun create(
        messages: List<ChatCompletionMessage>,
        model: String? = null,
        frequency_penalty: Float? = null,
        presence_penalty: Float? = null,
        logprobs: Boolean = false,
        top_logprobs: Int = 0,
        logit_bias: Map<Int, Float>? = null,
        max_tokens: Int? = null,
        n: Int = 1,
        seed: Int? = null,
        stop: List<String>? = null,
        stream: Boolean = true,
        stream_options: StreamOptions? = null,
        temperature: Float? = null,
        top_p: Float? = null,
        tools: List<ChatTool>? = null,
        user: String? = null,
        response_format: ResponseFormat? = null,
    ): ReceiveChannel<ChatCompletionStreamResponse> {
        MlcFixtureControl.lastMessages = messages
        MlcFixtureControl.lastMaxTokens = max_tokens
        MlcFixtureControl.lastTemperature = temperature
        MlcFixtureControl.lastTopP = top_p
        MlcFixtureControl.lastTopK = null
        MlcFixtureControl.lastStop = stop
        return stream(
            ChatCompletionRequest(
                messages = messages,
                max_tokens = max_tokens,
                temperature = temperature,
                top_p = top_p,
                stop = stop,
                stream = stream,
            ),
        )
    }

    private suspend fun stream(
        request: ChatCompletionRequest,
    ): ReceiveChannel<ChatCompletionStreamResponse> {
        val channel = Channel<ChatCompletionStreamResponse>(Channel.UNLIMITED)
        GlobalScope.launch {
            val id = "chatcmpl-fixture"
            val deltas = MlcFixtureControl.deltas
            for (i in deltas.indices) {
                delay(MlcFixtureControl.streamDelayMs)
                val last = i == deltas.size - 1
                channel.send(
                    ChatCompletionStreamResponse(
                        id = id,
                        choices = listOf(
                            ChatCompletionStreamResponseChoice(
                                finish_reason = if (last) MlcFixtureControl.finishReason else null,
                                index = 0,
                                delta = ChatCompletionMessage(
                                    role = ChatCompletionRole.assistant,
                                    content = deltas[i],
                                ),
                            ),
                        ),
                        system_fingerprint = "fp_fixture",
                    ),
                )
            }
            delay(MlcFixtureControl.streamDelayMs)
            MlcFixtureControl.completionTokens += deltas.sumOf { it.length }
            channel.send(
                ChatCompletionStreamResponse(
                    id = id,
                    choices = listOf(
                        ChatCompletionStreamResponseChoice(
                            finish_reason = MlcFixtureControl.finishReason,
                            index = 0,
                            delta = ChatCompletionMessage(role = ChatCompletionRole.assistant),
                        ),
                    ),
                    system_fingerprint = "fp_fixture",
                    usage = CompletionUsage(
                        prompt_tokens = MlcFixtureControl.promptTokens,
                        completion_tokens = MlcFixtureControl.completionTokens,
                        total_tokens = MlcFixtureControl.promptTokens + MlcFixtureControl.completionTokens,
                    ),
                ),
            )
            channel.close()
        }
        return channel
    }
}

/** Mirrors upstream `class OpenAIProtocol` nested types (API shape only). */
class OpenAIProtocol {

    enum class ChatCompletionRole {
        system,
        user,
        assistant,
        tool,
    }

    data class ChatCompletionMessageContent(
        val text: String? = null,
        val parts: List<Map<String, String>>? = null,
    ) {
        constructor(text: String) : this(text, null)
        constructor(parts: List<Map<String, String>>) : this(null, parts)

        fun isText(): Boolean = text != null

        fun isParts(): Boolean = parts != null

        fun asText(): String =
            text ?: (parts?.filter { it["type"] == "text" }
                ?.joinToString("") { it["text"] ?: "" } ?: "")
    }

    data class ChatCompletionMessage(
        val role: ChatCompletionRole,
        var content: ChatCompletionMessageContent? = null,
        var name: String? = null,
        var tool_calls: List<ChatToolCall>? = null,
        var tool_call_id: String? = null,
    ) {
        constructor(
            role: ChatCompletionRole,
            content: String,
            name: String? = null,
            tool_calls: List<ChatToolCall>? = null,
            tool_call_id: String? = null,
        ) : this(role, ChatCompletionMessageContent(content), name, tool_calls, tool_call_id)
    }

    data class ChatFunctionCall(
        val name: String,
        var arguments: Map<String, String>? = null,
    )

    data class ChatToolCall(
        val id: String = "call_fixture",
        val type: String = "function",
        val function: ChatFunctionCall,
    )

    data class ChatFunction(
        val name: String,
        var description: String? = null,
        val parameters: Map<String, String> = emptyMap(),
    )

    data class ChatTool(
        val type: String = "function",
        val function: ChatFunction,
    )

    data class CompletionUsageExtra(
        val prefill_tokens_per_s: Float? = null,
        val decode_tokens_per_s: Float? = null,
        val num_prefill_tokens: Int? = null,
    )

    data class CompletionUsage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int,
        val extra: CompletionUsageExtra? = null,
    )

    data class StreamOptions(
        val include_usage: Boolean = false,
    )

    data class ChatCompletionStreamResponseChoice(
        var finish_reason: String? = null,
        val index: Int = 0,
        val delta: ChatCompletionMessage,
        var lobprobs: LogProbs? = null,
    )

    data class LogProbsContent(
        val token: String,
        val logprob: Float,
    )

    data class LogProbs(
        var content: List<LogProbsContent> = listOf(),
    )

    data class ChatCompletionStreamResponse(
        val id: String,
        var choices: List<ChatCompletionStreamResponseChoice> = listOf(),
        var created: Int? = null,
        var model: String? = null,
        val system_fingerprint: String,
        var `object`: String? = null,
        val usage: CompletionUsage? = null,
    )

    data class ResponseFormat(
        val type: String,
        val schema: String? = null,
    )

    data class ChatCompletionRequest(
        val messages: List<ChatCompletionMessage>,
        val model: String? = null,
        val frequency_penalty: Float? = null,
        val presence_penalty: Float? = null,
        val logprobs: Boolean = false,
        val top_logprobs: Int = 0,
        val logit_bias: Map<Int, Float>? = null,
        val max_tokens: Int? = null,
        val n: Int = 1,
        val seed: Int? = null,
        val stop: List<String>? = null,
        val stream: Boolean = true,
        val stream_options: StreamOptions? = null,
        val temperature: Float? = null,
        val top_p: Float? = null,
        val tools: List<ChatTool>? = null,
        val user: String? = null,
        val response_format: ResponseFormat? = null,
    )
}
