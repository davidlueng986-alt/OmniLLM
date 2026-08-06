package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel

/**
 * Canonical metric sample aligned with OpenAPI `MetricSample`
 * (`specs/openapi/omnillm.openapi.yaml`) and observability-catalog rules.
 *
 * Required: name, value, unit, evidence_label, sampled_at.
 * Dimensions must not include prompt / private path keys.
 */
data class MetricSample(
    val name: String,
    val value: Double,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val dimensions: Map<String, String> = emptyMap(),
    /** Required when aggregation is HISTOGRAM and percentile is derived. */
    val methodVersion: String? = null,
    val source: String? = null,
) {
    init {
        require(name.isNotBlank()) { "metric name must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        require(value.isFinite()) { "metric value must be finite" }
        DimensionPolicy.validate(dimensions)
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED MetricSample must disclose source"
            }
        }
    }

    fun toEvidencedValue(): EvidencedValue =
        EvidencedValue(
            value = value,
            evidenceLabel = evidenceLabel,
            sampledAtEpochMs = sampledAtEpochMs,
            source = source,
            unit = unit,
        )
}

/**
 * OpenAPI `MetricSnapshot` — versioned sample page for summary/detail APIs.
 */
data class MetricSnapshot(
    val snapshotVersion: Long,
    val samples: List<MetricSample>,
    val nextPageToken: String? = null,
) {
    init {
        require(snapshotVersion >= 0L) { "snapshotVersion must be non-negative" }
    }
}

/**
 * Rejects forbidden dimension keys (catalog rule + privacy).
 */
object DimensionPolicy {
    fun validate(dimensions: Map<String, String>) {
        for (key in dimensions.keys) {
            if (key in MetricCatalog.FORBIDDEN_DIMENSION_KEYS) {
                throw IllegalArgumentException(
                    "Forbidden metric dimension key (catalog rule): $key",
                )
            }
            // Soft scan: values that look like absolute private paths are rejected.
            val v = dimensions[key] ?: continue
            if (looksLikePrivatePath(v)) {
                throw IllegalArgumentException(
                    "Metric dimension value must not be a private filesystem path: $key",
                )
            }
        }
    }

    private fun looksLikePrivatePath(value: String): Boolean {
        if (value.length < 3) return false
        // Unix absolute under app-private-ish roots, or Windows drive path.
        if (value.startsWith("/data/") || value.startsWith("/storage/emulated/")) return true
        if (value.length >= 3 && value[1] == ':' && (value[2] == '\\' || value[2] == '/')) {
            return true
        }
        return false
    }
}

/**
 * Fixed-bucket histogram used for operational percentile derivation.
 * Catalog rule: percentiles require histogram/sketch and methodVersion;
 * count+sum alone cannot claim percentile.
 *
 * Buckets are upper-bound exclusive edges in ascending order; values
 * at/above the last edge fall into the overflow bucket.
 */
class FixedBucketHistogram(
    val methodVersion: String,
    bucketUpperBounds: DoubleArray,
) {
    init {
        require(methodVersion.isNotBlank()) { "methodVersion is required for histograms" }
        require(bucketUpperBounds.isNotEmpty()) { "bucketUpperBounds must be non-empty" }
        for (i in 1 until bucketUpperBounds.size) {
            require(bucketUpperBounds[i] > bucketUpperBounds[i - 1]) {
                "bucketUpperBounds must be strictly ascending"
            }
        }
    }

    private val bounds: DoubleArray = bucketUpperBounds.copyOf()
    private val counts: LongArray = LongArray(bounds.size + 1) // last = overflow
    private var count: Long = 0L
    private var sum: Double = 0.0
    private var min: Double = Double.POSITIVE_INFINITY
    private var max: Double = Double.NEGATIVE_INFINITY

    fun observe(value: Double) {
        require(value.isFinite()) { "histogram value must be finite" }
        var idx = bounds.size // overflow
        for (i in bounds.indices) {
            if (value < bounds[i]) {
                idx = i
                break
            }
        }
        counts[idx]++
        count++
        sum += value
        if (value < min) min = value
        if (value > max) max = value
    }

    fun count(): Long = count

    fun sum(): Double = sum

    /** Average only — not a percentile (catalog rule). */
    fun averageOrNull(): Double? =
        if (count == 0L) null else sum / count.toDouble()

    fun minOrNull(): Double? = if (count == 0L) null else min

    fun maxOrNull(): Double? = if (count == 0L) null else max

    /**
     * Approximate percentile via cumulative fixed buckets.
     * [p] in (0, 100]. Empty histogram returns null (UNKNOWN, not 0).
     */
    fun percentileOrNull(p: Double): Double? {
        require(p > 0.0 && p <= 100.0) { "percentile p must be in (0, 100]" }
        if (count == 0L) return null
        val target = kotlin.math.ceil(count * (p / 100.0)).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in counts.indices) {
            cumulative += counts[i]
            if (cumulative >= target) {
                return if (i < bounds.size) bounds[i] else max
            }
        }
        return max
    }

    fun snapshot(): HistogramSnapshot =
        HistogramSnapshot(
            methodVersion = methodVersion,
            bucketUpperBounds = bounds.copyOf(),
            counts = counts.copyOf(),
            count = count,
            sum = sum,
            min = if (count == 0L) null else min,
            max = if (count == 0L) null else max,
        )
}

data class HistogramSnapshot(
    val methodVersion: String,
    val bucketUpperBounds: DoubleArray,
    val counts: LongArray,
    val count: Long,
    val sum: Double,
    val min: Double?,
    val max: Double?,
) {
    fun averageOrNull(): Double? =
        if (count == 0L) null else sum / count.toDouble()

    /**
     * Fail closed: callers must not invent percentiles from count+sum alone.
     * This snapshot carries buckets + methodVersion so percentiles are legal.
     */
    fun percentileOrNull(p: Double): Double? {
        require(p > 0.0 && p <= 100.0) { "percentile p must be in (0, 100]" }
        if (count == 0L) return null
        val target = kotlin.math.ceil(count * (p / 100.0)).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in counts.indices) {
            cumulative += counts[i]
            if (cumulative >= target) {
                return if (i < bucketUpperBounds.size) bucketUpperBounds[i] else max
            }
        }
        return max
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramSnapshot) return false
        return methodVersion == other.methodVersion &&
            bucketUpperBounds.contentEquals(other.bucketUpperBounds) &&
            counts.contentEquals(other.counts) &&
            count == other.count &&
            sum == other.sum &&
            min == other.min &&
            max == other.max
    }

    override fun hashCode(): Int {
        var result = methodVersion.hashCode()
        result = 31 * result + bucketUpperBounds.contentHashCode()
        result = 31 * result + counts.contentHashCode()
        result = 31 * result + count.hashCode()
        result = 31 * result + sum.hashCode()
        result = 31 * result + (min?.hashCode() ?: 0)
        result = 31 * result + (max?.hashCode() ?: 0)
        return result
    }
}

/**
 * Count+sum only accumulator — **cannot** claim percentile
 * (observability-catalog.yaml rules).
 */
data class CountSumAccumulator(
    val count: Long = 0L,
    val sum: Double = 0.0,
) {
    init {
        require(count >= 0L) { "count must be non-negative" }
        require(sum.isFinite()) { "sum must be finite" }
    }

    fun observe(value: Double): CountSumAccumulator {
        require(value.isFinite()) { "value must be finite" }
        return CountSumAccumulator(count = count + 1L, sum = sum + value)
    }

    fun averageOrNull(): Double? =
        if (count == 0L) null else sum / count.toDouble()

    /**
     * Always fails — percentiles require histogram/sketch + methodVersion.
     */
    fun percentileForbidden(p: Double): Nothing {
        throw IllegalStateException(
            "count+sum cannot claim percentile (p=$p); " +
                "percentiles require histogram/sketch and methodVersion",
        )
    }
}

/** Default latency buckets (ms) for operational TTFT / queue histograms. */
object DefaultLatencyBucketsMs {
    val BOUNDS: DoubleArray = doubleArrayOf(
        10.0, 25.0, 50.0, 100.0, 250.0, 500.0,
        1_000.0, 2_500.0, 5_000.0, 10_000.0, 30_000.0, 60_000.0,
    )
    const val METHOD_VERSION: String = "fixed-bucket-latency-ms.v1"
}

/** Default throughput buckets (token/s). */
object DefaultThroughputBuckets {
    val BOUNDS: DoubleArray = doubleArrayOf(
        1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
    )
    const val METHOD_VERSION: String = "fixed-bucket-tps.v1"
}
