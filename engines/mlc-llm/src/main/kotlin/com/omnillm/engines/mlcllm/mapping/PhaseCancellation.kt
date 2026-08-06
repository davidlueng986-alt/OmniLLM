package com.omnillm.engines.mlcllm.mapping

import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases

/**
 * Phase → cancellation mode mapping for MLC-LLM (ENGINE-MLC §5).
 *
 * Design **expectations** vs **measured** modes are distinct:
 * - [expectedModes] document cooperative decode vs kill-only load/close.
 * - [measuredModes] stay [CancellationModes.UNKNOWN] until cell evidence exists.
 *
 * UNKNOWN cancellation ⇒ full LoadedModel lifecycle runs only on a killable
 * worker; GPU command kill requires driver/resource barrier observation.
 */
object PhaseCancellationMap {

    /**
     * Design-time expected modes (not runtime authority).
     * LOAD/CLOSE/UNLOAD may need kill; GENERATE expected cooperative when API allows.
     */
    val expectedModes: Map<String, String> = mapOf(
        EnginePhases.PROBE to CancellationModes.COOPERATIVE,
        EnginePhases.LOAD to CancellationModes.WORKER_KILL_ONLY,
        EnginePhases.CREATE_SESSION to CancellationModes.COOPERATIVE,
        EnginePhases.PLAN to CancellationModes.COOPERATIVE,
        EnginePhases.COMMIT to CancellationModes.COOPERATIVE,
        EnginePhases.START to CancellationModes.INTERRUPTIBLE,
        EnginePhases.GENERATE to CancellationModes.COOPERATIVE,
        EnginePhases.CLOSE to CancellationModes.WORKER_KILL_ONLY,
        EnginePhases.UNLOAD to CancellationModes.WORKER_KILL_ONLY,
    )

    /**
     * Measured modes published on [com.omnillm.engines.api.EngineDescriptor].
     * Default UNKNOWN until qualification measures max non-preemptible windows.
     */
    fun measuredModes(
        overrides: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val base = EnginePhases.REQUIRED.associateWith { CancellationModes.UNKNOWN }
        return base + overrides.filterKeys { EnginePhases.isKnown(it) }
            .filterValues { CancellationModes.isKnown(it) }
    }

    fun modeForPhase(
        phase: String,
        measured: Map<String, String> = measuredModes(),
    ): String {
        require(EnginePhases.isKnown(phase)) { "unknown phase (fail closed): $phase" }
        return measured[phase] ?: CancellationModes.UNKNOWN
    }

    /**
     * Privileged placement is only safe for COOPERATIVE / INTERRUPTIBLE phases.
     * UNKNOWN / WORKER_KILL_ONLY / NOT_SUPPORTED ⇒ require worker placement.
     */
    fun requiresKillableWorker(mode: String): Boolean =
        !CancellationModes.isPrivilegedSafe(mode)

    fun anyPhaseRequiresKillableWorker(
        measured: Map<String, String> = measuredModes(),
    ): Boolean = EnginePhases.REQUIRED.any { phase ->
        requiresKillableWorker(modeForPhase(phase, measured))
    }
}
