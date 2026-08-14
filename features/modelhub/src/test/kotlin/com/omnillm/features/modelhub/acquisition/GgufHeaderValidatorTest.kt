package com.omnillm.features.modelhub.acquisition

import com.omnillm.features.modelhub.acquisition.GgufHeaderValidator.Outcome.Invalid
import com.omnillm.features.modelhub.acquisition.GgufHeaderValidator.Outcome.Valid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * C-03: GGUF header validator — pure parse gate for the import path.
 * Spec: magic 0x46554747 ("GGUF" LE) + uint32 version 1..3 +
 * uint64 tensor_count < 1_000_000 + uint64 metadata_kv_count < 1_000_000.
 */
class GgufHeaderValidatorTest {

    private fun header(
        version: Int = 3,
        tensorCount: Long = 0L,
        kvCount: Long = 0L,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(GgufHeaderValidator.HEADER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(GgufHeaderValidator.GGUF_MAGIC_BYTES)
        buffer.putInt(version)
        buffer.putLong(tensorCount)
        buffer.putLong(kvCount)
        return buffer.array()
    }

    private fun assertValid(outcome: GgufHeaderValidator.Outcome): GgufHeaderValidator.Header {
        assertTrue("expected Valid: $outcome", outcome is Valid)
        return (outcome as Valid).header
    }

    private fun assertInvalid(
        outcome: GgufHeaderValidator.Outcome,
        expectedReason: String,
        expectedField: String,
    ) {
        assertTrue("expected Invalid: $outcome", outcome is Invalid)
        val invalid = outcome as Invalid
        assertEquals(expectedReason, invalid.reason)
        assertEquals(expectedField, invalid.field)
    }

    @Test
    fun validMinimalHeader_v1v2v3_accepted() {
        for (v in 1..3) {
            val header = assertValid(GgufHeaderValidator.validate(header(version = v)))
            assertEquals(v, header.version)
            assertEquals(0L, header.tensorCount)
            assertEquals(0L, header.metadataKvCount)
        }
    }

    @Test
    fun validHeader_withCounts_parsesFields() {
        val parsed = assertValid(
            GgufHeaderValidator.validate(header(tensorCount = 42L, kvCount = 17L)),
        )
        assertEquals(3, parsed.version)
        assertEquals(42L, parsed.tensorCount)
        assertEquals(17L, parsed.metadataKvCount)
    }

    @Test
    fun textFile_namedGguf_rejectedBadMagic() {
        val text = "This is not a gguf file at all......".toByteArray(Charsets.UTF_8)
        assertInvalid(
            GgufHeaderValidator.validate(text),
            expectedReason = "bad magic",
            expectedField = "magic",
        )
    }

    @Test
    fun corruptMagic_rejected() {
        val broken = header().also { it[3] = 0x58 } // "GGU" + 'X'
        assertInvalid(
            GgufHeaderValidator.validate(broken),
            expectedReason = "bad magic",
            expectedField = "magic",
        )
        val random = ByteArray(GgufHeaderValidator.HEADER_LENGTH) { (it * 7).toByte() }
        assertInvalid(
            GgufHeaderValidator.validate(random),
            expectedReason = "bad magic",
            expectedField = "magic",
        )
    }

    @Test
    fun truncatedHeader_rejected() {
        assertInvalid(
            GgufHeaderValidator.validate(ByteArray(0)),
            expectedReason = "truncated header",
            expectedField = "header",
        )
        assertInvalid(
            GgufHeaderValidator.validate(ByteArray(23)),
            expectedReason = "truncated header",
            expectedField = "header",
        )
        // 4 magic bytes only — must not be accepted without counts.
        assertInvalid(
            GgufHeaderValidator.validate(GgufHeaderValidator.GGUF_MAGIC_BYTES),
            expectedReason = "truncated header",
            expectedField = "header",
        )
    }

    @Test
    fun unsupportedVersion_rejected() {
        for (v in listOf(0, 4, 99)) {
            assertInvalid(
                GgufHeaderValidator.validate(header(version = v)),
                expectedReason = "unsupported version",
                expectedField = "version",
            )
        }
    }

    @Test
    fun tensorCountOutOfBounds_rejected() {
        assertInvalid(
            GgufHeaderValidator.validate(header(tensorCount = 1_000_000L)),
            expectedReason = "tensor_count out of sanity bounds",
            expectedField = "tensor_count",
        )
        assertInvalid(
            GgufHeaderValidator.validate(header(tensorCount = Long.MAX_VALUE)),
            expectedReason = "tensor_count out of sanity bounds",
            expectedField = "tensor_count",
        )
        assertValid(GgufHeaderValidator.validate(header(tensorCount = 999_999L)))
    }

    @Test
    fun metadataKvCountOutOfBounds_rejected() {
        assertInvalid(
            GgufHeaderValidator.validate(header(kvCount = 1_000_000L)),
            expectedReason = "metadata_kv_count out of sanity bounds",
            expectedField = "metadata_kv_count",
        )
        assertInvalid(
            GgufHeaderValidator.validate(header(kvCount = Long.MAX_VALUE)),
            expectedReason = "metadata_kv_count out of sanity bounds",
            expectedField = "metadata_kv_count",
        )
        assertValid(GgufHeaderValidator.validate(header(kvCount = 999_999L)))
    }

    @Test
    fun streamVariant_readsExactlyHeaderAndValidates() {
        // Stream with trailing bytes beyond the header — only 24 read.
        val full = header(tensorCount = 7L) + ByteArray(64) { 0x00 }
        val parsed = assertValid(
            GgufHeaderValidator.validate(ByteArrayInputStream(full)),
        )
        assertEquals(7L, parsed.tensorCount)

        // Short stream → truncated.
        assertInvalid(
            GgufHeaderValidator.validate(ByteArrayInputStream(ByteArray(10))),
            expectedReason = "truncated header",
            expectedField = "header",
        )
        assertInvalid(
            GgufHeaderValidator.validate(ByteArrayInputStream(ByteArray(0))),
            expectedReason = "truncated header",
            expectedField = "header",
        )
    }
}
