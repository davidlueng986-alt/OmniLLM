package com.omnillm.runtime.policy.security

import com.omnillm.core.ports.security.EncryptedRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Binary envelope for [EncryptedRecord] when a table has a single BLOB column
 * (content_reports.encrypted_proposal / encrypted_payload).
 *
 * Format (big-endian):
 * ```
 * magic "OCR1" (4)
 * profileId UTF-8 length-prefixed (u16 + bytes)
 * recordType UTF-8 length-prefixed
 * recordId UTF-8 length-prefixed
 * schemaVersion u32
 * keyVersion u32
 * expiresAtEpochMs i64
 * nonce length-prefixed (u16 + bytes)
 * ciphertext length-prefixed (u32 + bytes)
 * ```
 *
 * Fail closed on unknown magic / truncated input.
 */
object EncryptedRecordCodec {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())

    fun isSealedEnvelope(blob: ByteArray): Boolean =
        blob.size >= 4 &&
            blob[0] == MAGIC[0] &&
            blob[1] == MAGIC[1] &&
            blob[2] == MAGIC[2] &&
            blob[3] == MAGIC[3]

    fun encode(record: EncryptedRecord): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.write(MAGIC)
            writeUtf16Prefixed(dos, record.profileId)
            writeUtf16Prefixed(dos, record.recordType)
            writeUtf16Prefixed(dos, record.recordId)
            dos.writeInt(record.schemaVersion)
            dos.writeInt(record.keyVersion)
            dos.writeLong(record.expiresAtEpochMs)
            writeBytes16(dos, record.nonce)
            writeBytes32(dos, record.ciphertext)
        }
        return out.toByteArray()
    }

    fun decode(blob: ByteArray): EncryptedRecord? {
        if (!isSealedEnvelope(blob)) return null
        return try {
            DataInputStream(ByteArrayInputStream(blob)).use { dis ->
                val magic = ByteArray(4)
                dis.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                val profileId = readUtf16Prefixed(dis) ?: return null
                val recordType = readUtf16Prefixed(dis) ?: return null
                val recordId = readUtf16Prefixed(dis) ?: return null
                val schemaVersion = dis.readInt()
                val keyVersion = dis.readInt()
                val expiresAt = dis.readLong()
                val nonce = readBytes16(dis) ?: return null
                val ciphertext = readBytes32(dis) ?: return null
                EncryptedRecord(
                    profileId = profileId,
                    recordType = recordType,
                    recordId = recordId,
                    schemaVersion = schemaVersion,
                    keyVersion = keyVersion,
                    expiresAtEpochMs = expiresAt,
                    nonce = nonce,
                    ciphertext = ciphertext,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUtf16Prefixed(dos: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 0xffff) { "utf field too long" }
        dos.writeShort(bytes.size)
        dos.write(bytes)
    }

    private fun readUtf16Prefixed(dis: DataInputStream): String? {
        val len = dis.readUnsignedShort()
        if (len < 0) return null
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeBytes16(dos: DataOutputStream, value: ByteArray) {
        require(value.size <= 0xffff) { "byte16 field too long" }
        dos.writeShort(value.size)
        dos.write(value)
    }

    private fun readBytes16(dis: DataInputStream): ByteArray? {
        val len = dis.readUnsignedShort()
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return bytes
    }

    private fun writeBytes32(dos: DataOutputStream, value: ByteArray) {
        dos.writeInt(value.size)
        dos.write(value)
    }

    private fun readBytes32(dis: DataInputStream): ByteArray? {
        val len = dis.readInt()
        if (len < 0 || len > 16 * 1024 * 1024) return null
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return bytes
    }
}
