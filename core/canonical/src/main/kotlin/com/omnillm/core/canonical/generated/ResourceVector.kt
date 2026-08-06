// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/**
 * ResourceVector from specs/canonical-types.yaml.
 * All dimensions are additive charges; no peak field is embedded in this type.
 * Arithmetic uses checked overflow (fail closed).
 */
data class ResourceVector(
    val cpuAnonBytes: Long = 0L,
    val cpuFileBytes: Long = 0L,
    val sharedMemoryChargeBytes: Long = 0L,
    val gpuDedicatedBytes: Long = 0L,
    val gpuSharedBytes: Long = 0L,
    val npuBytes: Long = 0L,
    val nativeThreads: Long = 0L,
    val fileDescriptors: Long = 0L,
    val temporaryDiskBytes: Long = 0L,
    val networkBytesInFlight: Long = 0L,
) {
    init {
        require(
            cpuAnonBytes >= 0L &&
            cpuFileBytes >= 0L &&
            sharedMemoryChargeBytes >= 0L &&
            gpuDedicatedBytes >= 0L &&
            gpuSharedBytes >= 0L &&
            npuBytes >= 0L &&
            nativeThreads >= 0L &&
            fileDescriptors >= 0L &&
            temporaryDiskBytes >= 0L &&
            networkBytesInFlight >= 0L
        ) { "ResourceVector dimensions must be non-negative" }
    }

    fun plus(other: ResourceVector): ResourceVector = ResourceVector(
            cpuAnonBytes = Math.addExact(this.cpuAnonBytes, other.cpuAnonBytes),
            cpuFileBytes = Math.addExact(this.cpuFileBytes, other.cpuFileBytes),
            sharedMemoryChargeBytes = Math.addExact(this.sharedMemoryChargeBytes, other.sharedMemoryChargeBytes),
            gpuDedicatedBytes = Math.addExact(this.gpuDedicatedBytes, other.gpuDedicatedBytes),
            gpuSharedBytes = Math.addExact(this.gpuSharedBytes, other.gpuSharedBytes),
            npuBytes = Math.addExact(this.npuBytes, other.npuBytes),
            nativeThreads = Math.addExact(this.nativeThreads, other.nativeThreads),
            fileDescriptors = Math.addExact(this.fileDescriptors, other.fileDescriptors),
            temporaryDiskBytes = Math.addExact(this.temporaryDiskBytes, other.temporaryDiskBytes),
            networkBytesInFlight = Math.addExact(this.networkBytesInFlight, other.networkBytesInFlight),
    )

    fun minus(other: ResourceVector): ResourceVector = ResourceVector(
            cpuAnonBytes = Math.subtractExact(this.cpuAnonBytes, other.cpuAnonBytes),
            cpuFileBytes = Math.subtractExact(this.cpuFileBytes, other.cpuFileBytes),
            sharedMemoryChargeBytes = Math.subtractExact(this.sharedMemoryChargeBytes, other.sharedMemoryChargeBytes),
            gpuDedicatedBytes = Math.subtractExact(this.gpuDedicatedBytes, other.gpuDedicatedBytes),
            gpuSharedBytes = Math.subtractExact(this.gpuSharedBytes, other.gpuSharedBytes),
            npuBytes = Math.subtractExact(this.npuBytes, other.npuBytes),
            nativeThreads = Math.subtractExact(this.nativeThreads, other.nativeThreads),
            fileDescriptors = Math.subtractExact(this.fileDescriptors, other.fileDescriptors),
            temporaryDiskBytes = Math.subtractExact(this.temporaryDiskBytes, other.temporaryDiskBytes),
            networkBytesInFlight = Math.subtractExact(this.networkBytesInFlight, other.networkBytesInFlight),
    )

    /** True when every dimension of this vector is >= [other]. */
    fun dominates(other: ResourceVector): Boolean =
        this.cpuAnonBytes >= other.cpuAnonBytes &&
            this.cpuFileBytes >= other.cpuFileBytes &&
            this.sharedMemoryChargeBytes >= other.sharedMemoryChargeBytes &&
            this.gpuDedicatedBytes >= other.gpuDedicatedBytes &&
            this.gpuSharedBytes >= other.gpuSharedBytes &&
            this.npuBytes >= other.npuBytes &&
            this.nativeThreads >= other.nativeThreads &&
            this.fileDescriptors >= other.fileDescriptors &&
            this.temporaryDiskBytes >= other.temporaryDiskBytes &&
            this.networkBytesInFlight >= other.networkBytesInFlight

    companion object {
        val ZERO: ResourceVector = ResourceVector(
            cpuAnonBytes = 0L,
            cpuFileBytes = 0L,
            sharedMemoryChargeBytes = 0L,
            gpuDedicatedBytes = 0L,
            gpuSharedBytes = 0L,
            npuBytes = 0L,
            nativeThreads = 0L,
            fileDescriptors = 0L,
            temporaryDiskBytes = 0L,
            networkBytesInFlight = 0L,
        )

        /** Catalog dimension names in declared order. */
        val DIMENSION_NAMES: List<String> = listOf("cpuAnonBytes", "cpuFileBytes", "sharedMemoryChargeBytes", "gpuDedicatedBytes", "gpuSharedBytes", "npuBytes", "nativeThreads", "fileDescriptors", "temporaryDiskBytes", "networkBytesInFlight")
    }
}

/**
 * ResourceEnvelope: steady is resident after commit; peak is max during the op.
 * Each peak dimension MUST be >= steady.
 */
data class ResourceEnvelope(
    val steady: ResourceVector,
    val peak: ResourceVector,
) {
    init {
        require(peak.dominates(steady)) {
            "ResourceEnvelope.peak must dominate steady on every dimension"
        }
    }
}
