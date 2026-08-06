package com.omnillm.features.benchmark.projection

import com.omnillm.features.benchmark.api.ProfileComparisonView
import com.omnillm.features.benchmark.api.ProfileDimensionView
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.ProfileComparisonDimensions

/**
 * Pure comparison of two MeasurementProfiles (FEAT-BENCHMARK §5).
 * Incompatible profiles: list every differing dimension; forbid single ranking.
 */
object ProfileComparison {

    fun compare(left: MeasurementProfile, right: MeasurementProfile): ProfileComparisonView {
        val leftId = left.profileId()
        val rightId = right.profileId()
        if (leftId == rightId) {
            return ProfileComparisonView(
                leftProfileId = leftId,
                rightProfileId = rightId,
                compatible = true,
                differingDimensions = emptyList(),
                forbidSingleRanking = false,
                noticeKey = "benchmark.compare.compatible",
            )
        }
        val diffs = differingDimensions(left, right)
        return ProfileComparisonView(
            leftProfileId = leftId,
            rightProfileId = rightId,
            compatible = false,
            differingDimensions = diffs,
            forbidSingleRanking = true,
            noticeKey = "benchmark.compare.incompatible",
        )
    }

    fun differingDimensions(left: MeasurementProfile, right: MeasurementProfile): List<String> {
        val out = ArrayList<String>()
        fun add(dim: String, a: Any?, b: Any?) {
            if (a != b) out += dim
        }
        add("engineBuildId", left.engineBuildId, right.engineBuildId)
        add("adapterVersion", left.adapterVersion, right.adapterVersion)
        add("backend", left.backend, right.backend)
        add("modelRevisionId", left.modelRevisionId, right.modelRevisionId)
        add("artifactPackageId", left.artifactPackageId, right.artifactPackageId)
        add("quantizationDescriptor", left.quantizationDescriptor, right.quantizationDescriptor)
        add(
            "deviceExecutionFingerprint",
            left.deviceExecutionFingerprint,
            right.deviceExecutionFingerprint,
        )
        add("driver", left.driver, right.driver)
        add("os", left.os, right.os)
        add("pageSizeBytes", left.pageSizeBytes, right.pageSizeBytes)
        add("contextLength", left.contextLength, right.contextLength)
        add("kvFormat", left.kvFormat, right.kvFormat)
        add("threads", left.threads, right.threads)
        add("batch", left.batch, right.batch)
        add("parallelSessions", left.parallelSessions, right.parallelSessions)
        add("promptTokenLimit", left.promptTokenLimit, right.promptTokenLimit)
        add("outputTokenLimit", left.outputTokenLimit, right.outputTokenLimit)
        add("tokenizerTemplateDigest", left.tokenizerTemplateDigest, right.tokenizerTemplateDigest)
        add("samplingConfigDigest", left.samplingConfigDigest, right.samplingConfigDigest)
        add("fixtureCorpusDigest", left.fixtureCorpusDigest, right.fixtureCorpusDigest)
        add("warmupCount", left.warmupCount, right.warmupCount)
        add("sampleCount", left.sampleCount, right.sampleCount)
        add("thermalCeilingCelsius", left.thermalCeilingCelsius, right.thermalCeilingCelsius)
        add("powerCondition", left.powerCondition, right.powerCondition)
        add("metricMethod", left.metricMethod, right.metricMethod)
        add("clockSource", left.clockSource, right.clockSource)
        add("histogramSketchVersion", left.histogramSketchVersion, right.histogramSketchVersion)
        // Guard: known dimension list covers all fields we compare.
        require(out.all { it in ProfileComparisonDimensions.ALL }) {
            "compared dimension not in catalog list"
        }
        return out
    }

    fun expandDimensions(profile: MeasurementProfile): List<ProfileDimensionView> =
        listOf(
            dim("engineBuildId", profile.engineBuildId),
            dim("adapterVersion", profile.adapterVersion),
            dim("backend", profile.backend),
            dim("modelRevisionId", profile.modelRevisionId),
            dim("artifactPackageId", profile.artifactPackageId ?: "null"),
            dim("quantizationDescriptor", profile.quantizationDescriptor),
            dim("deviceExecutionFingerprint", profile.deviceExecutionFingerprint),
            dim("driver", profile.driver),
            dim("os", profile.os),
            dim("pageSizeBytes", profile.pageSizeBytes.toString()),
            dim("contextLength", profile.contextLength.toString()),
            dim("kvFormat", profile.kvFormat),
            dim("threads", profile.threads.toString()),
            dim("batch", profile.batch.toString()),
            dim("parallelSessions", profile.parallelSessions.toString()),
            dim("promptTokenLimit", profile.promptTokenLimit.toString()),
            dim("outputTokenLimit", profile.outputTokenLimit.toString()),
            dim("tokenizerTemplateDigest", profile.tokenizerTemplateDigest ?: "null"),
            dim("samplingConfigDigest", profile.samplingConfigDigest ?: "null"),
            dim("fixtureCorpusDigest", profile.fixtureCorpusDigest ?: "null"),
            dim("warmupCount", profile.warmupCount.toString()),
            dim("sampleCount", profile.sampleCount.toString()),
            dim("thermalCeilingCelsius", profile.thermalCeilingCelsius?.toString() ?: "null"),
            dim("powerCondition", profile.powerCondition),
            dim("metricMethod", profile.metricMethod),
            dim("clockSource", profile.clockSource),
            dim("histogramSketchVersion", profile.histogramSketchVersion),
        )

    private fun dim(key: String, value: String): ProfileDimensionView =
        ProfileDimensionView(
            key = key,
            value = value,
            sourceKey = "benchmark.profile.dimension.$key",
        )
}
