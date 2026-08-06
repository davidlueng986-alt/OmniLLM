package com.omnillm.data.modelstore

import com.omnillm.core.identity.IdentityHashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class StreamMaterializerTest {

    @Test
    fun copiesAndDigests() {
        val payload = "hello-omnillm".toByteArray()
        val out = ByteArrayOutputStream()
        val clock = AtomicLong(0L)
        val result = StreamMaterializer.copyBounded(
            input = ByteArrayInputStream(payload),
            output = out,
            request = StreamMaterializer.CopyRequest(
                role = "weights",
                materializeHandle = "quarantine/job/a/weights",
                expectedByteLength = payload.size.toLong(),
                expectedDigestHex = IdentityHashing.sha256Hex(payload),
            ),
            monotonicNowMs = { clock.get() },
        )
        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertEquals(payload.size.toLong(), outcome.byteLength)
        assertEquals(IdentityHashing.sha256Hex(payload), outcome.sha256Hex)
        assertTrue(out.toByteArray().contentEquals(payload))
    }

    @Test
    fun rejectsSizeMismatch() {
        val payload = byteArrayOf(1, 2, 3)
        val result = StreamMaterializer.copyBounded(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            request = StreamMaterializer.CopyRequest(
                role = "weights",
                materializeHandle = "h",
                expectedByteLength = 10L,
            ),
            monotonicNowMs = { 0L },
        )
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as MaterializeException).error
        assertTrue(err is MaterializeError.SizeMismatch)
    }

    @Test
    fun rejectsOverCap() {
        val bounds = MaterializeBounds(maxFileBytes = 4L, maxTotalBytes = 4L)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val result = StreamMaterializer.copyBounded(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            request = StreamMaterializer.CopyRequest(
                role = "weights",
                materializeHandle = "h",
                bounds = bounds,
            ),
            monotonicNowMs = { 0L },
        )
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as MaterializeException).error
        assertTrue(err is MaterializeError.TooLarge)
        assertEquals("TRANSPORT_TOO_LARGE", err.toCatalogCode())
    }
}
