package com.omnillm.data.modelstore

/**
 * Quarantine / PFD materialize bounds (SEC-INPUT §5–§6, DATA-STORAGE §2, ANDROID-STORAGE).
 *
 * Versioned policy floors — control plane may tighten further via settings;
 * cannot be relaxed by workers.
 */
data class MaterializeBounds(
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val maxFileCount: Int = DEFAULT_MAX_FILE_COUNT,
    val maxReadTimeMs: Long = DEFAULT_MAX_READ_TIME_MS,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init {
        require(maxFileBytes > 0L)
        require(maxTotalBytes >= maxFileBytes)
        require(maxFileCount > 0)
        require(maxReadTimeMs > 0L)
        require(bufferSize in 4_096..1_048_576)
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 8L * 1024L * 1024L * 1024L // 8 GiB
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 16L * 1024L * 1024L * 1024L // 16 GiB
        const val DEFAULT_MAX_FILE_COUNT: Int = 256
        const val DEFAULT_MAX_READ_TIME_MS: Long = 30L * 60L * 1000L // 30 min
        const val DEFAULT_BUFFER_SIZE: Int = 256 * 1024

        val DEFAULT: MaterializeBounds = MaterializeBounds()
    }
}

/**
 * Typed ImportSpec for deferred SAF imports (ANDROID-STORAGE §2).
 *
 * Free payload JSON must not carry security-critical fields — this is the typed form.
 */
data class ImportSpec(
    val uriToken: String,
    val grantFlags: Int,
    val expectedByteLength: Long?,
    val expectedDigestHex: String?,
    val formatHint: String,
    val ownerKey: String,
    val expiryMonotonic: Long,
) {
    init {
        require(uriToken.isNotEmpty()) { "uriToken must be non-empty" }
        require(formatHint.isNotEmpty()) { "formatHint must be non-empty" }
        require(ownerKey.isNotEmpty()) { "ownerKey must be non-empty" }
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        expectedByteLength?.let { require(it >= 0L) }
        expectedDigestHex?.let { PathSafety.requireDigestHex(it, "expectedDigestHex") }
    }
}

/** Result of a single bounded materialize into quarantine. */
data class MaterializeOutcome(
    val role: String,
    val byteLength: Long,
    val sha256Hex: String,
    val materializeHandle: String,
    val seekable: Boolean,
    val readTimeMs: Long,
) {
    init {
        require(role.isNotEmpty())
        require(byteLength >= 0L)
        PathSafety.requireDigestHex(sha256Hex, "sha256Hex")
        require(materializeHandle.isNotEmpty())
        require(readTimeMs >= 0L)
    }
}

sealed class MaterializeError {
    data class InvalidRequest(val reason: String) : MaterializeError()
    data class TooLarge(val reason: String) : MaterializeError()
    data class DeadlineExceeded(val reason: String) : MaterializeError()
    data class IoFailure(val reason: String) : MaterializeError()
    data class NotRegularFile(val reason: String) : MaterializeError()
    data class SizeMismatch(val reason: String) : MaterializeError()
    data class DigestMismatch(val reason: String) : MaterializeError()

    /** Map to catalog error code string (fail closed). */
    fun toCatalogCode(): String = when (this) {
        is InvalidRequest -> "INVALID_REQUEST"
        is TooLarge -> "TRANSPORT_TOO_LARGE"
        is DeadlineExceeded -> "DEADLINE_EXCEEDED"
        is IoFailure -> "INTERNAL"
        is NotRegularFile -> "INVALID_REQUEST"
        is SizeMismatch -> "INVALID_REQUEST"
        is DigestMismatch -> "INVALID_REQUEST"
    }
}
