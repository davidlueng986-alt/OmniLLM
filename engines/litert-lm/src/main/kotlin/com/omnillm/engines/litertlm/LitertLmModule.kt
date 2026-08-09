package com.omnillm.engines.litertlm

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.litertlm.lock.UpstreamLock
import com.omnillm.engines.litertlm.lock.UpstreamLockLoader
import com.omnillm.engines.litertlm.sdk.SdkBackend
import com.omnillm.engines.litertlm.sdk.SdkBackendFactory

/**
 * Module `:engines:litert-lm` — LiteRT-LM Engine Pack (ENGINE-LITERT).
 *
 * Integration shape: official SDK / AAR Engine/Conversation adapter via [SdkBackend].
 * Do not assume traditional JNI exposes every handle (ENGINE-LITERT §2).
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return SDK objects / native pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Incomplete UPSTREAM.lock ⇒ exploratory only; Registry stays UNKNOWN
 * - Design BASELINE ≠ runtime SUPPORTED
 * - Production uses [RealSdkBackend]; host tests use [StubSdkBackend] explicitly
 */
object LitertLmModule {
    const val MODULE_PATH: String = ":engines:litert-lm"
    /** Catalog engineId from specs/engine-qualification-status.yaml */
    const val ENGINE_ID: String = "LiteRT-LM"

    /** Placeholder build id until a complete lock produces a real artifact digest. */
    const val DEFAULT_ENGINE_BUILD_ID: String = "litert-lm-not-locked"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = EngineQualificationCellStatus.UNQUALIFIED

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Same build-id resolution as [registerWith]'s default: the classpath UPSTREAM.lock
     * when it pins an engineBuildId, else the placeholder. Keeps registration and
     * seeded cells on the same engineBuildId (INV-018 catalog completeness).
     */
    private fun resolvedDefaultEngineBuildId(): EngineBuildId =
        UpstreamLockLoader.loadFromClasspathOrTemplate().resolvedEngineBuildId()
            ?: defaultEngineBuildId()

    /**
     * Factory for the Kotlin adapter.
     *
     * Default [SdkBackend] is [StubSdkBackend] for **host unit tests** only.
     * Runtime / production must use [createProductionEngine] ([RealSdkBackend])
     * and must not silently substitute the stub when the AAR is missing.
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        backend: SdkBackend = SdkBackendFactory.forHostUnitTests(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
    ): LitertLmEngine =
        LitertLmEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            sdk = backend,
        )

    /**
     * Production factory: always binds [RealSdkBackend] (fail-closed when AAR
     * absent). Exploratory execute requires complete [lock] **and**
     * [forceExploratory] — still never marks Registry cells SUPPORTED.
     */
    fun createProductionEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        forceExploratory: Boolean = false,
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
    ): LitertLmEngine =
        LitertLmEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            sdk = SdkBackendFactory.forProduction(
                lock = lock,
                forceExploratory = forceExploratory,
            ),
        )

    /** Whether the official AAR Engine class is loadable (not a SUPPORTED claim). */
    fun isOfficialSdkOnClasspath(): Boolean =
        SdkBackendFactory.isOfficialSdkOnClasspath()

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
     * The default [engineBuildId] resolves from the classpath UPSTREAM.lock exactly like
     * [registerWith] — cells must live under the **registered** build id, otherwise the
     * catalog appears empty for the engine (INV-018: cells exist, status UNKNOWN, never
     * silently absent). Callers with an explicit lock should pass the same build id to
     * both [registerWith] and this function.
     *
     * Projected runtime capability remains [com.omnillm.core.canonical.generated.CapabilityState.UNKNOWN].
     * Never invents SUPPORTED.
     */
    fun seedUnqualifiedPlaceholders(
        registry: EngineRegistry,
        deviceFingerprint: DeviceExecutionFingerprint,
        engineBuildId: EngineBuildId = resolvedDefaultEngineBuildId(),
        driverFingerprint: String = "unknown-driver",
        platform: String = "android",
        modelEnvelope: String = "*",
        workloadEnvelope: String = "default",
    ): List<EngineQualificationCell> {
        val cells = mutableListOf<EngineQualificationCell>()
        // CPU × all required phases
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
        // Accelerator backends — explicit UNKNOWN rows (no silent inheritance)
        for (backend in listOf("gpu", "npu")) {
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
