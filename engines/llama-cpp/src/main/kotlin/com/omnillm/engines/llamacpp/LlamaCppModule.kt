package com.omnillm.engines.llamacpp

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.llamacpp.lock.UpstreamLock
import com.omnillm.engines.llamacpp.lock.UpstreamLockLoader
import com.omnillm.engines.llamacpp.native.JniNativeBackend
import com.omnillm.engines.llamacpp.native.NativeBackend
import com.omnillm.engines.llamacpp.native.StubNativeBackend

/**
 * Module `:engines:llama-cpp` — first production engine path (ENGINE-LLAMACPP).
 *
 * Integration shape: C/C++ library adapter via [NativeBackend] (JNI).
 * Upstream HTTP server is **not** the core interface.
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return native pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Incomplete UPSTREAM.lock ⇒ exploratory only; Registry stays UNKNOWN
 * - Production uses [JniNativeBackend]; [StubNativeBackend] is for unit tests only
 */
object LlamaCppModule {
    const val MODULE_PATH: String = ":engines:llama-cpp"
    const val ENGINE_ID: String = "llama.cpp"

    /** Placeholder build id until a complete lock produces a real artifact digest. */
    const val DEFAULT_ENGINE_BUILD_ID: String = "llama-cpp-not-locked"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = EngineQualificationCellStatus.UNQUALIFIED

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Factory for the Kotlin adapter. Default [NativeBackend] is [StubNativeBackend]
     * for host unit tests. Production must call [createEngineWithNativeOrNull].
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        backend: NativeBackend = StubNativeBackend(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
    ): LlamaCppEngine =
        LlamaCppEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            native = backend,
        )

    /**
     * Production factory: loads [JniNativeBackend] when `libomnillm_llama` is present.
     * Returns null when the library is missing or ABI mismatches — **fail closed**
     * (do not substitute [StubNativeBackend]).
     */
    fun createEngineWithNativeOrNull(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
    ): LlamaCppEngine? {
        val backend = JniNativeBackend.createOrNull() ?: return null
        return createEngine(lock = lock, backend = backend, engineBuildId = engineBuildId)
    }

    /**
     * Register build metadata with [EngineRegistry]. Does **not** mark any cell
     * SUPPORTED — evidence cells must be put separately with PASS records.
     */
    fun registerWith(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
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
        return reg
    }

    /**
     * Seed placeholder qualification cells from the design matrix.
     * All cells are [EngineQualificationCellStatus.UNQUALIFIED] with
     * [EvidenceStatusLabels.NOT_EXECUTED] and [CancellationModes.UNKNOWN].
     *
     * Projected runtime capability remains UNKNOWN. Never invents SUPPORTED.
     */
    fun seedUnqualifiedPlaceholders(
        registry: EngineRegistry,
        deviceFingerprint: DeviceExecutionFingerprint,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
        driverFingerprint: String = "unknown-driver",
        platform: String = "android",
        modelEnvelope: String = "*",
        workloadEnvelope: String = "default",
    ): List<EngineQualificationCell> {
        val cells = mutableListOf<EngineQualificationCell>()
        for (phase in EnginePhases.REQUIRED) {
            cells += placeholderCell(
                engineBuildId = engineBuildId,
                backend = "cpu",
                phase = phase,
                platform = platform,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = driverFingerprint,
                modelEnvelope = modelEnvelope,
                workloadEnvelope = workloadEnvelope,
            )
        }
        for (backend in listOf("vulkan", "opencl")) {
            for (phase in listOf(EnginePhases.LOAD, EnginePhases.GENERATE)) {
                cells += placeholderCell(
                    engineBuildId = engineBuildId,
                    backend = backend,
                    phase = phase,
                    platform = platform,
                    deviceFingerprint = deviceFingerprint,
                    driverFingerprint = driverFingerprint,
                    modelEnvelope = modelEnvelope,
                    workloadEnvelope = workloadEnvelope,
                )
            }
        }
        cells.forEach { registry.putCell(it) }
        return cells
    }

    private fun placeholderCell(
        engineBuildId: EngineBuildId,
        backend: String,
        phase: String,
        platform: String,
        deviceFingerprint: DeviceExecutionFingerprint,
        driverFingerprint: String,
        modelEnvelope: String,
        workloadEnvelope: String,
    ): EngineQualificationCell =
        EngineQualificationCell(
            engineBuildId = engineBuildId,
            backend = backend,
            phase = phase,
            platform = platform,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
            modelEnvelope = modelEnvelope,
            workloadEnvelope = workloadEnvelope,
            qualificationStatus = EngineQualificationCellStatus.UNQUALIFIED,
            evidenceStatus = EvidenceStatusLabels.NOT_EXECUTED,
            cancellationMode = CancellationModes.UNKNOWN,
            notes = mapOf(
                "engineId" to ENGINE_ID,
                "designStatus" to DESIGN_STATUS,
                "source" to "capability-matrix.yaml placeholder",
            ),
        )
}
