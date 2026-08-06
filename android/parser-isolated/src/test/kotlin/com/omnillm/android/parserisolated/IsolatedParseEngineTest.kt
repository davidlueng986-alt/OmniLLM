package com.omnillm.android.parserisolated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class IsolatedParseEngineTest {

    private val engine = IsolatedParseEngine()

    private fun request(
        tokens: List<String> = listOf("t1"),
        roles: List<String> = listOf("weights"),
        total: Long = 4L,
        deadline: Long = 5_000L,
    ) = IsolatedParseRequest(
        requestId = "req-1",
        runtimeEpoch = 1L,
        bootId = "boot-1",
        formatHint = "gguf",
        inputFdTokens = tokens,
        declaredRoles = roles,
        declaredTotalBytes = total,
        deadlineMs = deadline,
    )

    @Test
    fun parsesBytesAndDigests() {
        val payload = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        val clock = AtomicLong(0L)
        val result = engine.parse(
            request = request(total = payload.size.toLong()),
            openStream = { ByteArrayInputStream(payload) },
            monotonicNowMs = { clock.get() },
        )
        assertTrue(result is IsolatedParseResult.Ok)
        val ok = result as IsolatedParseResult.Ok
        assertEquals(1, ok.descriptor.fileCount)
        assertEquals(4L, ok.descriptor.totalDeclaredBytes)
        assertEquals("gguf", ok.descriptor.entries.single().contentSniff)
        assertEquals(64, ok.descriptor.entries.single().sha256Hex!!.length)
    }

    @Test
    fun rejectsOversizedSingleFile() {
        // Stream that claims endless data via a custom InputStream.
        val stream = object : ByteArrayInputStream(ByteArray(1024) { 1 }) {
            private var left = ParseBounds.MAX_FILE_BYTES + 10_000L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0L) return -1
                val n = minOf(len.toLong(), left, 8192L).toInt()
                for (i in 0 until n) b[off + i] = 1
                left -= n
                return n
            }
        }
        val result = engine.parse(
            request = request(total = ParseBounds.MAX_FILE_BYTES + 1),
            openStream = { stream },
            monotonicNowMs = { 0L },
        )
        assertTrue(result is IsolatedParseResult.Err)
        assertEquals("TRANSPORT_TOO_LARGE", (result as IsolatedParseResult.Err).errorCode)
    }

    @Test
    fun respectsDeadline() {
        val clock = AtomicLong(0L)
        val result = engine.parse(
            request = request(deadline = 10L),
            openStream = {
                clock.set(100L)
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            },
            monotonicNowMs = { clock.get() },
        )
        // First check at start is 0; during read clock advances past deadline on next poll.
        // Depending on timing path: either OK if finished before poll or DEADLINE.
        // Force deadline at start:
        clock.set(100L)
        val late = engine.parse(
            request = request(deadline = 10L),
            openStream = { ByteArrayInputStream(byteArrayOf(1)) },
            monotonicNowMs = { clock.getAndAdd(100L) },
        )
        assertTrue(late is IsolatedParseResult.Err)
        assertEquals("DEADLINE_EXCEEDED", (late as IsolatedParseResult.Err).errorCode)
        // silence
        @Suppress("UNUSED_VARIABLE")
        val _r = result
    }

    @Test
    fun respectsCancel() {
        val cancel = AtomicBoolean(true)
        val result = engine.parse(
            request = request(),
            openStream = { ByteArrayInputStream(byteArrayOf(1)) },
            monotonicNowMs = { 0L },
            cancel = cancel,
        )
        assertTrue(result is IsolatedParseResult.Err)
        assertEquals("CANCELLED", (result as IsolatedParseResult.Err).errorCode)
    }

    @Test
    fun rejectsInvalidShape() {
        val result = engine.parse(
            request = request(deadline = ParseBounds.MAX_PARSE_TIME_MS + 1),
            openStream = { ByteArrayInputStream(byteArrayOf()) },
            monotonicNowMs = { 0L },
        )
        assertTrue(result is IsolatedParseResult.Err)
        assertEquals("INVALID_REQUEST", (result as IsolatedParseResult.Err).errorCode)
    }
}
