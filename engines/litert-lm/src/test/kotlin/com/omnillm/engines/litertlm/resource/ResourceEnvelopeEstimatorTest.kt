package com.omnillm.engines.litertlm.resource

import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceEnvelopeEstimatorTest {

    @Test
    fun estimateProbe_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateProbe()
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateLoad_includesSdkFixedOverhead() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                sdkFixedBytes = ResourceEnvelopeEstimator.DEFAULT_SDK_FIXED_BYTES,
            ),
        )
        assertTrue(env.steady.cpuAnonBytes >= ResourceEnvelopeEstimator.DEFAULT_SDK_FIXED_BYTES)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateInference_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 1024L * 1024L),
        )
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }
}
