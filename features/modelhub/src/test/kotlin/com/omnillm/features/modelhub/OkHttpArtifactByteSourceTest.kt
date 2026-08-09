package com.omnillm.features.modelhub.acquisition

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host tests for [OkHttpArtifactByteSource] (M1): bounded HTTPS-style download,
 * manual per-hop redirect re-validation, cancel fail-closed.
 * Uses a local loopback HTTP server with a test-only policy allowlist.
 */
class OkHttpArtifactByteSourceTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val testPolicy = DownloadUrlPolicy.Policy(
        allowedSchemes = setOf("http", "https"),
        hostAllowlist = setOf("127.0.0.1"),
    )

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun registerHandler(path: String, body: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { exchange: HttpExchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (status == 200) body.size.toLong() else -1L)
            if (status == 200) {
                val out: OutputStream = exchange.responseBody
                out.write(body)
                out.close()
            }
            exchange.close()
        }
    }

    private fun readAll(source: ArtifactByteSource): ByteArray {
        val input = source.open("WEIGHTS", AtomicBoolean(false))
        assertTrue(input is OmniResult.Ok)
        return (input as OmniResult.Ok).value.use { it.readBytes() }
    }

    @Test
    fun open_200_deliversPayloadBytes() {
        val payload = "gguf-bytes-abc".toByteArray()
        registerHandler("/model.gguf", payload)
        val source = OkHttpArtifactByteSource(url("/model.gguf"), urlPolicy = testPolicy)
        val bytes = readAll(source)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test
    fun open_redirect_followsWithinPolicy() {
        val payload = "redirected-payload".toByteArray()
        registerHandler("/final.gguf", payload)
        registerHandler("/start", ByteArray(0), status = 302, headers = mapOf("Location" to "/final.gguf"))
        val source = OkHttpArtifactByteSource(url("/start"), urlPolicy = testPolicy)
        val bytes = readAll(source)
        assertTrue(bytes.contentEquals(payload))
    }

    @Test
    fun open_redirectToRejectedHost_failsClosed() {
        registerHandler("/start2", ByteArray(0), status = 302, headers = mapOf("Location" to "https://evil.example/x.gguf"))
        // Policy: evil.example not allowlisted → per-hop re-validation must reject.
        val source = OkHttpArtifactByteSource(url("/start2"), urlPolicy = testPolicy)
        val result = source.open("WEIGHTS", AtomicBoolean(false))
        assertTrue("redirect must fail closed", result is OmniResult.Err)
    }

    @Test
    fun open_httpError_mapsToCatalogError() {
        registerHandler("/missing.gguf", ByteArray(0), status = 404)
        val source = OkHttpArtifactByteSource(url("/missing.gguf"), urlPolicy = testPolicy)
        val result = source.open("WEIGHTS", AtomicBoolean(false))
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun open_cancelled_failsClosedImmediately() {
        registerHandler("/cancel.gguf", "x".toByteArray())
        val source = OkHttpArtifactByteSource(url("/cancel.gguf"), urlPolicy = testPolicy)
        val result = source.open("WEIGHTS", AtomicBoolean(true))
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun open_rejectsUrlOutsidePolicy() {
        val source = OkHttpArtifactByteSource("https://public.example.com/m.gguf", urlPolicy = testPolicy)
        val result = source.open("WEIGHTS", AtomicBoolean(false))
        assertTrue("non-allowlisted host must fail closed", result is OmniResult.Err)
    }

    @Test
    fun boundedStream_enforcesByteCap() {
        val payload = "0123456789".toByteArray()
        registerHandler("/big.gguf", payload)
        val source = OkHttpArtifactByteSource(
            url("/big.gguf"),
            urlPolicy = testPolicy,
            limits = com.omnillm.runtime.policy.download.DownloadTransferLimits(
                maxBytesPerConnection = 5L,
            ),
        )
        val result = source.open("WEIGHTS", AtomicBoolean(false))
        assertTrue(result is OmniResult.Ok)
        val read = (result as OmniResult.Ok).value
        val e = try {
            read.readBytes()
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("byte cap must abort the stream", e != null)
        read.close()
    }

    @Test
    fun resolver_withNetworkFactory_resolvesRealUrl() {
        val made = AtomicBoolean(false)
        val outcome = PinnedDownloadResolver.resolve(
            sourceUrl = "https://models.omnillm.dev/gemma.gguf",
            policy = DownloadUrlPolicy.Policy(
                hostAllowlist = setOf("models.omnillm.dev"),
            ),
            networkSourceFactory = { url ->
                made.set(true)
                OkHttpArtifactByteSource(url, urlPolicy = testPolicy)
            },
        )
        assertTrue("factory must be invoked", made.get())
        assertTrue(outcome is PinnedDownloadResolver.Outcome.Ready)
        assertEquals(
            "https://models.omnillm.dev/gemma.gguf",
            (outcome as PinnedDownloadResolver.Outcome.Ready).normalizedUrl,
        )
    }

    @Test
    fun resolver_withoutNetworkFactory_realUrlFailsClosed() {
        val outcome = PinnedDownloadResolver.resolve(
            sourceUrl = "https://models.omnillm.dev/gemma.gguf",
            policy = DownloadUrlPolicy.Policy(
                hostAllowlist = setOf("models.omnillm.dev"),
            ),
        )
        assertTrue(outcome is PinnedDownloadResolver.Outcome.Rejected)
    }
}
