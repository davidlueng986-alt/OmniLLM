package com.omnillm.engines.mllm

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.mllm.lock.UpstreamLock
import com.omnillm.engines.mllm.lock.UpstreamLockLoader
import com.omnillm.engines.mllm.qualification.QualificationCells
import com.omnillm.engines.mllm.server.ServerBackend
import com.omnillm.engines.mllm.server.StubServerBackend

/**
 * Module `:engines:mllm` — Engine Pack (ENGINE-MLLM).
 *
 * Integration shape: device-local client-server / Go `mllm_server.aar` adapter.
 * Embedded server is private; Adapter owns lifecycle, private channel, runtime
 * credential, and canonical translation (ENGINE-MLLM §2).
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return native / server pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Incomplete UPSTREAM.lock ⇒ exploratory only; Registry stays UNKNOWN
 * - Design BASELINE ≠ runtime SUPPORTED
 * - Missing binary / AAR ⇒ fail closed (never load-as-success)
 */
object MllmModule {
    const val MODULE_PATH: String = ":engines:mllm"
    const val ENGINE_ID: String = "mllm"

    /** Placeholder build id until a complete lock produces a real artifact digest. */
    const val DEFAULT_ENGINE_BUILD_ID: String = "mllm-not-locked"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = "UNQUALIFIED"
    const val REGISTRY_EXPOSURE: String = "UNKNOWN"

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Factory for the Kotlin adapter. Default [ServerBackend] is [StubServerBackend]
     * with exploratory dry-run **off** (unproven ops → CAPABILITY_UNKNOWN).
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        server: ServerBackend = StubServerBackend(
            lockComplete = lock.isComplete(),
            exploratoryDryRun = false,
        ),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        /**
         * When false (default), commit/execute paths map unproven operations to
         * CAPABILITY_UNKNOWN even if a backend would otherwise succeed.
         * Tests that need dry-run plumbing set this true **and** use exploratory stub.
         */
        allowUnprovenExecution: Boolean = false,
    ): MllmEngine =
        MllmEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            server = server,
            allowUnprovenExecution = allowUnprovenExecution,
        )

    /**
     * Register build metadata with [EngineRegistry] and optionally seed UNQUALIFIED cells.
     * Does **not** mark any cell SUPPORTED — only PASS + QUALIFIED_WITH_ENVELOPE
     * evidence may project SUPPORTED later.
     *
     * @return registration metadata (designStatus BASELINE; upstreamLocked false
     *   when lock template is incomplete)
     */
    fun registerWith(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        seedCells: Boolean = true,
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = QualificationCells.DRIVER_PLACEHOLDER,
    ): EngineRegistration {
        val reg = EngineRegistration(
            engineBuildId = engineBuildId,
            engineId = ENGINE_ID,
            upstreamCommitOrTag = lock.upstreamCommitOrTag(),
            patchSetDigest = lock.patchDigest.orEmpty(),
            toolchainDigest = lock.toolchainDigest.orEmpty(),
            artifactDigest = lock.artifactDigest.orEmpty(),
            designStatus = DESIGN_STATUS,
        )
        registry.register(reg)
        if (seedCells) {
            seedUnqualifiedPlaceholders(
                registry = registry,
                engineBuildId = engineBuildId,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = driverFingerprint,
            )
        }
        return reg
    }

    /**
     * Seed capability-matrix UNQUALIFIED / NOT_EXECUTED cells.
     * Used by [com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment].
     */
    fun seedUnqualifiedPlaceholders(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = QualificationCells.DRIVER_PLACEHOLDER,
    ): Int = QualificationCells.seedUnqualified(
        registry = registry,
        engineBuildId = engineBuildId,
        deviceFingerprint = deviceFingerprint,
        driverFingerprint = driverFingerprint,
    )
}
