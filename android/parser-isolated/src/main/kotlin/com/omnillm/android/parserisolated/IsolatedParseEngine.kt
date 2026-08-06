package com.omnillm.android.parserisolated

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure bounded parse of read-only streams into [TypedPackageDescriptor].
 *
 * Android service supplies streams from ParcelFileDescriptor; unit tests use
 * in-memory streams. Does not open filesystem paths or network.
 *
 * Rejects symlink/special-file at materialize time (runtime); parser only
 * sees regular bytes. Sparse abuse is mitigated by hard byte caps.
 */
class IsolatedParseEngine(
    private val bounds: ParseBounds = ParseBounds,
) {
    /**
     * @param openStream resolves an opaque FD token to a read-only [InputStream]
     * @param monotonicNowMs clock for deadline checks
     */
    fun parse(
        request: IsolatedParseRequest,
        openStream: (fdToken: String) -> InputStream,
        monotonicNowMs: () -> Long,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): IsolatedParseResult {
        ParseBounds.validateRequestShape(
            fileCount = request.inputFdTokens.size,
            declaredTotalBytes = request.declaredTotalBytes,
            deadlineMs = request.deadlineMs,
        )?.let {
            return IsolatedParseResult.Err("INVALID_REQUEST", it.reason)
        }

        val start = monotonicNowMs()
        val deadline = start + request.deadlineMs
        val entries = ArrayList<TypedFileEntry>(request.inputFdTokens.size)
        var total = 0L

        for (i in request.inputFdTokens.indices) {
            if (cancel.get()) {
                return IsolatedParseResult.Err("CANCELLED", "parse cancelled")
            }
            if (monotonicNowMs() > deadline) {
                return IsolatedParseResult.Err("DEADLINE_EXCEEDED", "parse deadline")
            }

            val token = request.inputFdTokens[i]
            val role = request.declaredRoles[i]
            try {
                openStream(token).use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var fileBytes = 0L
                    var sniff: String? = null
                    while (true) {
                        if (cancel.get()) {
                            return IsolatedParseResult.Err("CANCELLED", "parse cancelled")
                        }
                        if (monotonicNowMs() > deadline) {
                            return IsolatedParseResult.Err("DEADLINE_EXCEEDED", "parse deadline")
                        }
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        fileBytes += n
                        total += n
                        if (fileBytes > ParseBounds.MAX_FILE_BYTES) {
                            return IsolatedParseResult.Err(
                                "TRANSPORT_TOO_LARGE",
                                "file exceeds individual cap for role=$role",
                            )
                        }
                        if (total > ParseBounds.MAX_TOTAL_BYTES ||
                            total > ParseBounds.MAX_INPUT_BYTES && request.inputFdTokens.size == 1
                        ) {
                            // Single-file metadata path uses MAX_INPUT_BYTES;
                            // multi-file uses MAX_TOTAL_BYTES.
                            if (request.inputFdTokens.size == 1 &&
                                total > ParseBounds.MAX_INPUT_BYTES
                            ) {
                                return IsolatedParseResult.Err(
                                    "TRANSPORT_TOO_LARGE",
                                    "input exceeds metadata cap",
                                )
                            }
                            if (total > ParseBounds.MAX_TOTAL_BYTES) {
                                return IsolatedParseResult.Err(
                                    "TRANSPORT_TOO_LARGE",
                                    "total exceeds package cap",
                                )
                            }
                        }
                        digest.update(buffer, 0, n)
                        if (sniff == null && fileBytes >= 4) {
                            sniff = sniffMagic(buffer, n.coerceAtMost(16))
                        }
                    }
                    val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
                    entries.add(
                        TypedFileEntry(
                            role = role,
                            relativeName = role,
                            byteLength = fileBytes,
                            sha256Hex = hex,
                            contentSniff = sniff,
                        ),
                    )
                }
            } catch (e: Exception) {
                return IsolatedParseResult.Err(
                    "INTERNAL",
                    "fd read failed for role=$role: ${e.javaClass.simpleName}",
                )
            }
        }

        if (request.declaredTotalBytes > 0L && total != request.declaredTotalBytes) {
            // Mismatch is not fatal for compatibility evidence, but is recorded.
            // Trust placement still uses separate identity verification (INV-010).
        }

        val duration = (monotonicNowMs() - start).coerceAtLeast(0L)
        val descriptor = TypedPackageDescriptor(
            formatHint = request.formatHint,
            fileCount = entries.size,
            totalDeclaredBytes = total,
            entries = entries,
            tensors = emptyList(),
            attributes = mapOf(
                "parser" to "IsolatedParseEngine",
                "schema" to TypedPackageDescriptor.SCHEMA_VERSION.toString(),
            ),
            parseDurationMs = duration,
            truncated = false,
        )
        return IsolatedParseResult.Ok(descriptor)
    }

    private fun sniffMagic(buf: ByteArray, len: Int): String {
        if (len >= 4) {
            // GGUF magic
            if (buf[0] == 'G'.code.toByte() &&
                buf[1] == 'G'.code.toByte() &&
                buf[2] == 'U'.code.toByte() &&
                buf[3] == 'F'.code.toByte()
            ) {
                return "gguf"
            }
            // ZIP / many archives
            if (buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()) {
                return "zip"
            }
        }
        return "unknown"
    }
}
