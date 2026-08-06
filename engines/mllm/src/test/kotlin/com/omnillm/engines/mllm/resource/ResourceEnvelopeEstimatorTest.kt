package com.omnillm.engines.mllm.resource

import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceEnvelopeEstimatorTest {

    @Test
    fun estimateProbe_isBounded() {
        val env = ResourceEnvelopeEstimator.estimateProbe()
        assertTrue(env.steady.cpuAnonBytes > 0L)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
        assertTrue(env.steady.fileDescriptors >= 1L)
    }

    @Test
    fun estimateLoad_includesServerOverhead() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 100L * 1024L * 1024L,
                contextLength = 2048,
                scratchBytes = 2L * 1024L * 1024L,
                serverOverheadBytes = ResourceEnvelopeEstimator.PLACEHOLDER_SERVER_OVERHEAD_BYTES,
            ),
        )
        assertTrue(
            env.steady.cpuAnonBytes >= ResourceEnvelopeEstimator.PLACEHOLDER_SERVER_OVERHEAD_BYTES,
        )
        assertTrue(env.steady.cpuFileBytes == 100L * 1024L * 1024L)
        assertTrue(env.steady.fileDescriptors >= ResourceEnvelopeEstimator.DEFAULT_LOAD_FDS)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun estimateInferenceAndEmbedding_nonNegative() {
        val inf = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 1L * 1024L * 1024L),
        )
        assertTrue(inf.steady.cpuAnonBytes > 0L)
        val emb = ResourceEnvelopeEstimator.estimateEmbedding()
        assertTrue(emb.steady.cpuAnonBytes > 0L)
        val start = ResourceEnvelopeEstimator.estimateServerStart()
        assertTrue(
            start.steady.cpuAnonBytes >= ResourceEnvelopeEstimator.PLACEHOLDER_SERVER_OVERHEAD_BYTES,
        )
    }
}
