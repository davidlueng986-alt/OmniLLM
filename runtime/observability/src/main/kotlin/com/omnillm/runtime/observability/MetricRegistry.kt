package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import java.util.concurrent.atomic.AtomicLong

/**
 * Control-plane metric recording and snapshot surface (CORE-OBSERVABILITY).
 *
 * Unknown metric IDs fail closed. Percentiles only from histogram/sketch
 * with methodVersion. Every sample carries evidence label + sampledAt.
 */
interface MetricRegistry {
    fun record(
        id: MetricId,
        value: Double,
        evidenceLabel: EvidenceLabel,
        dimensions: Map<String, String> = emptyMap(),
        sampledAtEpochMs: Long? = null,
        source: String? = null,
    ): OmniResult<Unit>

    fun snapshot(
        privacyFilter: Set<MetricPrivacy>? = null,
        includeDetailRestricted: Boolean = false,
    ): MetricSnapshot

    fun histogramSnapshot(
        id: MetricId,
        dimensions: Map<String, String> = emptyMap(),
    ): HistogramSnapshot?

    fun counterValue(
        id: MetricId,
        dimensions: Map<String, String> = emptyMap(),
    ): Long

    fun gaugeSample(
        id: MetricId,
        dimensions: Map<String, String> = emptyMap(),
    ): MetricSample?
}

class InMemoryMetricRegistry(
    private val clockWallMs: () -> Long = { System.currentTimeMillis() },
) : MetricRegistry {

    private val lock = Any()
    private val version = AtomicLong(0L)

    private val histograms =
        linkedMapOf<SeriesKey, FixedBucketHistogram>()
    private val counters = linkedMapOf<SeriesKey, Long>()
    private val gauges = linkedMapOf<SeriesKey, MetricSample>()
    private val lasts = linkedMapOf<SeriesKey, MetricSample>()

    override fun record(
        id: MetricId,
        value: Double,
        evidenceLabel: EvidenceLabel,
        dimensions: Map<String, String>,
        sampledAtEpochMs: Long?,
        source: String?,
    ): OmniResult<Unit> = synchronized(lock) {
        val def = MetricCatalog.definition(id)
        if (!value.isFinite()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "metric value must be finite",
                    details = mapOf("metric" to id.wireName),
                ),
            )
        }
        try {
            DimensionPolicy.validate(dimensions)
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message,
                    details = mapOf("metric" to id.wireName),
                ),
            )
        }
        if (evidenceLabel == EvidenceLabel.REPORTED && source.isNullOrBlank()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "REPORTED metrics must disclose source",
                    details = mapOf("metric" to id.wireName),
                ),
            )
        }

        val at = sampledAtEpochMs ?: clockWallMs()
        val key = try {
            SeriesKey(id, normalizeDims(dimensions, def))
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message,
                    details = mapOf("metric" to id.wireName),
                ),
            )
        }
        when (def.aggregation) {
            MetricAggregation.HISTOGRAM -> {
                val hist = histograms.getOrPut(key) {
                    histogramFor(id)
                }
                hist.observe(value)
            }
            MetricAggregation.COUNTER -> {
                val delta = value.toLong()
                if (delta.toDouble() != value || delta < 0L) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = "counter observations must be non-negative integers",
                            details = mapOf("metric" to id.wireName),
                        ),
                    )
                }
                counters[key] = (counters[key] ?: 0L) + delta
            }
            MetricAggregation.GAUGE -> {
                gauges[key] = MetricSample(
                    name = id.wireName,
                    value = value,
                    unit = def.unit,
                    evidenceLabel = evidenceLabel,
                    sampledAtEpochMs = at,
                    dimensions = key.dimensions,
                    source = source,
                )
            }
            MetricAggregation.LAST -> {
                lasts[key] = MetricSample(
                    name = id.wireName,
                    value = value,
                    unit = def.unit,
                    evidenceLabel = evidenceLabel,
                    sampledAtEpochMs = at,
                    dimensions = key.dimensions,
                    source = source,
                )
            }
        }
        version.incrementAndGet()
        OmniResult.ok(Unit)
    }

    override fun snapshot(
        privacyFilter: Set<MetricPrivacy>?,
        includeDetailRestricted: Boolean,
    ): MetricSnapshot = synchronized(lock) {
        val samples = ArrayList<MetricSample>()
        val now = clockWallMs()

        fun allowed(def: MetricDefinition): Boolean {
            if (privacyFilter != null && def.privacy !in privacyFilter) return false
            if (!includeDetailRestricted && def.privacy == MetricPrivacy.RESTRICTED) {
                return false
            }
            // device-sensitive excluded from summary unless filter allows it
            if (!includeDetailRestricted && def.privacy == MetricPrivacy.DEVICE_SENSITIVE) {
                return false
            }
            return true
        }

        for ((key, hist) in histograms) {
            val def = MetricCatalog.definition(key.id)
            if (!allowed(def)) continue
            val snap = hist.snapshot()
            val avg = snap.averageOrNull()
            if (avg != null) {
                samples += MetricSample(
                    name = key.id.wireName,
                    value = avg,
                    unit = def.unit,
                    evidenceLabel = EvidenceLabel.LAST_SAMPLED,
                    sampledAtEpochMs = now,
                    dimensions = key.dimensions + mapOf("stat" to "avg"),
                    methodVersion = snap.methodVersion,
                )
            }
            // p50 / p95 only when histogram + methodVersion present
            for (p in listOf(50.0, 95.0)) {
                val pv = snap.percentileOrNull(p) ?: continue
                samples += MetricSample(
                    name = key.id.wireName,
                    value = pv,
                    unit = def.unit,
                    evidenceLabel = EvidenceLabel.LAST_SAMPLED,
                    sampledAtEpochMs = now,
                    dimensions = key.dimensions + mapOf(
                        "stat" to "p${p.toInt()}",
                        "methodVersion" to snap.methodVersion,
                    ),
                    methodVersion = snap.methodVersion,
                )
            }
        }

        for ((key, count) in counters) {
            val def = MetricCatalog.definition(key.id)
            if (!allowed(def)) continue
            samples += MetricSample(
                name = key.id.wireName,
                value = count.toDouble(),
                unit = def.unit,
                evidenceLabel = EvidenceLabel.MEASURED,
                sampledAtEpochMs = now,
                dimensions = key.dimensions,
            )
        }

        for ((key, sample) in gauges) {
            val def = MetricCatalog.definition(key.id)
            if (!allowed(def)) continue
            samples += sample
        }
        for ((key, sample) in lasts) {
            val def = MetricCatalog.definition(key.id)
            if (!allowed(def)) continue
            samples += sample
        }

        MetricSnapshot(
            snapshotVersion = version.get(),
            samples = samples,
        )
    }

    override fun histogramSnapshot(
        id: MetricId,
        dimensions: Map<String, String>,
    ): HistogramSnapshot? = synchronized(lock) {
        val def = MetricCatalog.definition(id)
        require(def.aggregation == MetricAggregation.HISTOGRAM) {
            "metric ${id.wireName} is not a histogram"
        }
        val key = SeriesKey(id, normalizeDims(dimensions, def))
        histograms[key]?.snapshot()
    }

    override fun counterValue(
        id: MetricId,
        dimensions: Map<String, String>,
    ): Long = synchronized(lock) {
        val def = MetricCatalog.definition(id)
        require(def.aggregation == MetricAggregation.COUNTER) {
            "metric ${id.wireName} is not a counter"
        }
        val key = SeriesKey(id, normalizeDims(dimensions, def))
        counters[key] ?: 0L
    }

    override fun gaugeSample(
        id: MetricId,
        dimensions: Map<String, String>,
    ): MetricSample? = synchronized(lock) {
        val def = MetricCatalog.definition(id)
        val key = SeriesKey(id, normalizeDims(dimensions, def))
        when (def.aggregation) {
            MetricAggregation.GAUGE -> gauges[key]
            MetricAggregation.LAST -> lasts[key]
            else -> null
        }
    }

    private fun histogramFor(id: MetricId): FixedBucketHistogram =
        when (id) {
            MetricId.REQUEST_TTFT_MS,
            MetricId.REQUEST_QUEUE_MS,
            -> FixedBucketHistogram(
                methodVersion = DefaultLatencyBucketsMs.METHOD_VERSION,
                bucketUpperBounds = DefaultLatencyBucketsMs.BOUNDS,
            )
            MetricId.REQUEST_TOKENS_PER_SECOND -> FixedBucketHistogram(
                methodVersion = DefaultThroughputBuckets.METHOD_VERSION,
                bucketUpperBounds = DefaultThroughputBuckets.BOUNDS,
            )
            else -> FixedBucketHistogram(
                methodVersion = "fixed-bucket-generic.v1",
                bucketUpperBounds = DefaultLatencyBucketsMs.BOUNDS,
            )
        }

    private fun normalizeDims(
        dimensions: Map<String, String>,
        def: MetricDefinition,
    ): Map<String, String> {
        // Keep only declared catalog dimensions (unknown dims fail closed).
        if (dimensions.keys.any { it !in def.dimensions && it != "stat" && it != "methodVersion" }) {
            val unknown = dimensions.keys.filter {
                it !in def.dimensions && it != "stat" && it != "methodVersion"
            }
            throw IllegalArgumentException(
                "Unknown metric dimension(s) for ${def.id.wireName}: $unknown",
            )
        }
        // Stable total order for map keys (INV-015 style).
        return dimensions.toSortedMap()
    }

    private data class SeriesKey(
        val id: MetricId,
        val dimensions: Map<String, String>,
    )
}

/**
 * Reject percentile claims from count+sum alone (catalog rule).
 * Use this helper at any aggregation boundary outside [FixedBucketHistogram].
 */
object PercentilePolicy {
    fun requireHistogramMethod(methodVersion: String?) {
        if (methodVersion.isNullOrBlank()) {
            throw IllegalStateException(
                "percentiles require histogram/sketch and methodVersion",
            )
        }
    }

    fun forbidCountSumPercentile(): Nothing {
        throw IllegalStateException(
            "count+sum cannot claim percentile; " +
                "percentiles require histogram/sketch and methodVersion",
        )
    }
}
