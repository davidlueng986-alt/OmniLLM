package com.omnillm.runtime.observability

/**
 * Machine projection of `specs/observability-catalog.yaml` (CORE-OBSERVABILITY).
 *
 * Metric IDs, units, aggregation, dimensions, privacy class, and freshness
 * are authoritative here — do not invent metric IDs outside this catalog.
 */
object MetricCatalog {

    val RULES: List<String> = listOf(
        "count+sum cannot claim percentile",
        "percentiles require histogram/sketch and methodVersion",
        "all UI values expose evidence label and sampledAt",
        "raw prompt and model private path are not metrics dimensions",
    )

    /**
     * Dimension keys that are forbidden on every metric (catalog rule +
     * SEC-PRIVACY). Matching is case-sensitive on canonical snake/camel forms
     * used by the control plane; redaction helpers also scan free text.
     */
    val FORBIDDEN_DIMENSION_KEYS: Set<String> = setOf(
        "prompt",
        "rawPrompt",
        "raw_prompt",
        "output",
        "rawOutput",
        "raw_output",
        "token",
        "tokenSecret",
        "bearerToken",
        "modelPrivatePath",
        "model_private_path",
        "privatePath",
        "private_path",
        "filePath",
        "absolutePath",
    )

    val METRICS: Map<String, MetricDefinition> = listOf(
        MetricDefinition(
            id = MetricId.REQUEST_TTFT_MS,
            unit = "ms",
            aggregation = MetricAggregation.HISTOGRAM,
            dimensions = listOf("engineBuildId", "modelRevisionId", "backend", "deviceClass"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "per request",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.REQUEST_TOKENS_PER_SECOND,
            unit = "token/s",
            aggregation = MetricAggregation.HISTOGRAM,
            dimensions = listOf("measurementProfileId"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "per request",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.REQUEST_QUEUE_MS,
            unit = "ms",
            aggregation = MetricAggregation.HISTOGRAM,
            dimensions = listOf("principalClass", "operationKind"),
            privacy = MetricPrivacy.RESTRICTED,
            freshness = "per request",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.REQUEST_ERROR_COUNT,
            unit = "count",
            aggregation = MetricAggregation.COUNTER,
            dimensions = listOf("errorCode", "phase"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "event",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.RESOURCE_RESERVED_BYTES,
            unit = "bytes",
            aggregation = MetricAggregation.GAUGE,
            dimensions = listOf("resourceDimension", "ownerKind"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "2s or transition",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.RESOURCE_ALLOCATED_BYTES,
            unit = "bytes",
            aggregation = MetricAggregation.GAUGE,
            dimensions = listOf("resourceDimension", "ownerKind"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "2s or transition",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.RESOURCE_MEMORY_SAMPLE_AGE_MS,
            unit = "ms",
            aggregation = MetricAggregation.GAUGE,
            dimensions = listOf("processInstance"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "2s",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.THERMAL_STATUS,
            unit = "enum",
            aggregation = MetricAggregation.LAST,
            dimensions = listOf("deviceFingerprint"),
            privacy = MetricPrivacy.DEVICE_SENSITIVE,
            freshness = "platform callback",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.ENGINE_HEALTH,
            unit = "enum",
            aggregation = MetricAggregation.LAST,
            dimensions = listOf("engineBuildId", "backend"),
            privacy = MetricPrivacy.OPERATIONAL,
            freshness = "transition",
            metricClass = MetricClass.OPERATIONAL,
        ),
        MetricDefinition(
            id = MetricId.JOB_PROGRESS,
            unit = "ratio",
            aggregation = MetricAggregation.LAST,
            dimensions = listOf("jobKind"),
            privacy = MetricPrivacy.RESTRICTED,
            freshness = "event",
            metricClass = MetricClass.OPERATIONAL,
        ),
    ).associateBy { it.id.wireName }

    fun definition(id: MetricId): MetricDefinition =
        METRICS[id.wireName]
            ?: error("MetricId missing from catalog map: ${id.wireName}")

    fun definitionOrNull(wireName: String): MetricDefinition? = METRICS[wireName]

    fun requireKnown(wireName: String): MetricDefinition =
        definitionOrNull(wireName)
            ?: throw IllegalArgumentException("Unknown metric id (fail closed): $wireName")
}

/**
 * Catalog metric identifiers (`observability-catalog.yaml` metrics[].id).
 * Wire names use the dotted form from the catalog.
 */
enum class MetricId(val wireName: String) {
    REQUEST_TTFT_MS("request.ttft_ms"),
    REQUEST_TOKENS_PER_SECOND("request.tokens_per_second"),
    REQUEST_QUEUE_MS("request.queue_ms"),
    REQUEST_ERROR_COUNT("request.error_count"),
    RESOURCE_RESERVED_BYTES("resource.reserved_bytes"),
    RESOURCE_ALLOCATED_BYTES("resource.allocated_bytes"),
    RESOURCE_MEMORY_SAMPLE_AGE_MS("resource.memory_sample_age_ms"),
    THERMAL_STATUS("thermal.status"),
    ENGINE_HEALTH("engine.health"),
    JOB_PROGRESS("job.progress"),
    ;

    companion object {
        private val BY_WIRE: Map<String, MetricId> = entries.associateBy { it.wireName }

        fun fromWireName(name: String): MetricId? = BY_WIRE[name]

        fun requireFromWireName(name: String): MetricId =
            fromWireName(name)
                ?: throw IllegalArgumentException("Unknown MetricId: $name")
    }
}

/** Aggregation kinds from observability-catalog.yaml metrics[].aggregation. */
enum class MetricAggregation {
    HISTOGRAM,
    COUNTER,
    GAUGE,
    LAST,
    ;

    companion object {
        fun fromCatalogName(name: String): MetricAggregation? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Privacy class from observability-catalog.yaml metrics[].privacy.
 * Affects summary vs detail projection (FEAT-DASHBOARD §5).
 */
enum class MetricPrivacy {
    /** Safe for operational aggregate surfaces (metrics.read-summary allowlist). */
    OPERATIONAL,
    /** Principal / job scoped; detail scope only. */
    RESTRICTED,
    /** Device fingerprint class; extra redaction / local-admin only. */
    DEVICE_SENSITIVE,
    ;

    companion object {
        fun fromCatalogName(name: String): MetricPrivacy? = when (name.lowercase()) {
            "operational" -> OPERATIONAL
            "restricted" -> RESTRICTED
            "device-sensitive" -> DEVICE_SENSITIVE
            else -> null
        }
    }
}

/**
 * CORE-OBSERVABILITY §5: operational metrics must not be mixed into
 * benchmark comparison; benchmarks use MeasurementProfile/Run.
 */
enum class MetricClass {
    OPERATIONAL,
    MEASUREMENT,
}

data class MetricDefinition(
    val id: MetricId,
    val unit: String,
    val aggregation: MetricAggregation,
    val dimensions: List<String>,
    val privacy: MetricPrivacy,
    val freshness: String,
    val metricClass: MetricClass,
)
