package com.omnillm.engines.mllm.resource

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector

/**
 * Resource envelope estimation hooks (ENGINE-MLLM §6–§7, CORE-RESOURCE).
 *
 * mllm envelopes must include:
 * - Go / server fixed overhead (not assumed from JNI numbers)
 * - model / KV / workspace
 * - queue
 * - CPU / accelerator
 * - port / socket / FD charge
 * - temporary cache
 *
 * Self-report from the server does **not** lower the envelope floor.
 * Estimates are advisory for Plan; Governor reservation is authoritative.
 * Unknown dimensions stay zero rather than inventing unmeasured peaks.
 */
object ResourceEnvelopeEstimator {

    /** Placeholder thread charge until measured. */
    const val DEFAULT_THREADS: Long = 2L

    /**
     * Conservative server fixed-overhead floor (bytes) used only as a planning
     * placeholder. Must be replaced with measured values per EngineBuildId.
     */
    const val PLACEHOLDER_SERVER_OVERHEAD_BYTES: Long = 8L * 1024L * 1024L

    /** FD charge: model + private channel socket(s). */
    const val DEFAULT_LOAD_FDS: Long = 4L

    data class EstimateInput(
        val weightFileBytes: Long = 0L,
        val contextLength: Int = 2048,
        val nThreads: Int = DEFAULT_THREADS.toInt(),
        val kvBytesPerToken: Long = 0L,
        val scratchBytes: Long = 0L,
        val temporaryDiskBytes: Long = 0L,
        val gpuDedicatedBytes: Long = 0L,
        val gpuSharedBytes: Long = 0L,
        val npuBytes: Long = 0L,
        val serverOverheadBytes: Long = PLACEHOLDER_SERVER_OVERHEAD_BYTES,
        val peakFactorNumerator: Long = 12L,
        val peakFactorDenominator: Long = 10L,
    ) {
        init {
            require(weightFileBytes >= 0L)
            require(contextLength > 0)
            require(nThreads > 0)
            require(kvBytesPerToken >= 0L)
            require(scratchBytes >= 0L)
            require(temporaryDiskBytes >= 0L)
            require(gpuDedicatedBytes >= 0L)
            require(gpuSharedBytes >= 0L)
            require(npuBytes >= 0L)
            require(serverOverheadBytes >= 0L)
            require(peakFactorNumerator >= peakFactorDenominator)
            require(peakFactorDenominator > 0L)
        }
    }

    /** Bounded server/backend presence check — small Go process floor only. */
    fun estimateProbe(): ResourceEnvelope {
        val steady = ResourceVector(
            cpuAnonBytes = 512L * 1024L,
            nativeThreads = 1L,
            fileDescriptors = 2L,
        )
        val peak = ResourceVector(
            cpuAnonBytes = 1L * 1024L * 1024L,
            nativeThreads = 2L,
            fileDescriptors = 3L,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    /**
     * Server start / ensureReady envelope (Go fixed overhead + channel FD).
     * Distinct from model load so plan can reserve before mutation.
     */
    fun estimateServerStart(
        serverOverheadBytes: Long = PLACEHOLDER_SERVER_OVERHEAD_BYTES,
    ): ResourceEnvelope {
        val steady = ResourceVector(
            cpuAnonBytes = serverOverheadBytes,
            nativeThreads = DEFAULT_THREADS,
            fileDescriptors = 3L,
        )
        val peak = ResourceVector(
            cpuAnonBytes = safeMulDiv(serverOverheadBytes, 12L, 10L),
            nativeThreads = DEFAULT_THREADS + 1L,
            fileDescriptors = 4L,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateLoad(input: EstimateInput): ResourceEnvelope {
        val kvSteady = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val modelAnon = if (input.weightFileBytes > 0L) {
            maxOf(1L * 1024L * 1024L, input.weightFileBytes / 100L)
        } else {
            4L * 1024L * 1024L
        }
        val steadyAnon = safeAdd(
            safeAdd(safeAdd(kvSteady, input.scratchBytes), modelAnon),
            input.serverOverheadBytes,
        )
        val steady = ResourceVector(
            cpuAnonBytes = steadyAnon,
            cpuFileBytes = input.weightFileBytes,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong(),
            fileDescriptors = DEFAULT_LOAD_FDS,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )
        val peakAnon = safeMulDiv(
            steadyAnon,
            input.peakFactorNumerator,
            input.peakFactorDenominator,
        )
        val peak = ResourceVector(
            cpuAnonBytes = maxOf(peakAnon, steadyAnon),
            cpuFileBytes = input.weightFileBytes,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = DEFAULT_LOAD_FDS + 1L,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateInference(input: EstimateInput): ResourceEnvelope {
        val kv = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val steadyAnon = safeAdd(
            safeAdd(kv, input.scratchBytes),
            // Residual server process already charged at load; keep small decode scratch only.
            1L * 1024L * 1024L,
        )
        val steady = ResourceVector(
            cpuAnonBytes = steadyAnon,
            nativeThreads = input.nThreads.toLong(),
            fileDescriptors = 2L,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )
        val peakAnon = safeMulDiv(
            steadyAnon,
            input.peakFactorNumerator,
            input.peakFactorDenominator,
        )
        val peak = ResourceVector(
            cpuAnonBytes = maxOf(peakAnon, steadyAnon),
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = 3L,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    /** Embedding plan envelope; execute remains UNKNOWN until API evidence. */
    fun estimateEmbedding(input: EstimateInput = EstimateInput(scratchBytes = 512L * 1024L)): ResourceEnvelope {
        val steady = ResourceVector(
            cpuAnonBytes = maxOf(input.scratchBytes, 512L * 1024L),
            nativeThreads = 1L,
            fileDescriptors = 2L,
        )
        val peak = ResourceVector(
            cpuAnonBytes = safeMulDiv(steady.cpuAnonBytes, 12L, 10L),
            nativeThreads = 2L,
            fileDescriptors = 2L,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun safeMul(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0L
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE
        return a * b
    }

    private fun safeMulDiv(value: Long, num: Long, den: Long): Long {
        if (den <= 0L) return value
        if (value > Long.MAX_VALUE / num) return Long.MAX_VALUE
        return (value * num) / den
    }
}
