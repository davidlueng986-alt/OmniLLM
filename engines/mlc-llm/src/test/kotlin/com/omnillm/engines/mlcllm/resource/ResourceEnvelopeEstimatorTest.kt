package com.omnillm.engines.mlcllm.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceEnvelopeEstimatorTest {

    @Test
    fun estimateProbe_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateProbe()
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
        assertTrue(env.steady.nativeThreads >= 1L)
    }

    @Test
    fun estimateLoad_includesGeneratedModuleAndKeepsGpuSeparate() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 10L * 1024L * 1024L,
                generatedModuleBytes = 2L * 1024L * 1024L,
                gpuDedicatedBytes = 4L * 1024L * 1024L,
                kernelCompilePeakBytes = 1L * 1024L * 1024L,
            ),
        )
        assertEquals(12L * 1024L * 1024L, env.steady.cpuFileBytes)
        // GPU must not be folded into CPU RSS
        assertTrue(env.steady.gpuDedicatedBytes == 4L * 1024L * 1024L)
        assertTrue(env.peak.gpuDedicatedBytes >= env.steady.gpuDedicatedBytes)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateLoad_unknownCompiledMetadata_usesConservativeCpuPlaceholder() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                generatedModuleBytes = 0L,
            ),
        )
        assertTrue(env.steady.cpuAnonBytes >= 8L * 1024L * 1024L)
        assertEquals(0L, env.steady.gpuDedicatedBytes)
    }

    @Test
    fun estimateInference_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 1024L * 1024L),
        )
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateEmbedding_hasPositiveEnvelope() {
        val env = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 0L),
        )
        assertTrue(env.steady.cpuAnonBytes >= 256L * 1024L)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }
}
