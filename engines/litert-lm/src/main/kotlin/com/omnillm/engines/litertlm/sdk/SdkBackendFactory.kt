package com.omnillm.engines.litertlm.sdk

import com.omnillm.engines.litertlm.lock.UpstreamLock

/**
 * Factory for [SdkBackend] selection (ENGINE-LITERT §2).
 *
 * Production rules (INV-018):
 * - Prefer [RealSdkBackend] when the official AAR is on the classpath.
 * - Never silently substitute [StubSdkBackend] for a missing AAR in production.
 * - Incomplete [UpstreamLock] keeps exploratory execute **off** unless an
 *   explicit policy flag is set (still does not mark Registry SUPPORTED).
 *
 * Host unit tests must call [forHostUnitTests] explicitly.
 */
object SdkBackendFactory {

    /**
     * Production / runtime-service path.
     *
     * Returns [RealSdkBackend] always (fail-closed when AAR absent). Exploratory
     * execute is enabled only when [lock] is complete **and** [forceExploratory]
     * is true (policy-gated CONDITIONAL exploratory — still UNQUALIFIED cells).
     */
    fun forProduction(
        lock: UpstreamLock,
        forceExploratory: Boolean = false,
        bridge: LitertLmSdkBridge = ReflectiveLitertLmSdkBridge.detect(),
    ): RealSdkBackend {
        val allow = forceExploratory && lock.isComplete()
        return RealSdkBackend.create(
            allowExploratoryExecute = allow,
            bridge = bridge,
        )
    }

    /**
     * Host JVM unit tests only — dry-run stub, no AAR required.
     * Must not be used as a silent production fallback.
     */
    fun forHostUnitTests(
        deltaCount: Int = 2,
        embeddingsEnabled: Boolean = false,
        exploratoryDryRun: Boolean = true,
    ): StubSdkBackend =
        StubSdkBackend(
            deltaCount = deltaCount,
            embeddingsEnabled = embeddingsEnabled,
            exploratoryDryRun = exploratoryDryRun,
        )

    /**
     * Diagnostics: whether the official Engine class is loadable.
     * Does **not** imply SUPPORTED or lock completeness.
     */
    fun isOfficialSdkOnClasspath(): Boolean =
        ReflectiveLitertLmSdkBridge.detect().isPresent()
}
