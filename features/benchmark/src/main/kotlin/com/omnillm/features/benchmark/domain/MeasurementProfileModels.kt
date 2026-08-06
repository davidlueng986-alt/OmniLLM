package com.omnillm.features.benchmark.domain

import com.omnillm.core.canonical.IdentityHashing

/**
 * Canonical MeasurementProfile dimensions (DATA-MEASUREMENT §1 / FEAT-BENCHMARK §1).
 *
 * Profile identity covers engine build, adapter, backend, model revision/package/
 * quantization, device/driver/OS/page size, ctx/KV/threads/batch/parallelism,
 * tokenizer/template, sampling, fixture, warmup/sample, thermal/power, and
 * metric method. Canonical object and hash are stored together and re-hashable.
 */
data class MeasurementProfile(
    val engineBuildId: String,
    val adapterVersion: String,
    val backend: String,
    /** ModelRevisionId — 64-char lower-case hex (ADR-008). */
    val modelRevisionId: String,
    /** ArtifactPackageId — 64-char lower-case hex when known. */
    val artifactPackageId: String?,
    val quantizationDescriptor: String,
    val deviceExecutionFingerprint: String,
    val driver: String,
    val os: String,
    val pageSizeBytes: Int,
    val contextLength: Int,
    val kvFormat: String,
    val threads: Int,
    val batch: Int,
    val parallelSessions: Int,
    val promptTokenLimit: Int,
    val outputTokenLimit: Int,
    /** Tokenizer/template digest (hex) when known. */
    val tokenizerTemplateDigest: String?,
    val samplingConfigDigest: String?,
    val fixtureCorpusDigest: String?,
    val warmupCount: Int,
    val sampleCount: Int,
    val thermalCeilingCelsius: Int?,
    val powerCondition: String,
    val metricMethod: String,
    val clockSource: String,
    val histogramSketchVersion: String,
) {
    init {
        require(engineBuildId.isNotBlank()) { "engineBuildId must be non-blank" }
        require(adapterVersion.isNotBlank()) { "adapterVersion must be non-blank" }
        require(backend.isNotBlank()) { "backend must be non-blank" }
        require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
        artifactPackageId?.let {
            require(it.matches(HEX64)) { "artifactPackageId must be 64-char hex" }
        }
        require(quantizationDescriptor.isNotBlank()) { "quantizationDescriptor must be non-blank" }
        require(deviceExecutionFingerprint.isNotBlank()) {
            "deviceExecutionFingerprint must be non-blank"
        }
        require(driver.isNotBlank()) { "driver must be non-blank" }
        require(os.isNotBlank()) { "os must be non-blank" }
        require(pageSizeBytes > 0) { "pageSizeBytes must be positive" }
        require(contextLength > 0) { "contextLength must be positive" }
        require(kvFormat.isNotBlank()) { "kvFormat must be non-blank" }
        require(threads > 0) { "threads must be positive" }
        require(batch > 0) { "batch must be positive" }
        require(parallelSessions > 0) { "parallelSessions must be positive" }
        require(promptTokenLimit > 0) { "promptTokenLimit must be positive" }
        require(outputTokenLimit > 0) { "outputTokenLimit must be positive" }
        tokenizerTemplateDigest?.let {
            require(it.matches(HEX64)) { "tokenizerTemplateDigest must be 64-char hex" }
        }
        samplingConfigDigest?.let {
            require(it.matches(HEX64)) { "samplingConfigDigest must be 64-char hex" }
        }
        fixtureCorpusDigest?.let {
            require(it.matches(HEX64)) { "fixtureCorpusDigest must be 64-char hex" }
        }
        require(warmupCount >= 0) { "warmupCount must be non-negative" }
        require(sampleCount in 1..10_000) { "sampleCount must be 1..10000" }
        thermalCeilingCelsius?.let {
            require(it in -40..150) { "thermalCeilingCelsius out of range" }
        }
        require(powerCondition.isNotBlank()) { "powerCondition must be non-blank" }
        require(metricMethod.isNotBlank()) { "metricMethod must be non-blank" }
        require(clockSource.isNotBlank()) { "clockSource must be non-blank" }
        require(histogramSketchVersion.isNotBlank()) {
            "histogramSketchVersion must be non-blank"
        }
    }

    /**
     * RFC8785-style compact JSON with lexicographically sorted keys for stable hashing.
     * Changing any result-affecting dimension yields a different profile id.
     */
    fun toCanonicalJson(): String {
        val parts = linkedMapOf(
            "adapterVersion" to jsonString(adapterVersion),
            "artifactPackageId" to (artifactPackageId?.let { jsonString(it) } ?: "null"),
            "backend" to jsonString(backend),
            "batch" to batch.toString(),
            "clockSource" to jsonString(clockSource),
            "contextLength" to contextLength.toString(),
            "deviceExecutionFingerprint" to jsonString(deviceExecutionFingerprint),
            "driver" to jsonString(driver),
            "engineBuildId" to jsonString(engineBuildId),
            "fixtureCorpusDigest" to (fixtureCorpusDigest?.let { jsonString(it) } ?: "null"),
            "histogramSketchVersion" to jsonString(histogramSketchVersion),
            "kvFormat" to jsonString(kvFormat),
            "metricMethod" to jsonString(metricMethod),
            "modelRevisionId" to jsonString(modelRevisionId),
            "os" to jsonString(os),
            "outputTokenLimit" to outputTokenLimit.toString(),
            "pageSizeBytes" to pageSizeBytes.toString(),
            "parallelSessions" to parallelSessions.toString(),
            "powerCondition" to jsonString(powerCondition),
            "promptTokenLimit" to promptTokenLimit.toString(),
            "quantizationDescriptor" to jsonString(quantizationDescriptor),
            "sampleCount" to sampleCount.toString(),
            "samplingConfigDigest" to (samplingConfigDigest?.let { jsonString(it) } ?: "null"),
            "thermalCeilingCelsius" to (thermalCeilingCelsius?.toString() ?: "null"),
            "threads" to threads.toString(),
            "tokenizerTemplateDigest" to
                (tokenizerTemplateDigest?.let { jsonString(it) } ?: "null"),
            "warmupCount" to warmupCount.toString(),
        )
        return parts.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    /**
     * Domain-separated MeasurementProfileId (hex SHA-256).
     * Separator is feature-local (not a generated catalog constant) but stable.
     */
    fun profileId(): String =
        IdentityHashing.identityDigestHex(DOMAIN_SEPARATOR, toCanonicalJson())

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        const val DOMAIN_SEPARATOR: String = "OmniLLM.MeasurementProfileId.v1"
        const val SCHEMA_VERSION: String = "1"

        private fun jsonString(s: String): String =
            "\"${escapeJson(s)}\""

        private fun escapeJson(s: String): String =
            buildString {
                for (c in s) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
            }
    }
}

/**
 * Dimensions that must match for automatic comparison
 * (FEAT-BENCHMARK §5 — only auto-compare canonically compatible runs).
 */
object ProfileComparisonDimensions {
    val ALL: List<String> = listOf(
        "engineBuildId",
        "adapterVersion",
        "backend",
        "modelRevisionId",
        "artifactPackageId",
        "quantizationDescriptor",
        "deviceExecutionFingerprint",
        "driver",
        "os",
        "pageSizeBytes",
        "contextLength",
        "kvFormat",
        "threads",
        "batch",
        "parallelSessions",
        "promptTokenLimit",
        "outputTokenLimit",
        "tokenizerTemplateDigest",
        "samplingConfigDigest",
        "fixtureCorpusDigest",
        "warmupCount",
        "sampleCount",
        "thermalCeilingCelsius",
        "powerCondition",
        "metricMethod",
        "clockSource",
        "histogramSketchVersion",
    )
}
