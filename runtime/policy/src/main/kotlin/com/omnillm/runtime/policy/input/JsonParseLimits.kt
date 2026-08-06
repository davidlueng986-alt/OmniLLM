package com.omnillm.runtime.policy.input

/**
 * HTTP/JSON resource-bomb floors (SEC-INPUT §1, SEC-004).
 *
 * Control plane / transports may tighten further; workers must not relax.
 * Unknown fields still count toward size and node budgets.
 * Duplicate keys: [DuplicateKeyPolicy.REJECT] is the default fail-closed policy
 * (security-profile signedJson forbids duplicates; wire JSON follows same).
 */
data class JsonParseLimits(
    /** Max compressed request body bytes (Content-Encoding applied). */
    val maxCompressedBytes: Long = DEFAULT_MAX_COMPRESSED_BYTES,
    /** Max decompressed / plain UTF-8 body bytes. */
    val maxDecompressedBytes: Long = DEFAULT_MAX_DECOMPRESSED_BYTES,
    /** Soft wall-clock budget for parse + tree walk (ms). */
    val maxParseTimeMs: Long = DEFAULT_MAX_PARSE_TIME_MS,
    /** Max nesting depth (root = 0). */
    val maxNestingDepth: Int = DEFAULT_MAX_NESTING_DEPTH,
    /** Max JSON nodes including primitives and containers. */
    val maxNodeCount: Int = DEFAULT_MAX_NODE_COUNT,
    /** Max array length at any single level. */
    val maxArrayLength: Int = DEFAULT_MAX_ARRAY_LENGTH,
    /** Max object key count at any single level. */
    val maxObjectKeys: Int = DEFAULT_MAX_OBJECT_KEYS,
    /** Max UTF-8 bytes of a single string value. */
    val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    /** Max UTF-8 bytes of a single object key. */
    val maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
    val duplicateKeyPolicy: DuplicateKeyPolicy = DuplicateKeyPolicy.REJECT,
) {
    init {
        require(maxCompressedBytes > 0L) { "maxCompressedBytes must be positive" }
        require(maxDecompressedBytes > 0L) { "maxDecompressedBytes must be positive" }
        require(maxDecompressedBytes >= maxCompressedBytes) {
            "maxDecompressedBytes must be >= maxCompressedBytes"
        }
        require(maxParseTimeMs > 0L) { "maxParseTimeMs must be positive" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        require(maxNodeCount > 0) { "maxNodeCount must be positive" }
        require(maxArrayLength > 0) { "maxArrayLength must be positive" }
        require(maxObjectKeys > 0) { "maxObjectKeys must be positive" }
        require(maxStringBytes > 0) { "maxStringBytes must be positive" }
        require(maxKeyBytes > 0) { "maxKeyBytes must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_COMPRESSED_BYTES: Long = 1L * 1024L * 1024L // 1 MiB
        const val DEFAULT_MAX_DECOMPRESSED_BYTES: Long = 4L * 1024L * 1024L // 4 MiB
        const val DEFAULT_MAX_PARSE_TIME_MS: Long = 2_000L
        const val DEFAULT_MAX_NESTING_DEPTH: Int = 32
        const val DEFAULT_MAX_NODE_COUNT: Int = 50_000
        const val DEFAULT_MAX_ARRAY_LENGTH: Int = 10_000
        const val DEFAULT_MAX_OBJECT_KEYS: Int = 4_096
        const val DEFAULT_MAX_STRING_BYTES: Int = 256 * 1024
        const val DEFAULT_MAX_KEY_BYTES: Int = 256

        val DEFAULT: JsonParseLimits = JsonParseLimits()

        /** Tight profile for untrusted LAN or fuzz surfaces. */
        val TIGHT: JsonParseLimits = JsonParseLimits(
            maxCompressedBytes = 256L * 1024L,
            maxDecompressedBytes = 512L * 1024L,
            maxParseTimeMs = 500L,
            maxNestingDepth = 16,
            maxNodeCount = 5_000,
            maxArrayLength = 1_024,
            maxObjectKeys = 512,
            maxStringBytes = 64 * 1024,
            maxKeyBytes = 128,
        )
    }
}

enum class DuplicateKeyPolicy {
    /** Fail closed — matches security-profile signedJson and wire defaults. */
    REJECT,

    /** Last-wins only when a transport documents it; still counts nodes once. */
    LAST_WINS,
}

/**
 * Request / response header budgets (SEC-INPUT §2).
 *
 * Prevents header overflow and diagnostic bloat from legitimate ignore lists.
 */
data class HeaderLimits(
    val maxTotalHeaderBytes: Int = DEFAULT_MAX_TOTAL_HEADER_BYTES,
    val maxSingleHeaderBytes: Int = DEFAULT_MAX_SINGLE_HEADER_BYTES,
    val maxHeaderCount: Int = DEFAULT_MAX_HEADER_COUNT,
    /** Max ignored-param names returned in diagnostics before count+digest. */
    val maxIgnoredParamsListed: Int = DEFAULT_MAX_IGNORED_LISTED,
    val maxIgnoredParamsListBytes: Int = DEFAULT_MAX_IGNORED_LIST_BYTES,
) {
    init {
        require(maxTotalHeaderBytes > 0)
        require(maxSingleHeaderBytes > 0)
        require(maxSingleHeaderBytes <= maxTotalHeaderBytes)
        require(maxHeaderCount > 0)
        require(maxIgnoredParamsListed > 0)
        require(maxIgnoredParamsListBytes > 0)
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_HEADER_BYTES: Int = 32 * 1024
        const val DEFAULT_MAX_SINGLE_HEADER_BYTES: Int = 8 * 1024
        const val DEFAULT_MAX_HEADER_COUNT: Int = 64
        const val DEFAULT_MAX_IGNORED_LISTED: Int = 16
        const val DEFAULT_MAX_IGNORED_LIST_BYTES: Int = 2_048

        val DEFAULT: HeaderLimits = HeaderLimits()
    }
}

/**
 * Output abuse floors (SEC-INPUT §7).
 *
 * Client backpressure / missing ACK must stop generation, cancel, or detach —
 * never unbounded buffer.
 */
data class OutputAbuseLimits(
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val maxEventsPerSecond: Int = DEFAULT_MAX_EVENTS_PER_SECOND,
    val maxBufferedEventBytes: Int = DEFAULT_MAX_BUFFERED_EVENT_BYTES,
    val maxStructuredOutputDepth: Int = DEFAULT_MAX_STRUCTURED_DEPTH,
    val maxToolCallCount: Int = DEFAULT_MAX_TOOL_CALL_COUNT,
    val backpressurePolicy: BackpressurePolicy = BackpressurePolicy.STOP_GENERATION,
) {
    init {
        require(maxOutputTokens > 0)
        require(maxEventsPerSecond > 0)
        require(maxBufferedEventBytes > 0)
        require(maxStructuredOutputDepth > 0)
        require(maxToolCallCount > 0)
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 32_768
        const val DEFAULT_MAX_EVENTS_PER_SECOND: Int = 200
        const val DEFAULT_MAX_BUFFERED_EVENT_BYTES: Int = 1 * 1024 * 1024
        const val DEFAULT_MAX_STRUCTURED_DEPTH: Int = 32
        const val DEFAULT_MAX_TOOL_CALL_COUNT: Int = 128

        val DEFAULT: OutputAbuseLimits = OutputAbuseLimits()
    }
}

/**
 * What happens when the client does not ACK or network applies backpressure.
 */
enum class BackpressurePolicy {
    STOP_GENERATION,
    CANCEL_REQUEST,
    DETACH_STREAM,
}
