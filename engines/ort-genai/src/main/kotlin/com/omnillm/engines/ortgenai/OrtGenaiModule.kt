package com.omnillm.engines.ortgenai

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.ortgenai.lock.UpstreamLock
import com.omnillm.engines.ortgenai.lock.UpstreamLockLoader
import com.omnillm.engines.ortgenai.session.GenAiBackend
import com.omnillm.engines.ortgenai.session.GenAiBackendFactory

/**
 * Module `:engines:ort-genai` — ONNX Runtime GenAI pack (ENGINE-ORTGENAI).
 *
 * Integration shape: official GenAI Java API from the pinned Android AAR
 * (v0.14.0), direct JVM access (no OmniLLM JNI bridge). Design status is
 * BASELINE; runtime remains UNQUALIFIED / UNKNOWN until device evidence cells
 * PASS — a pinned, real backend is a supply-chain + integration fact, not a
 * SUPPORTED claim.
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return native pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Missing natives must never report load success
 * - Do **not** claim SUPPORTED without QUALIFIED_WITH_ENVELOPE + PASS evidence
 */
object OrtGenaiModule {
    const val MODULE_PATH: String = ":engines:ort-genai"

    /** Matches `specs/engine-qualification-status.yaml` engineId. */
    const val ENGINE_ID: String = "ONNX-Runtime-GenAI"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = "UNQUALIFIED"
    const val REGISTRY_EXPOSURE: String = "UNKNOWN"

    /** Fallback build id when the lock has no artifact build id (template only). */
    const val DEFAULT_ENGINE_BUILD_ID: String = "ort-genai-not-locked"

    /** Placeholder device/driver used only when seeding matrix rows without a real device. */
    const val PLACEHOLDER_DEVICE_FP: String = "unqualified-device-placeholder"
    const val PLACEHOLDER_DRIVER_FP: String = "unqualified-driver-placeholder"

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Factory for the Kotlin session adapter. Default [GenAiBackend] is the
     * **real** ONNX Runtime GenAI backend (fail-closed: natives absent or
     * execute unproven ⇒ NOT_AVAILABLE / CAPABILITY_UNKNOWN). Pass
     * [GenAiBackendFactory.forHostUnitTests] explicitly for unit tests that
     * must not touch native loading.
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        backend: GenAiBackend? = null,
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        /**
         * When false (default), commit/execute paths map unproven operations to
         * CAPABILITY_UNKNOWN even if a backend would otherwise succeed.
         * Control plane opts in only with a complete lock + exploratory policy.
         */
        allowUnprovenExecution: Boolean = false,
        measuredPhaseCancellation: Map<String, String> = emptyMap(),
    ): OrtGenaiEngine =
        OrtGenaiEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            backend = backend ?: GenAiBackendFactory.create(
                allowExploratoryExecute = allowUnprovenExecution,
            ),
            allowUnprovenExecution = allowUnprovenExecution,
            measuredPhaseCancellation = measuredPhaseCancellation,
        )

    /**
     * Register build metadata with [EngineRegistry]. Does **not** mark any cell
     * SUPPORTED — evidence cells must be put separately with PASS records.
     * Design-complete (`BASELINE`) remains independent of qualification.
     */
    fun registerWith(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        seedPlaceholderCells: Boolean = false,
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

    /**
     * Seed UNQUALIFIED / NOT_EXECUTED placeholder cells from the design matrix.
     * All projected capabilities remain [com.omnillm.core.canonical.generated.CapabilityState.UNKNOWN].
     *
     * [deviceFingerprint] / [driverFingerprint] should be real fingerprints in
     * production seeding; placeholders are for offline pack wiring only and never
     * elevate trust.
     */
    fun seedUnqualifiedCells(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
        deviceFingerprint: DeviceExecutionFingerprint =
            DeviceExecutionFingerprint.parse(PLACEHOLDER_DEVICE_FP),
        driverFingerprint: String = PLACEHOLDER_DRIVER_FP,
        platform: String = "android",
    ): List<EngineQualificationCell> {
        val rows = placeholderCellSpecs()
        val seeded = rows.map { row ->
            EngineQualificationCell(
                engineBuildId = engineBuildId,
                backend = row.backend,
                phase = row.phase,
                platform = platform,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = driverFingerprint,
                modelEnvelope = row.modelEnvelope,
                workloadEnvelope = row.workloadEnvelope,
                qualificationStatus = EngineQualificationCellStatus.UNQUALIFIED,
                evidenceStatus = EvidenceStatusLabels.NOT_EXECUTED,
                cancellationMode = CancellationModes.UNKNOWN,
                notes = mapOf(
                    "engineId" to ENGINE_ID,
                    "designStatus" to DESIGN_STATUS,
                    "source" to "capability-matrix.yaml",
                    "claim" to "UNQUALIFIED_PLACEHOLDER",
                ),
            )
        }
        seeded.forEach { registry.putCell(it) }
        return seeded
    }

    /**
     * Register + seed in one call. Runtime exposure stays UNKNOWN.
     * Used by [com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment].
     */
    fun registerDesignCompleteUnqualified(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        deviceFingerprint: DeviceExecutionFingerprint =
            DeviceExecutionFingerprint.parse(PLACEHOLDER_DEVICE_FP),
        driverFingerprint: String = PLACEHOLDER_DRIVER_FP,
    ): EngineRegistration {
        val reg = registerWith(
            registry = registry,
            lock = lock,
            engineBuildId = engineBuildId,
            seedPlaceholderCells = false,
        )
        seedUnqualifiedCells(
            registry = registry,
            engineBuildId = engineBuildId,
            deviceFingerprint = deviceFingerprint,
            driverFingerprint = driverFingerprint,
        )
        return reg
    }

    /**
     * Static placeholder rows aligned with capability-matrix.yaml `cells:`.
     * Kept in code so seeding has no YAML dependency at runtime.
     * Provider matrix (cpu / nnapi / qnn) stays UNQUALIFIED.
     */
    fun placeholderCellSpecs(): List<CellSpec> = listOf(
        CellSpec("cpu", EnginePhases.PROBE),
        CellSpec("cpu", EnginePhases.LOAD),
        CellSpec("cpu", EnginePhases.CREATE_SESSION),
        CellSpec("cpu", EnginePhases.PLAN),
        CellSpec("cpu", EnginePhases.COMMIT),
        CellSpec("cpu", EnginePhases.START),
        CellSpec("cpu", EnginePhases.GENERATE),
        CellSpec("cpu", EnginePhases.CLOSE),
        CellSpec("cpu", EnginePhases.UNLOAD),
        CellSpec("nnapi", EnginePhases.LOAD),
        CellSpec("nnapi", EnginePhases.GENERATE),
        CellSpec("qnn", EnginePhases.LOAD),
        CellSpec("qnn", EnginePhases.GENERATE),
    )

    data class CellSpec(
        val backend: String,
        val phase: String,
        val modelEnvelope: String = "*",
        val workloadEnvelope: String = "default",
    )
}
