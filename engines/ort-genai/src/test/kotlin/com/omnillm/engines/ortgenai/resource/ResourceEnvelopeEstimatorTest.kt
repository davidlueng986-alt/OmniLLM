package com.omnillm.engines.ortgenai.resource

import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceEnvelopeEstimatorTest {

    @Test
    fun estimateProbe_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateProbe()
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateLoad_includesGraphOptPlaceholder() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                graphOptScratchBytes = 4L * 1024L * 1024L,
            ),
        )
        assertTrue(env.steady.cpuAnonBytes >= 4L * 1024L * 1024L)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateLoad_withWeights_chargesFileBytes() {
        val weights = 100L * 1024L * 1024L
        val external = 50L * 1024L * 1024L
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = weights,
                externalDataFileBytes = external,
            ),
        )
        assertTrue(env.steady.cpuFileBytes >= weights + external)
        assertTrue(env.peak.cpuFileBytes >= env.steady.cpuFileBytes)
    }

    @Test
    fun estimateInference_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 1024L * 1024L),
        )
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateEmbedding_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 512L * 1024L),
        )
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }
}
