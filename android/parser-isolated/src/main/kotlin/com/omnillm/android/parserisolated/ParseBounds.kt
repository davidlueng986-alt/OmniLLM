package com.omnillm.android.parserisolated

/**
 * Hard caps for isolated parser I/O (SEC-INPUT §1/§6, ARCH-TRUST-TOPOLOGY §5).
 *
 * These are platform policy floors for fail-closed parsing — not trust elevation.
 * Free-form JSON node budgets still apply when parsing manifests.
 */
object ParseBounds {
    /** Max bytes readable from a single input FD. */
    const val MAX_INPUT_BYTES: Long = 64L * 1024L * 1024L // 64 MiB header/metadata

    /** Max wall time for one parse job (ms). */
    const val MAX_PARSE_TIME_MS: Long = 30_000L

    /** Max nested directory depth when scanning multi-file packages. */
    const val MAX_NESTED_DEPTH: Int = 8

    /** Max file count declared in one parse request. */
    const val MAX_FILE_COUNT: Int = 256

    /** Max individual file size considered for metadata parse. */
    const val MAX_FILE_BYTES: Long = 512L * 1024L * 1024L // 512 MiB

    /** Max total size across all files in one parse request. */
    const val MAX_TOTAL_BYTES: Long = 2L * 1024L * 1024L * 1024L // 2 GiB

    /** Max path component length. */
    const val MAX_PATH_COMPONENT: Int = 255

    /** Max string field length in typed descriptor. */
    const val MAX_STRING_FIELD: Int = 4_096

    /** Max number of tensor metadata entries in descriptor. */
    const val MAX_TENSOR_ENTRIES: Int = 4_096

    /** Max shape rank. */
    const val MAX_SHAPE_RANK: Int = 16

    /** Max nesting depth for structured metadata nodes. */
    const val MAX_JSON_DEPTH: Int = 32

    /** Max JSON node count (unknown fields still count). */
    const val MAX_JSON_NODES: Int = 50_000

    /** Max descriptor attribute map entries. */
    const val MAX_DESCRIPTOR_ATTRS: Int = 64

    fun validateRequestShape(
        fileCount: Int,
        declaredTotalBytes: Long,
        deadlineMs: Long,
    ): ParseBoundViolation? {
        if (fileCount <= 0) return ParseBoundViolation("fileCount must be positive")
        if (fileCount > MAX_FILE_COUNT) return ParseBoundViolation("fileCount exceeds cap")
        if (declaredTotalBytes < 0L) return ParseBoundViolation("declaredTotalBytes negative")
        if (declaredTotalBytes > MAX_TOTAL_BYTES) {
            return ParseBoundViolation("declaredTotalBytes exceeds cap")
        }
        if (deadlineMs <= 0L || deadlineMs > MAX_PARSE_TIME_MS) {
            return ParseBoundViolation("deadlineMs out of policy range")
        }
        return null
    }
}

data class ParseBoundViolation(val reason: String)
