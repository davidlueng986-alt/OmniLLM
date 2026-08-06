package com.omnillm.data.modelstore

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded stream copy with SHA-256 (SEC-INPUT §5, ANDROID-STORAGE §3).
 *
 * Used by PFD materialize after dup/fstat, and by download job pipelines.
 * Does not open paths — caller supplies streams and destination handle.
 */
object StreamMaterializer {

    data class CopyRequest(
        val role: String,
        val materializeHandle: String,
        val expectedByteLength: Long? = null,
        val expectedDigestHex: String? = null,
        val bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        val seekable: Boolean = false,
    ) {
        init {
            PathSafety.requireRole(role)
            require(materializeHandle.isNotEmpty())
            expectedByteLength?.let { require(it >= 0L) }
            expectedDigestHex?.let { PathSafety.requireDigestHex(it, "expectedDigestHex") }
        }
    }

    /**
     * Copy [input] → [output] under [request] bounds.
     * Caller owns close of streams.
     */
    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        request: CopyRequest,
        monotonicNowMs: () -> Long,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): Result<MaterializeOutcome> {
        val start = monotonicNowMs()
        val deadline = start + request.bounds.maxReadTimeMs
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(request.bounds.bufferSize)
        var total = 0L

        try {
            while (true) {
                if (cancel.get()) {
                    return Result.failure(
                        MaterializeException(MaterializeError.InvalidRequest("cancelled")),
                    )
                }
                if (monotonicNowMs() > deadline) {
                    return Result.failure(
                        MaterializeException(
                            MaterializeError.DeadlineExceeded("read exceeded maxReadTimeMs"),
                        ),
                    )
                }
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > request.bounds.maxFileBytes) {
                    return Result.failure(
                        MaterializeException(
                            MaterializeError.TooLarge("exceeds maxFileBytes"),
                        ),
                    )
                }
                request.expectedByteLength?.let { expected ->
                    if (total > expected) {
                        return Result.failure(
                            MaterializeException(
                                MaterializeError.SizeMismatch(
                                    "stream longer than expectedByteLength=$expected",
                                ),
                            ),
                        )
                    }
                }
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
            }
        } catch (e: MaterializeException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(
                MaterializeException(
                    MaterializeError.IoFailure(e.javaClass.simpleName),
                ),
            )
        }

        request.expectedByteLength?.let { expected ->
            if (total != expected) {
                return Result.failure(
                    MaterializeException(
                        MaterializeError.SizeMismatch(
                            "expected=$expected actual=$total",
                        ),
                    ),
                )
            }
        }

        val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
        request.expectedDigestHex?.let { expected ->
            if (hex != expected.lowercase()) {
                return Result.failure(
                    MaterializeException(
                        MaterializeError.DigestMismatch("digest mismatch for role=${request.role}"),
                    ),
                )
            }
        }

        val elapsed = (monotonicNowMs() - start).coerceAtLeast(0L)
        return Result.success(
            MaterializeOutcome(
                role = request.role,
                byteLength = total,
                sha256Hex = hex,
                materializeHandle = request.materializeHandle,
                seekable = request.seekable,
                readTimeMs = elapsed,
            ),
        )
    }
}

class MaterializeException(val error: MaterializeError) : Exception(error.toString())
