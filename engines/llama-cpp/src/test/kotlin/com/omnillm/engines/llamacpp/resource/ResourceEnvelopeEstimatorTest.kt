package com.omnillm.engines.llamacpp.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceEnvelopeEstimatorTest {

    @Test
    fun estimateLoad_peakDominatesSteady() {
        val env = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 100_000_000L,
                contextLength = 4096,
                nThreads = 4,
                kvBytesPerToken = 1024L,
                scratchBytes = 1_000_000L,
            ),
        )
        assertTrue(env.peak.dominates(env.steady))
        assertEquals(100_000_000L, env.steady.cpuFileBytes)
        assertEquals(4L, env.steady.nativeThreads)
        assertTrue(env.steady.cpuAnonBytes > 0L)
    }

    @Test
    fun estimateInference_noWeightFileCharge() {
        val env = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 50_000_000L,
                contextLength = 2048,
                nThreads = 2,
            ),
        )
        assertEquals(0L, env.steady.cpuFileBytes)
        assertEquals(0L, env.peak.cpuFileBytes)
        assertTrue(env.peak.cpuAnonBytes >= env.steady.cpuAnonBytes)
    }

    @Test
    fun maxContextForAnonBudget_inverseKvFormula() {
        assertEquals(0, ResourceEnvelopeEstimator.maxContextForAnonBudget(1_000_000, 0))
        assertEquals(
            1000,
            ResourceEnvelopeEstimator.maxContextForAnonBudget(
                anonBudgetBytes = 1_000_000L + 100_000L,
                kvBytesPerToken = 1000L,
                scratchBytes = 100_000L,
            ),
        )
    }

    @Test
    fun estimateProbe_positiveEnvelope() {
        val env = ResourceEnvelopeEstimator.estimateProbe()
        assertTrue(env.peak.dominates(env.steady))
        assertTrue(env.steady.cpuAnonBytes > 0L)
    }
}
