package com.omnillm.engines.mlcllm.resource

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector

/**
 * Resource envelope estimation hooks (ENGINE-MLC §6, CORE-RESOURCE).
 *
 * Dimensions include generated code/module, CPU anon/file, weights, KV,
 * runtime workspace, GPU dedicated/shared, kernel compile peak, temp disk, threads.
 *
 * CPU RSS must **not** represent GPU allocation. Unknown GPU dimensions stay
 * zero rather than inventing unmeasured peaks — Plan uses conservative CPU
 * placeholders until compiled metadata + qualified profile exist.
 *
 * Estimates are **advisory for Plan**; Governor reservation is authoritative.
 */
object ResourceEnvelopeEstimator {

    const val DEFAULT_THREADS: Long = 2L
    const val DEFAULT_LOAD_FDS: Long = 3L

    data class EstimateInput(
        val weightFileBytes: Long = 0L,
        /** Generated library / module file charge. */
        val generatedModuleBytes: Long = 0L,
        val contextLength: Int = 2048,
        val nThreads: Int = DEFAULT_THREADS.toInt(),
        val kvBytesPerToken: Long = 0L,
        val scratchBytes: Long = 0L,
        val temporaryDiskBytes: Long = 0L,
        val gpuDedicatedBytes: Long = 0L,
        val gpuSharedBytes: Long = 0L,
        val npuBytes: Long = 0L,
        /** Kernel compile peak extra (on top of steady GPU/CPU). */
        val kernelCompilePeakBytes: Long = 0L,
        val peakFactorNumerator: Long = 12L,
        val peakFactorDenominator: Long = 10L,
    ) {
        init {
            require(weightFileBytes >= 0L)
            require(generatedModuleBytes >= 0L)
            require(contextLength > 0)
            require(nThreads > 0)
            require(kvBytesPerToken >= 0L)
            require(scratchBytes >= 0L)
            require(temporaryDiskBytes >= 0L)
            require(gpuDedicatedBytes >= 0L)
            require(gpuSharedBytes >= 0L)
            require(npuBytes >= 0L)
            require(kernelCompilePeakBytes >= 0L)
            require(peakFactorNumerator >= peakFactorDenominator)
            require(peakFactorDenominator > 0L)
        }
    }

    fun estimateProbe(): ResourceEnvelope {
        val steady = ResourceVector(
            cpuAnonBytes = 256L * 1024L,
            nativeThreads = 1L,
            fileDescriptors = 1L,
        )
        val peak = ResourceVector(
            cpuAnonBytes = 512L * 1024L,
            nativeThreads = 2L,
            fileDescriptors = 2L,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateLoad(input: EstimateInput): ResourceEnvelope {
        val fileSteady = safeAdd(input.weightFileBytes, input.generatedModuleBytes)
        val kvSteady = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val runtimeAnon = if (fileSteady > 0L) {
            maxOf(2L * 1024L * 1024L, fileSteady / 50L)
        } else {
            // Conservative placeholder when compiled metadata unknown (ENGINE-MLC §6)
            8L * 1024L * 1024L
        }
        val steadyAnon = safeAdd(safeAdd(kvSteady, input.scratchBytes), runtimeAnon)

        val steady = ResourceVector(
            cpuAnonBytes = steadyAnon,
            cpuFileBytes = fileSteady,
            gpuDedicatedBytes = input.gpuDedicatedBytes,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong(),
            fileDescriptors = DEFAULT_LOAD_FDS,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )

        val peakAnon = scaleUp(steadyAnon, input.peakFactorNumerator, input.peakFactorDenominator)
        val peakGpu = safeAdd(
            scaleUp(
                input.gpuDedicatedBytes,
                input.peakFactorNumerator,
                input.peakFactorDenominator,
            ),
            input.kernelCompilePeakBytes,
        )
        val peak = ResourceVector(
            cpuAnonBytes = peakAnon,
            cpuFileBytes = fileSteady,
            gpuDedicatedBytes = peakGpu,
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = DEFAULT_LOAD_FDS + 1L,
            temporaryDiskBytes = safeAdd(input.temporaryDiskBytes, input.kernelCompilePeakBytes / 4),
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    fun estimateInference(input: EstimateInput): ResourceEnvelope {
        val kv = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val scratch = maxOf(input.scratchBytes, 512L * 1024L)
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
            gpuDedicatedBytes = safeAdd(input.gpuDedicatedBytes, input.kernelCompilePeakBytes),
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
