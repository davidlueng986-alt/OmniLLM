package com.omnillm.engines.ortgenai.mapping

import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases

/**
 * Phase → cancellation mode mapping for ONNX Runtime GenAI (ENGINE-ORTGENAI §6).
 *
 * Design **expectations** vs **measured** modes are distinct:
 * - [expectedModes] document cooperative token gen vs kill-only load/close
 *   (provider init / graph opt / compile-cache sit in LOAD).
 * - [measuredModes] stay [CancellationModes.UNKNOWN] until cell evidence exists.
 *
 * UNKNOWN cancellation ⇒ full LoadedModel lifecycle runs only on a killable
 * worker; kill invalidates all sessions on that worker (no handle reuse).
 * Provider without bounded cooperative cancel ⇒ same rule.
 */
object PhaseCancellationMap {

    /**
     * Design-time expected modes (not runtime authority).
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

    fun requiresKillableWorker(mode: String): Boolean =
        !CancellationModes.isPrivilegedSafe(mode)

    fun anyPhaseRequiresKillableWorker(
        measured: Map<String, String> = measuredModes(),
    ): Boolean = EnginePhases.REQUIRED.any { phase ->
        requiresKillableWorker(modeForPhase(phase, measured))
    }
}
