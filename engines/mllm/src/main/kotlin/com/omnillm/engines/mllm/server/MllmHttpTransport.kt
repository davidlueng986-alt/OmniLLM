package com.omnillm.engines.mllm.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Loopback HTTP/SSE transport to the upstream mllm in-app Go server
 * (`127.0.0.1:8080/v1/chat/completions`). Host-testable via a fake
 * implementation; production uses [OkHttpMllmTransport].
 *
 * Cancellation semantics (ENGINE-MLLM §6): the upstream server has no cancel
 * RPC — closing the request stream breaks its `r.Context()` poll loop. The
 * caller must treat connection close as *cooperative stop intent*, never as
 * proof of native decode halt.
 */
interface MllmHttpTransport {

    /** Adapter-enforced credential header (the upstream server itself ignores it). */
    companion object {
        const val CREDENTIAL_HEADER: String = "X-Omni-Mllm-Credential"
    }

    /** Terminal result of one chat-completions stream. */
    sealed class Result {
        /** Stream ended normally ([DONE] / finish_reason). */
        data object Completed : Result()

        /** Cancelled by [cancelFlag] — connection closed cooperatively. */
        data object Cancelled : Result()

        /** HTTP/transport failure. [status] is the HTTP code (0 when none). */
        data class Failed(val status: Int, val message: String) : Result()
    }

    /**
     * POST [bodyJson] to [url] and stream SSE `data:` lines to [onData]
     * until [onData] returns false, the stream ends, [cancelFlag] flips, or
     * an error occurs.
     *
     * @param credentialHeader optional adapter-enforced auth header value.
     */
    fun postChatCompletions(
        url: String,
        bodyJson: String,
        credentialHeader: String?,
        cancelFlag: () -> Boolean,
        onData: (String) -> Boolean,
    ): Result
}

/**
 * OkHttp-backed transport. Reuses the caller-provided [client] (connection pool
 * shared). Never leaves the loopback destination space enforced by
 * [MllmServerBackend].
 */
class OkHttpMllmTransport(private val client: OkHttpClient) : MllmHttpTransport {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun postChatCompletions(
        url: String,
        bodyJson: String,
        credentialHeader: String?,
        cancelFlag: () -> Boolean,
        onData: (String) -> Boolean,
    ): MllmHttpTransport.Result {
        if (cancelFlag()) return MllmHttpTransport.Result.Cancelled

        val builder = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(jsonMediaType))
        credentialHeader?.let { builder.header(MllmHttpTransport.CREDENTIAL_HEADER, it) }
        val request = builder.build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val snippet = response.body?.string()?.take(512).orEmpty()
                    return MllmHttpTransport.Result.Failed(
                        response.code,
                        "server returned HTTP ${response.code}: $snippet",
                    )
                }
                val source = response.body?.source() ?: return MllmHttpTransport.Result.Failed(
                    0,
                    "empty response body",
                )
                while (!source.exhausted()) {
                    if (cancelFlag()) {
                        // Cooperative stop intent: closing the body breaks the
                        // server's r.Context() poll loop.
                        return MllmHttpTransport.Result.Cancelled
                    }
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (!onData(data)) break
                }
                return MllmHttpTransport.Result.Completed
            }
        } catch (e: IOException) {
            return MllmHttpTransport.Result.Failed(0, e.message ?: "transport failure")
        }
    }
}
