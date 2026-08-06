package com.omnillm.runtime.policy.download

import com.omnillm.core.errors.generated.OmniError

/**
 * Download transfer and Range policy floors (SEC-INPUT §3–§4, SEC-SUPPLY §5).
 */
data class DownloadTransferLimits(
    val maxBytesPerConnection: Long = DEFAULT_MAX_BYTES,
    val maxTimeMs: Long = DEFAULT_MAX_TIME_MS,
    val maxBytesPerSecond: Long = DEFAULT_MAX_BPS,
    val maxRedirectHops: Int = 5,
    val requireEtagOrLastModifiedForResume: Boolean = true,
) {
    init {
        require(maxBytesPerConnection > 0L)
        require(maxTimeMs > 0L)
        require(maxBytesPerSecond > 0L)
        require(maxRedirectHops in 0..20)
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024L * 1024L * 1024L // 16 GiB
        const val DEFAULT_MAX_TIME_MS: Long = 6L * 60L * 60L * 1000L // 6 h
        const val DEFAULT_MAX_BPS: Long = 100L * 1024L * 1024L // 100 MiB/s soft rate cap

        val DEFAULT: DownloadTransferLimits = DownloadTransferLimits()
    }
}

/**
 * Half-open byte range `[start, end)` (SEC-INPUT §4).
 *
 * - [end] must not exceed content length when known
 * - Ranges on the same attempt/path must not overlap
 * - Verified bytes must not exceed the admitted range
 * - `If-Range` → 200 requires full restart or policy replace; never splice incompatible partials
 */
data class ByteRange(
    val start: Long,
    val endExclusive: Long,
) {
    init {
        require(start >= 0L) { "start must be non-negative" }
        require(endExclusive > start) { "end must be > start ([start,end))" }
    }

    val length: Long get() = endExclusive - start

    fun overlaps(other: ByteRange): Boolean =
        start < other.endExclusive && other.start < endExclusive
}

object RangeDownloadPolicy {

    sealed class Outcome {
        data class Accepted(val range: ByteRange) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    fun admitRange(
        start: Long,
        endExclusive: Long,
        contentLength: Long?,
        existingRanges: List<ByteRange> = emptyList(),
        verifiedBytes: Long = 0L,
    ): Outcome {
        if (start < 0L || endExclusive <= start) {
            return reject("invalid range bounds")
        }
        if (contentLength != null) {
            if (contentLength < 0L) return reject("contentLength negative")
            if (endExclusive > contentLength) {
                return reject("range end exceeds content length")
            }
        }
        val range = ByteRange(start, endExclusive)
        for (existing in existingRanges) {
            if (existing.overlaps(range)) {
                return reject("overlapping range on same attempt/path")
            }
        }
        if (verifiedBytes < 0L) return reject("verifiedBytes negative")
        if (verifiedBytes > range.length) {
            return reject("verified bytes exceed range length")
        }
        return Outcome.Accepted(range)
    }

    /**
     * When If-Range validator fails and server returns 200, do not splice.
     */
    fun onIfRangeFullResponse(
        preferRestart: Boolean = true,
    ): FullReplaceAction =
        if (preferRestart) FullReplaceAction.RESTART else FullReplaceAction.REPLACE_ALL

    enum class FullReplaceAction {
        RESTART,
        REPLACE_ALL,
    }

    private fun reject(reason: String): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.INVALID_REQUEST(
                message = "range download rejected",
                details = mapOf("reason" to reason),
            ),
            reason = reason,
        )
}
