package com.omnillm.engines.ortgenai.resource

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector

/**
 * Resource envelope estimation hooks (ENGINE-ORTGENAI §7, CORE-RESOURCE).
 *
 * Dimensions (design):
 * - weights / external data mmap → [ResourceVector.cpuFileBytes]
 * - graph optimization, provider compile/cache, KV, workspace → anon / peak
 * - CPU anon/file, accelerator (GPU/NPU), threads, FD, temp disk
 *
 * When provider allocation is unknown, use **conservative upper bounds** —
 * never claim 0 for unknown EP memory (ENGINE-ORTGENAI §7).
 *
 * Estimates are advisory for Plan; Governor reservation is authoritative.
 */
object ResourceEnvelopeEstimator {

    const val DEFAULT_THREADS: Long = 2L
    const val DEFAULT_LOAD_FDS: Long = 4L

    data class EstimateInput(
        val weightFileBytes: Long = 0L,
        val externalDataFileBytes: Long = 0L,
        val contextLength: Int = 2048,
        val nThreads: Int = DEFAULT_THREADS.toInt(),
        val kvBytesPerToken: Long = 0L,
        val graphOptScratchBytes: Long = 0L,
        val providerCompileCacheBytes: Long = 0L,
        val temporaryDiskBytes: Long = 0L,
        val gpuDedicatedBytes: Long = 0L,
        val gpuSharedBytes: Long = 0L,
        val npuBytes: Long = 0L,
        /** Alias used by inference/embedding helpers. */
        val scratchBytes: Long = 0L,
        val peakFactorNumerator: Long = 15L,
        val peakFactorDenominator: Long = 10L,
    ) {
        init {
            require(weightFileBytes >= 0L)
            require(externalDataFileBytes >= 0L)
            require(contextLength > 0)
            require(nThreads > 0)
            require(kvBytesPerToken >= 0L)
            require(graphOptScratchBytes >= 0L)
            require(providerCompileCacheBytes >= 0L)
            require(temporaryDiskBytes >= 0L)
            require(gpuDedicatedBytes >= 0L)
            require(gpuSharedBytes >= 0L)
            require(npuBytes >= 0L)
            require(scratchBytes >= 0L)
            require(peakFactorNumerator >= peakFactorDenominator)
            require(peakFactorDenominator > 0L)
        }
    }

    fun estimateProbe(): ResourceEnvelope {
        val steady = ResourceVector(
            cpuAnonBytes = 512L * 1024L,
            nativeThreads = 1L,
            fileDescriptors = 2L,
        )
        val peak = ResourceVector(
            cpuAnonBytes = 2L * 1024L * 1024L,
            nativeThreads = 2L,
            fileDescriptors = 4L,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    /**
     * Load envelope: file-backed weights + external data + graph opt / compile peak.
     * Unknown EP memory stays zero only when input explicitly supplies 0 **and**
     * callers understand that as "not yet estimated" for Plan — Governor must not
     * treat missing EP dims as free for privileged placement.
     */
    fun estimateLoad(input: EstimateInput): ResourceEnvelope {
        val fileBytes = safeAdd(input.weightFileBytes, input.externalDataFileBytes)
        val kvSteady = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val modelAnon = if (fileBytes > 0L) {
            maxOf(2L * 1024L * 1024L, fileBytes / 50L)
        } else {
            // Conservative placeholder when package size unknown at pure plan.
            8L * 1024L * 1024L
        }
        val scratch = safeAdd(input.graphOptScratchBytes, input.scratchBytes)
        val steadyAnon = safeAdd(
            safeAdd(kvSteady, scratch),
            modelAnon,
        )

        val steady = ResourceVector(
            cpuAnonBytes = steadyAnon,
            cpuFileBytes = fileBytes,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong(),
            fileDescriptors = DEFAULT_LOAD_FDS,
            temporaryDiskBytes = safeAdd(input.temporaryDiskBytes, input.providerCompileCacheBytes),
        )

        val peakAnon = scaleUp(steadyAnon, input.peakFactorNumerator, input.peakFactorDenominator)
        val peak = ResourceVector(
            cpuAnonBytes = peakAnon,
            cpuFileBytes = fileBytes,
            gpuDedicatedBytes = scaleUp(
                input.gpuDedicatedBytes,
                input.peakFactorNumerator,
                input.peakFactorDenominator,
            ),
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = scaleUp(
                input.npuBytes,
                input.peakFactorNumerator,
                input.peakFactorDenominator,
            ),
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = DEFAULT_LOAD_FDS + 2L,
            temporaryDiskBytes = safeAdd(input.temporaryDiskBytes, input.providerCompileCacheBytes),
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateInference(input: EstimateInput): ResourceEnvelope {
        val kv = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val scratch = maxOf(
            safeAdd(input.scratchBytes, input.graphOptScratchBytes),
            512L * 1024L,
        )
        val steadyAnon = safeAdd(kv, scratch)
        val peakAnon = scaleUp(steadyAnon, input.peakFactorNumerator, input.peakFactorDenominator)

        val steady = ResourceVector(
            cpuAnonBytes = steadyAnon,
            nativeThreads = input.nThreads.toLong(),
            fileDescriptors = 1L,
            temporaryDiskBytes = input.temporaryDiskBytes,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
        )
        val peak = ResourceVector(
            cpuAnonBytes = peakAnon,
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = 2L,
            temporaryDiskBytes = input.temporaryDiskBytes,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateEmbedding(input: EstimateInput): ResourceEnvelope {
        val base = maxOf(input.scratchBytes, 256L * 1024L)
        return ResourceEnvelope(
            steady = ResourceVector(
                cpuAnonBytes = base,
                nativeThreads = 1L,
                fileDescriptors = 1L,
            ),
            peak = ResourceVector(
                cpuAnonBytes = safeMul(base, 2L),
                nativeThreads = 2L,
                fileDescriptors = 1L,
            ),
        )
    }

    private fun safeAdd(a: Long, b: Long): Long =
        try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE / 4
        }

    private fun safeMul(a: Long, b: Long): Long =
        try {
            Math.multiplyExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE / 4
        }

    private fun scaleUp(value: Long, num: Long, den: Long): Long {
        if (value == 0L) return 0L
        return try {
            val scaled = Math.multiplyExact(value, num) / den
            maxOf(scaled, value)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE / 4
        }
    }
}
