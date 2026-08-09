package com.omnillm.features.modelhub.acquisition

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.policy.download.DownloadTransferLimits
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import com.omnillm.runtime.policy.download.ResolvedAddressPolicy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real HTTPS artifact source (SHIP_BACKLOG M1 / SEC-INPUT §3 / SEC-SUPPLY §5).
 *
 * - Every hop (initial URL + each redirect Location) is re-admitted through
 *   [DownloadUrlPolicy] (scheme/host/port allowlist, loopback deny, hop cap).
 * - Every DNS resolution is re-checked against [ResolvedAddressPolicy]
 *   (SEC-05): DNS-rebinding / private-IP SSRF defense on each resolved address.
 * - Bounded stream: byte cap + read deadline + cooperative cancel via the
 *   acquisition pipeline's [AtomicBoolean].
 * - No automatic OkHttp redirect following — redirects are manual so each hop
 *   goes through policy (no silent cross-host redirect).
 *
 * Control-plane only: constructed in `:runtime` (ADR-010 / INV-001).
 */
class OkHttpArtifactByteSource(
    private val url: String,
    private val urlPolicy: DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT,
    private val limits: DownloadTransferLimits = DownloadTransferLimits.DEFAULT,
    private val addressPolicy: ResolvedAddressPolicy.Policy = ResolvedAddressPolicy.Policy.DEFAULT,
    private val client: OkHttpClient = defaultClient(addressPolicy),
) : ArtifactByteSource {

    override fun open(role: String, cancel: AtomicBoolean): OmniResult<InputStream> {
        if (cancel.get()) {
            return OmniResult.err(OmniError.CANCELLED(message = "download open cancelled"))
        }
        var currentUrl = url
        var hop = 0
        while (true) {
            val admit = DownloadUrlPolicy.admitUrl(currentUrl, urlPolicy)
            if (admit is DownloadUrlPolicy.Outcome.Rejected) {
                return OmniResult.err(admit.error)
            }
            val accepted = admit as DownloadUrlPolicy.Outcome.Accepted

            val request = Request.Builder()
                .url(accepted.normalizedUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "OmniLLM/0.1.0 (local edge LLM platform)")
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                // SEC-05: resolved-address policy denial maps to INVALID_REQUEST
                // (same catalog family as URL policy); transport failures map to
                // STREAM_INTERRUPTED / DEADLINE_EXCEEDED (no invented codes).
                if (e is ResolvedAddressDeniedException) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = e.message ?: "resolved address denied",
                            details = mapOf("host" to accepted.normalizedUrl),
                        ),
                    )
                }
                val deadline = e is okhttp3.internal.http2.StreamResetException ||
                    e is java.net.SocketTimeoutException
                return OmniResult.err(
                    if (deadline) {
                        OmniError.DEADLINE_EXCEEDED(
                            message = "download deadline exceeded: ${e.javaClass.simpleName}",
                        )
                    } else {
                        OmniError.STREAM_INTERRUPTED(
                            message = "download stream interrupted: ${e.javaClass.simpleName}",
                        )
                    },
                )
            }

            when (response.code) {
                in 300..399 -> {
                    val location = response.header("Location")
                    response.close()
                    if (location.isNullOrBlank()) {
                        return OmniResult.err(
                            OmniError.INVALID_REQUEST(
                                message = "redirect without Location",
                                details = mapOf("status" to response.code.toString()),
                            ),
                        )
                    }
                    // Per-hop policy re-validation (SEC-INPUT §3 / SEC-SUPPLY §5).
                    val redirect = DownloadUrlPolicy.admitRedirect(
                        locationHeader = location,
                        hopIndex = hop,
                        policy = urlPolicy,
                        base = URI(currentUrl),
                    )
                    when (redirect) {
                        is DownloadUrlPolicy.Outcome.Rejected -> return OmniResult.err(redirect.error)
                        is DownloadUrlPolicy.Outcome.Accepted -> {
                            currentUrl = redirect.normalizedUrl
                            hop++
                        }
                    }
                }
                200 -> {
                    val body = response.body
                        ?: run {
                            response.close()
                            return OmniResult.err(
                                OmniError.INVALID_REQUEST(message = "download response without body"),
                            )
                        }
                    return OmniResult.ok(
                        BoundedDownloadStream(
                            response = response,
                            source = body.byteStream(),
                            cancel = cancel,
                            maxBytes = limits.maxBytesPerConnection,
                        ),
                    )
                }
                else -> {
                    val status = response.code
                    val message = response.message
                    response.close()
                    // 4xx = semantic request error; 5xx = retryable interruption.
                    return OmniResult.err(
                        if (status in 400..499) {
                            OmniError.INVALID_REQUEST(
                                message = "download rejected by server HTTP $status",
                                details = mapOf(
                                    "status" to status.toString(),
                                    "message" to (message ?: ""),
                                ),
                            )
                        } else {
                            OmniError.STREAM_INTERRUPTED(
                                message = "download HTTP $status",
                                details = mapOf(
                                    "status" to status.toString(),
                                    "message" to (message ?: ""),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    /**
     * Bounded, cancel-aware stream wrapper. Enforces the connection byte cap and
     * aborts reads after the transfer deadline; cancel() stops future reads.
     * The underlying response is closed by [close] (idempotent).
     */
    private class BoundedDownloadStream(
        private val response: okhttp3.Response,
        private val source: InputStream,
        private val cancel: AtomicBoolean,
        private val maxBytes: Long,
    ) : InputStream() {
        private var consumed: Long = 0L
        private var closed: Boolean = false
        private val deadlineMs: Long =
            System.currentTimeMillis() + DownloadTransferLimits.DEFAULT_MAX_TIME_MS

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(!closed) { "stream closed" }
            check(!cancel.get()) { "download cancelled" }
            check(System.currentTimeMillis() <= deadlineMs) { "download deadline exceeded" }
            if (consumed >= maxBytes) {
                throw IllegalStateException("download exceeds max bytes $maxBytes")
            }
            val max = minOf(len.toLong(), maxBytes - consumed).toInt()
            if (max <= 0) return -1
            val n = source.read(b, off, max)
            if (n > 0) consumed += n
            return n
        }

        override fun available(): Int = source.available()

        override fun close() {
            if (closed) return
            closed = true
            try {
                source.close()
            } finally {
                response.close()
            }
        }
    }

    companion object {
        fun defaultClient(
            addressPolicy: ResolvedAddressPolicy.Policy = ResolvedAddressPolicy.Policy.DEFAULT,
        ): OkHttpClient =
            OkHttpClient.Builder()
                .dns(PolicyCheckingDns(addressPolicy))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build()
    }

    /**
     * SEC-05: OkHttp [Dns] that resolves then re-checks EVERY address against
     * [ResolvedAddressPolicy] (applied on every hop since redirects re-resolve).
     * Denied resolutions throw [ResolvedAddressDeniedException] which the
     * caller maps to INVALID_REQUEST (fail closed on DNS rebinding / SSRF).
     */
    class PolicyCheckingDns(
        private val policy: ResolvedAddressPolicy.Policy = ResolvedAddressPolicy.Policy.DEFAULT,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = Dns.SYSTEM.lookup(hostname)
            when (val outcome = ResolvedAddressPolicy.admitAll(resolved, policy)) {
                is ResolvedAddressPolicy.Outcome.Rejected ->
                    throw ResolvedAddressDeniedException(outcome.reason)
                is ResolvedAddressPolicy.Outcome.Accepted -> Unit
            }
            return resolved
        }
    }

    /** Marker exception for resolved-address policy denials (SEC-05). */
    class ResolvedAddressDeniedException(reason: String) :
        Exception("resolved address denied: $reason")
}
