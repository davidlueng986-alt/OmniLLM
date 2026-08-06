package com.omnillm.engines.llamacpp.resource

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector

/**
 * Resource envelope estimation hooks (ENGINE-LLAMACPP §7, CORE-RESOURCE).
 *
 * Plan distinguishes:
 * - weights mmap / file charge → [ResourceVector.cpuFileBytes]
 * - anon model/context / KV / compute / sampling / grammar → [ResourceVector.cpuAnonBytes]
 * - threads, FDs, temporary disk, accelerator memory
 *
 * Context inverse is bounded by engine hard max, KV formula, app/anon/accelerator
 * cap and OperatingConstraint — callers supply [EstimateInput] dimensions.
 *
 * Estimates are **advisory for Plan**; Governor reservation is authoritative.
 * Unknown dimensions stay zero rather than inventing unmeasured peaks.
 */
object ResourceEnvelopeEstimator {

    /** Default thread charge when nThreads not specified. */
    const val DEFAULT_THREADS: Long = 2L

    /** FD charge: model file + optional mmapped secondary. */
    const val DEFAULT_LOAD_FDS: Long = 2L

    data class EstimateInput(
        val weightFileBytes: Long = 0L,
        val contextLength: Int = 2048,
        val nThreads: Int = DEFAULT_THREADS.toInt(),
        /** Bytes per token for KV cache estimate (model-dependent; 0 if unknown). */
        val kvBytesPerToken: Long = 0L,
        /** Extra compute / sampling / grammar scratch. */
        val scratchBytes: Long = 0L,
        val temporaryDiskBytes: Long = 0L,
        val gpuDedicatedBytes: Long = 0L,
        val gpuSharedBytes: Long = 0L,
        val npuBytes: Long = 0L,
        /** Peak multiplier during load/prefill (must be >= 1.0 conceptually). */
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

    /**
     * Load envelope: file-backed weights + anon model structures + threads/FDs.
     */
    fun estimateLoad(input: EstimateInput): ResourceEnvelope {
        val kvSteady = safeMul(input.kvBytesPerToken, input.contextLength.toLong())
        val anonSteady = safeAdd(kvSteady, input.scratchBytes)
        // Residual model structures when weights are mmap'd (bounded placeholder).
        val modelAnon = if (input.weightFileBytes > 0L) {
            // ~1% overhead placeholder when unknown exact layout; floor 1 MiB
            maxOf(1L * 1024L * 1024L, input.weightFileBytes / 100L)
        } else {
            4L * 1024L * 1024L
        }
        val steadyAnon = safeAdd(anonSteady, modelAnon)

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

        val peakAnon = scaleUp(steadyAnon, input.peakFactorNumerator, input.peakFactorDenominator)
        val peak = ResourceVector(
            cpuAnonBytes = peakAnon,
            cpuFileBytes = input.weightFileBytes,
            gpuDedicatedBytes = scaleUp(
                input.gpuDedicatedBytes,
                input.peakFactorNumerator,
                input.peakFactorDenominator,
            ),
            gpuSharedBytes = input.gpuSharedBytes,
            npuBytes = input.npuBytes,
            nativeThreads = input.nThreads.toLong() + 1L,
            fileDescriptors = DEFAULT_LOAD_FDS + 1L,
            temporaryDiskBytes = input.temporaryDiskBytes,
        )
        return ResourceEnvelope(steady = steady, peak = peak)
    }

    /**
     * Inference delta on top of already-loaded model: prefill scratch + generation.
     * Does not re-charge full weight file bytes (those stay on LoadedModel allocation).
     */
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

    /**
     * Inverse: max context length under an anon budget given kvBytesPerToken.
     * Returns 0 when formula unknown (kvBytesPerToken == 0).
     */
    fun maxContextForAnonBudget(
        anonBudgetBytes: Long,
        kvBytesPerToken: Long,
        scratchBytes: Long = 0L,
    ): Int {
        if (kvBytesPerToken <= 0L || anonBudgetBytes <= scratchBytes) return 0
        val forKv = anonBudgetBytes - scratchBytes
        val tokens = forKv / kvBytesPerToken
        return tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
