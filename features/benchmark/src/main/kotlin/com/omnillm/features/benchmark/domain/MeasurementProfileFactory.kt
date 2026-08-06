package com.omnillm.features.benchmark.domain

/**
 * Builds [MeasurementProfile] from scenario templates + host dimensions (FEAT-BENCHMARK §1–§2).
 *
 * Every result-affecting dimension is explicit and re-hashable (DATA-MEASUREMENT §1).
 * Templates only supply defaults — callers may override any field.
 */
object MeasurementProfileFactory {

    data class HostDimensions(
        val engineBuildId: String,
        val adapterVersion: String = "1.0.0",
        val backend: String,
        val modelRevisionId: String,
        val artifactPackageId: String? = null,
        val quantizationDescriptor: String = "unknown",
        val deviceExecutionFingerprint: String,
        val driver: String = "unknown",
        val os: String = "Android",
        val pageSizeBytes: Int = 4096,
        val kvFormat: String = "default",
        val threads: Int = 4,
        val batch: Int = 1,
        val parallelSessions: Int = 1,
        val tokenizerTemplateDigest: String? = null,
        val samplingConfigDigest: String? = null,
        val fixtureCorpusDigest: String? = null,
        val thermalCeilingCelsius: Int? = 85,
        val powerCondition: String = "battery",
        val metricMethod: String = "omnillm.latency_sketch.v1",
        val clockSource: String = "elapsedRealtimeNanos",
        val histogramSketchVersion: String = "hdr-v1",
    )

    /**
     * Create a profile from a template + host dimensions.
     * Changing any dimension yields a different [MeasurementProfile.profileId].
     */
    fun fromTemplate(
        template: BenchmarkScenarioTemplate,
        host: HostDimensions,
        contextLength: Int = template.defaultContextLength,
        outputTokenLimit: Int = template.defaultOutputTokenLimit,
        sampleCount: Int = template.defaultSampleCount,
        warmupCount: Int = template.defaultWarmupCount,
        promptTokenLimit: Int = contextLength,
    ): MeasurementProfile =
        MeasurementProfile(
            engineBuildId = host.engineBuildId,
            adapterVersion = host.adapterVersion,
            backend = host.backend,
            modelRevisionId = host.modelRevisionId.lowercase(),
            artifactPackageId = host.artifactPackageId?.lowercase(),
            quantizationDescriptor = host.quantizationDescriptor,
            deviceExecutionFingerprint = host.deviceExecutionFingerprint,
            driver = host.driver,
            os = host.os,
            pageSizeBytes = host.pageSizeBytes,
            contextLength = contextLength,
            kvFormat = host.kvFormat,
            threads = host.threads,
            batch = host.batch,
            parallelSessions = host.parallelSessions,
            promptTokenLimit = promptTokenLimit,
            outputTokenLimit = outputTokenLimit,
            tokenizerTemplateDigest = host.tokenizerTemplateDigest?.lowercase(),
            samplingConfigDigest = host.samplingConfigDigest?.lowercase(),
            fixtureCorpusDigest = host.fixtureCorpusDigest?.lowercase(),
            warmupCount = warmupCount,
            sampleCount = sampleCount,
            thermalCeilingCelsius = host.thermalCeilingCelsius,
            powerCondition = host.powerCondition,
            metricMethod = host.metricMethod,
            clockSource = host.clockSource,
            histogramSketchVersion = host.histogramSketchVersion,
        )

    fun quickSmoke(host: HostDimensions): MeasurementProfile =
        fromTemplate(BenchmarkScenarioTemplate.QUICK_SMOKE, host)
}
