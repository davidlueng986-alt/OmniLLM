package com.omnillm.runtime.policy.input

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.errors.generated.OmniError

/**
 * Post-parse JSON tree budget admission (SEC-INPUT §1).
 *
 * Walks Map / List / primitive trees produced by a streaming parser.
 * Unknown fields count toward node and size budgets.
 * Pure function — no I/O.
 */
object JsonTreeBudget {

    sealed class Outcome {
        data class Accepted(
            val nodeCount: Int,
            val maxDepthSeen: Int,
            val approxUtf8Bytes: Int,
        ) : Outcome()

        data class Rejected(
            val error: OmniError,
            val reason: String,
            val details: Map<String, String> = emptyMap(),
        ) : Outcome()
    }

    /**
     * Admit a decompressed body size before parse (cheap gate).
     */
    fun admitRawSize(
        compressedBytes: Long?,
        decompressedBytes: Long,
        limits: JsonParseLimits = JsonParseLimits.DEFAULT,
    ): Outcome {
        if (decompressedBytes < 0L) {
            return rejectInvalid("decompressedBytes negative")
        }
        if (decompressedBytes > limits.maxDecompressedBytes) {
            return rejectTooLarge(
                "decompressed body exceeds cap",
                mapOf(
                    "decompressedBytes" to decompressedBytes.toString(),
                    "maxDecompressedBytes" to limits.maxDecompressedBytes.toString(),
                ),
            )
        }
        if (compressedBytes != null) {
            if (compressedBytes < 0L) return rejectInvalid("compressedBytes negative")
            if (compressedBytes > limits.maxCompressedBytes) {
                return rejectTooLarge(
                    "compressed body exceeds cap",
                    mapOf(
                        "compressedBytes" to compressedBytes.toString(),
                        "maxCompressedBytes" to limits.maxCompressedBytes.toString(),
                    ),
                )
            }
        }
        return Outcome.Accepted(nodeCount = 0, maxDepthSeen = 0, approxUtf8Bytes = decompressedBytes.toInt())
    }

    /**
     * Walk a parsed tree under [limits]. Root depth is 0.
     */
    fun admitTree(
        value: Any?,
        limits: JsonParseLimits = JsonParseLimits.DEFAULT,
        startedNanos: Long = System.nanoTime(),
    ): Outcome {
        val state = WalkState(limits = limits, startedNanos = startedNanos)
        val err = walk(value, depth = 0, state = state)
        if (err != null) return err
        return Outcome.Accepted(
            nodeCount = state.nodeCount,
            maxDepthSeen = state.maxDepthSeen,
            approxUtf8Bytes = state.approxUtf8Bytes,
        )
    }

    /**
     * Project ignored parameter names for diagnostics (SEC-INPUT §2).
     * Returns only the first N names within byte cap; overflow becomes count+digest.
     */
    fun projectIgnoredParams(
        ignoredParamNames: List<String>,
        headerLimits: HeaderLimits = HeaderLimits.DEFAULT,
    ): IgnoredParamsProjection {
        val listed = mutableListOf<String>()
        var listedBytes = 0
        for (name in ignoredParamNames) {
            if (listed.size >= headerLimits.maxIgnoredParamsListed) break
            val b = name.toByteArray(Charsets.UTF_8).size
            if (listedBytes + b > headerLimits.maxIgnoredParamsListBytes) break
            listed += name
            listedBytes += b
        }
        val omitted = ignoredParamNames.size - listed.size
        val digest = if (omitted > 0 || ignoredParamNames.isNotEmpty()) {
            IdentityHashing.sha256Hex(
                ignoredParamNames.sorted().joinToString("\n"),
            )
        } else {
            null
        }
        return IgnoredParamsProjection(
            listedNames = listed,
            omittedCount = omitted.coerceAtLeast(0),
            totalCount = ignoredParamNames.size,
            totalDigestHex = digest,
        )
    }

    /**
     * Admit request headers under total / single / count caps (SEC-INPUT §2).
     */
    fun admitHeaders(
        headers: List<Pair<String, String>>,
        limits: HeaderLimits = HeaderLimits.DEFAULT,
    ): Outcome {
        if (headers.size > limits.maxHeaderCount) {
            return rejectTooLarge(
                "header count exceeds cap",
                mapOf(
                    "headerCount" to headers.size.toString(),
                    "maxHeaderCount" to limits.maxHeaderCount.toString(),
                ),
            )
        }
        var total = 0
        for ((name, value) in headers) {
            val n = name.toByteArray(Charsets.UTF_8).size
            val v = value.toByteArray(Charsets.UTF_8).size
            val single = n + v + 4 // ": " + CRLF approximation
            if (single > limits.maxSingleHeaderBytes) {
                return rejectTooLarge(
                    "single header exceeds cap",
                    mapOf(
                        "header" to name.take(64),
                        "bytes" to single.toString(),
                        "maxSingleHeaderBytes" to limits.maxSingleHeaderBytes.toString(),
                    ),
                )
            }
            total += single
            if (total > limits.maxTotalHeaderBytes) {
                return rejectTooLarge(
                    "total header bytes exceed cap",
                    mapOf(
                        "totalBytes" to total.toString(),
                        "maxTotalHeaderBytes" to limits.maxTotalHeaderBytes.toString(),
                    ),
                )
            }
        }
        return Outcome.Accepted(nodeCount = headers.size, maxDepthSeen = 0, approxUtf8Bytes = total)
    }

    data class IgnoredParamsProjection(
        val listedNames: List<String>,
        val omittedCount: Int,
        val totalCount: Int,
        val totalDigestHex: String?,
    )

    private class WalkState(
        val limits: JsonParseLimits,
        val startedNanos: Long,
        var nodeCount: Int = 0,
        var maxDepthSeen: Int = 0,
        var approxUtf8Bytes: Int = 0,
    )

    private fun walk(value: Any?, depth: Int, state: WalkState): Outcome.Rejected? {
        val elapsedMs = (System.nanoTime() - state.startedNanos) / 1_000_000L
        if (elapsedMs > state.limits.maxParseTimeMs) {
            return Outcome.Rejected(
                error = OmniError.DEADLINE_EXCEEDED(
                    message = "JSON parse time cap exceeded",
                    details = mapOf("maxParseTimeMs" to state.limits.maxParseTimeMs.toString()),
                ),
                reason = "parse time cap exceeded",
                details = mapOf("maxParseTimeMs" to state.limits.maxParseTimeMs.toString()),
            )
        }
        if (depth > state.limits.maxNestingDepth) {
            return rejectTooLarge(
                "JSON nesting depth exceeds cap",
                mapOf(
                    "depth" to depth.toString(),
                    "maxNestingDepth" to state.limits.maxNestingDepth.toString(),
                ),
            ).asRejected()
        }
        state.maxDepthSeen = maxOf(state.maxDepthSeen, depth)
        state.nodeCount += 1
        if (state.nodeCount > state.limits.maxNodeCount) {
            return rejectTooLarge(
                "JSON node count exceeds cap",
                mapOf(
                    "nodeCount" to state.nodeCount.toString(),
                    "maxNodeCount" to state.limits.maxNodeCount.toString(),
                ),
            ).asRejected()
        }

        when (value) {
            null, is Boolean, is Number -> {
                state.approxUtf8Bytes += 8
            }
            is String -> {
                val b = value.toByteArray(Charsets.UTF_8).size
                state.approxUtf8Bytes += b
                if (b > state.limits.maxStringBytes) {
                    return rejectTooLarge(
                        "JSON string exceeds cap",
                        mapOf(
                            "stringBytes" to b.toString(),
                            "maxStringBytes" to state.limits.maxStringBytes.toString(),
                        ),
                    ).asRejected()
                }
            }
            is Map<*, *> -> {
                if (value.size > state.limits.maxObjectKeys) {
                    return rejectTooLarge(
                        "JSON object key count exceeds cap",
                        mapOf(
                            "keys" to value.size.toString(),
                            "maxObjectKeys" to state.limits.maxObjectKeys.toString(),
                        ),
                    ).asRejected()
                }
                // Detect duplicate keys only when the map surface can expose them
                // (LinkedHashMap after a parser that preserved collisions via lists).
                val seenKeys = HashSet<String>(value.size)
                for ((rawKey, child) in value) {
                    val key = rawKey?.toString().orEmpty()
                    val keyBytes = key.toByteArray(Charsets.UTF_8).size
                    state.approxUtf8Bytes += keyBytes
                    if (keyBytes > state.limits.maxKeyBytes) {
                        return rejectTooLarge(
                            "JSON object key exceeds cap",
                            mapOf(
                                "keyBytes" to keyBytes.toString(),
                                "maxKeyBytes" to state.limits.maxKeyBytes.toString(),
                            ),
                        ).asRejected()
                    }
                    if (state.limits.duplicateKeyPolicy == DuplicateKeyPolicy.REJECT) {
                        if (!seenKeys.add(key)) {
                            return Outcome.Rejected(
                                error = OmniError.INVALID_REQUEST(
                                    message = "duplicate JSON object key",
                                    details = mapOf("key" to key.take(64)),
                                ),
                                reason = "duplicate key",
                                details = mapOf("key" to key.take(64)),
                            )
                        }
                    }
                    val err = walk(child, depth + 1, state)
                    if (err != null) return err
                }
            }
            is List<*> -> {
                if (value.size > state.limits.maxArrayLength) {
                    return rejectTooLarge(
                        "JSON array length exceeds cap",
                        mapOf(
                            "length" to value.size.toString(),
                            "maxArrayLength" to state.limits.maxArrayLength.toString(),
                        ),
                    ).asRejected()
                }
                for (child in value) {
                    val err = walk(child, depth + 1, state)
                    if (err != null) return err
                }
            }
            is Array<*> -> {
                if (value.size > state.limits.maxArrayLength) {
                    return rejectTooLarge(
                        "JSON array length exceeds cap",
                        mapOf(
                            "length" to value.size.toString(),
                            "maxArrayLength" to state.limits.maxArrayLength.toString(),
                        ),
                    ).asRejected()
                }
                for (child in value) {
                    val err = walk(child, depth + 1, state)
                    if (err != null) return err
                }
            }
            else -> {
                // Fail closed on unexpected node types from hostile parsers.
                return Outcome.Rejected(
                    error = OmniError.INVALID_REQUEST(
                        message = "unsupported JSON node type",
                        details = mapOf("type" to (value::class.simpleName ?: "unknown")),
                    ),
                    reason = "unsupported node type",
                )
            }
        }
        return null
    }

    private fun rejectTooLarge(reason: String, details: Map<String, String>): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.TRANSPORT_TOO_LARGE(message = reason, details = details),
            reason = reason,
            details = details,
        )

    private fun rejectInvalid(reason: String): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.INVALID_REQUEST(message = reason),
            reason = reason,
        )

    private fun Outcome.Rejected.asRejected(): Outcome.Rejected = this
}
