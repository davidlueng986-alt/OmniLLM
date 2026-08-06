package com.omnillm.engines.litertlm.mapping

import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EnginePhases

/**
 * Phase → cancellation mode mapping for LiteRT-LM (ENGINE-LITERT §6).
 *
 * Design **expectations** vs **measured** modes are distinct:
 * - [expectedModes] document intended cooperative vs kill-only phases.
 * - [measuredModes] stay [CancellationModes.UNKNOWN] until cell evidence exists.
 *
 * SDK cancellation that only stops future output ≠ native execution stopped.
 * UNKNOWN cancellation ⇒ full LoadedModel lifecycle runs only on a killable
 * worker; kill invalidates all Engine/Conversation objects (no handle reuse).
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
