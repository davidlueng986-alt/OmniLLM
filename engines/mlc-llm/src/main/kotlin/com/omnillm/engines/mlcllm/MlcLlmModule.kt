package com.omnillm.engines.mlcllm

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.mlcllm.lock.UpstreamLock
import com.omnillm.engines.mlcllm.lock.UpstreamLockLoader
import com.omnillm.engines.mlcllm.runtime.MlcEngineRuntimeBackend
import com.omnillm.engines.mlcllm.runtime.RuntimeBackend
import com.omnillm.engines.mlcllm.runtime.StubRuntimeBackend

/**
 * Module `:engines:mlc-llm` — MLC-LLM Engine Pack (ENGINE-MLC).
 *
 * Integration shape: compiler + generated model library + runtime adapter.
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return native pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Incomplete UPSTREAM.lock ⇒ exploratory only; Registry stays UNKNOWN
 * - designStatus BASELINE ≠ runtime SUPPORTED (ENGINE-QUALIFICATION-STATUS)
 * - Production uses [MlcEngineRuntimeBackend]; [StubRuntimeBackend] is for unit
 *   tests only (fail closed — never substitute a stub silently, INV-018).
 */
object MlcLlmModule {
    const val MODULE_PATH: String = ":engines:mlc-llm"
    const val ENGINE_ID: String = "MLC-LLM"

    /** Placeholder build id until a complete lock produces a real artifact digest. */
    const val DEFAULT_ENGINE_BUILD_ID: String = "mlc-llm-not-locked"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = EngineQualificationCellStatus.UNQUALIFIED

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Factory for the Kotlin adapter. Default [RuntimeBackend] is [StubRuntimeBackend]
     * with exploratory dry-run **off** (unproven ops → CAPABILITY_UNKNOWN) — host
     * tests only. Production must call [createEngineWithRuntimeOrNull].
     *
     * [com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment]
     * registers metadata only and does **not** attach a live exploratory engine.
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        backend: RuntimeBackend = StubRuntimeBackend(exploratoryDryRun = false),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        measuredPhaseCancellation: Map<String, String> = emptyMap(),
    ): MlcLlmEngine =
        MlcLlmEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            runtime = backend,
            measuredPhaseCancellation = measuredPhaseCancellation,
        )

    /**
     * Production factory: binds the official generated MLC-LLM Android runtime
     * (`ai.mlc.mlcllm.MLCEngine` from `mlc_llm package`; see [MlcRuntimeBridge]).
     * Returns null when the runtime is absent from the classpath or the pinned
     * API surface mismatches — **fail closed** (do not substitute
     * [StubRuntimeBackend]).
     */
    fun createEngineWithRuntimeOrNull(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        measuredPhaseCancellation: Map<String, String> = emptyMap(),
    ): MlcLlmEngine? {
        val backend = MlcEngineRuntimeBackend.createOrNull() ?: return null
        return createEngine(
            lock = lock,
            backend = backend,
            engineBuildId = engineBuildId,
            measuredPhaseCancellation = measuredPhaseCancellation,
        )
    }

    /**
     * Register build metadata with [EngineRegistry]. Does **not** mark any cell
     * SUPPORTED — evidence cells must be put separately with PASS records.
     *
     * @param seedPlaceholderCells when true (default), installs UNQUALIFIED matrix rows
     *   with the design placeholder device fingerprint. Prefer
     *   [seedUnqualifiedPlaceholders] when a real [DeviceExecutionFingerprint] is known
     *   (EnginePackAttachment path).
     */
    fun registerWith(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        seedPlaceholderCells: Boolean = true,
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
        if (seedPlaceholderCells) {
            seedUnqualifiedCells(registry, engineBuildId)
        }
        return reg
    }

    /** Seed capability-matrix UNQUALIFIED / NOT_EXECUTED cells (design placeholder fp). */
    fun seedUnqualifiedCells(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
    ): Int = QualificationCells.seedUnqualified(registry, engineBuildId)

    /**
     * Seed UNQUALIFIED placeholders with an explicit device fingerprint
     * (used by EnginePackAttachment). Never invents SUPPORTED.
     */
    fun seedUnqualifiedPlaceholders(
        registry: EngineRegistry,
        deviceFingerprint: DeviceExecutionFingerprint,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
        driverFingerprint: String = QualificationCells.PLACEHOLDER_DRIVER_FP,
    ): List<EngineQualificationCell> {
        val cells = QualificationCells.buildPlaceholders(
            engineBuildId = engineBuildId,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
        )
        cells.forEach { registry.putCell(it) }
        return cells
    }
}
