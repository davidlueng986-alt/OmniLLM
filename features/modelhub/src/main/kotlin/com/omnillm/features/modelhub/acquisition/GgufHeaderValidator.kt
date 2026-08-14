package com.omnillm.features.modelhub.acquisition

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header-level GGUF validator (C-03): a file LABELED gguf must actually PARSE as
 * GGUF before it becomes installable. Pure JVM — no engine, no full-file scan.
 *
 * GGUF layout (llama.cpp `ggml/include/gguf.h`):
 * ```
 *  0: uint32 magic      = 0x46554747 ("GGUF" little-endian)
 *  4: uint32 version    = 1..3 (llama.cpp b9999 supports v1–v3)
 *  8: uint64 tensor_count      (sanity-bounded)
 * 16: uint64 metadata_kv_count (sanity-bounded)
 * ```
 * Only the first [HEADER_LENGTH] bytes are read — deep KV/tensor parse is out of
 * scope for the import gate (performance; header parse IS the dry-load evidence).
 */
object GgufHeaderValidator {

    /** "GGUF" as the 4 on-disk bytes (0x47 0x47 0x55 0x46). */
    val GGUF_MAGIC_BYTES: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    /** Same magic read as uint32 little-endian (matches gguf.h GGUF_MAGIC). */
    const val GGUF_MAGIC_LE: Long = 0x46554747L

    const val HEADER_LENGTH: Int = 24

    /** llama.cpp b9999 supports GGUF v1..v3; anything else fails closed. */
    const val MIN_SUPPORTED_VERSION: Int = 1
    const val MAX_SUPPORTED_VERSION: Int = 3

    /** Sanity ceiling for uint64 counts — a real model never exceeds 1M tensors/KVs. */
    const val MAX_TENSOR_COUNT: Long = 1_000_000L
    const val MAX_METADATA_KV_COUNT: Long = 1_000_000L

    /** Parsed header of a validated GGUF file (dry-load evidence, no execution). */
    data class Header(
        val version: Int,
        val tensorCount: Long,
        val metadataKvCount: Long,
    )

    sealed interface Outcome {
        data class Valid(val header: Header) : Outcome
        data class Invalid(
            val reason: String,
            val field: String,
            val actual: String,
        ) : Outcome
    }

    /** Pure function over the first [HEADER_LENGTH] bytes. */
    fun validate(header: ByteArray): Outcome {
        if (header.size < HEADER_LENGTH) {
            return Outcome.Invalid(
                reason = "truncated header",
                field = "header",
                actual = "${header.size} bytes (< $HEADER_LENGTH)",
            )
        }
        if (!header[0].equalsByte(GGUF_MAGIC_BYTES[0]) ||
            !header[1].equalsByte(GGUF_MAGIC_BYTES[1]) ||
            !header[2].equalsByte(GGUF_MAGIC_BYTES[2]) ||
            !header[3].equalsByte(GGUF_MAGIC_BYTES[3])
        ) {
            val actualMagic = String(header, 0, 4, Charsets.ISO_8859_1)
            return Outcome.Invalid(
                reason = "bad magic",
                field = "magic",
                actual = "'$actualMagic' (expected 'GGUF')",
            )
        }

        val buffer = ByteBuffer.wrap(header, 4, HEADER_LENGTH - 4).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.int
        if (version < MIN_SUPPORTED_VERSION || version > MAX_SUPPORTED_VERSION) {
            return Outcome.Invalid(
                reason = "unsupported version",
                field = "version",
                actual = version.toString(),
            )
        }
        val tensorCount = buffer.long
        if (tensorCount >= MAX_TENSOR_COUNT) {
            return Outcome.Invalid(
                reason = "tensor_count out of sanity bounds",
                field = "tensor_count",
                actual = tensorCount.toString(),
            )
        }
        val metadataKvCount = buffer.long
        if (metadataKvCount >= MAX_METADATA_KV_COUNT) {
            return Outcome.Invalid(
                reason = "metadata_kv_count out of sanity bounds",
                field = "metadata_kv_count",
                actual = metadataKvCount.toString(),
            )
        }
        return Outcome.Valid(Header(version, tensorCount, metadataKvCount))
    }

    /**
     * Reads at most [HEADER_LENGTH] bytes from [input] and validates.
     * The stream is consumed by exactly one bounded read — no full-file scan.
     */
    fun validate(input: InputStream): Outcome {
        val header = ByteArray(HEADER_LENGTH)
        var read = 0
        while (read < HEADER_LENGTH) {
            val n = try {
                input.read(header, read, HEADER_LENGTH - read)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            read += n
        }
        if (read < HEADER_LENGTH) {
            return Outcome.Invalid(
                reason = "truncated header",
                field = "header",
                actual = "$read bytes (< $HEADER_LENGTH)",
            )
        }
        return validate(header)
    }

    private fun Byte.equalsByte(other: Byte): Boolean = this.toInt() == other.toInt()
}
