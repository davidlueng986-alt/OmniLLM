package com.omnillm.engines.api

/**
 * Engine qualification labels from `specs/engine-qualification-schema.yaml`
 * and runtime exposure rules from `specs/engine-qualification-status.yaml`.
 *
 * Stored as string constants (not invented Kotlin enums) so wire/DB and
 * catalog YAML stay aligned (rule: do not invent types absent from specs).
 *
 * Registry rule (ENGINE-QUALIFICATION-STATUS §2):
 * only [QUALIFIED_WITH_ENVELOPE] cells may project as supported;
 * default runtime capability is UNKNOWN.
 */
object EngineQualificationCellStatus {
    const val UNQUALIFIED: String = "UNQUALIFIED"
    const val STATIC_REVIEWED: String = "STATIC_REVIEWED"
    const val SPIKE_REQUIRED: String = "SPIKE_REQUIRED"
    const val QUALIFIED_WITH_ENVELOPE: String = "QUALIFIED_WITH_ENVELOPE"
    const val SUSPENDED: String = "SUSPENDED"

    val ALL: Set<String> = setOf(
        UNQUALIFIED,
        STATIC_REVIEWED,
        SPIKE_REQUIRED,
        QUALIFIED_WITH_ENVELOPE,
        SUSPENDED,
    )

    fun isKnown(status: String): Boolean = status in ALL
}

/**
 * Required lifecycle phases for phase-capability cells
 * (`specs/engine-qualification-schema.yaml#requiredPhases`).
 */
object EnginePhases {
    const val PROBE: String = "PROBE"
    const val LOAD: String = "LOAD"
    const val CREATE_SESSION: String = "CREATE_SESSION"
    const val PLAN: String = "PLAN"
    const val COMMIT: String = "COMMIT"
    const val START: String = "START"
    const val GENERATE: String = "GENERATE"
    const val CLOSE: String = "CLOSE"
    const val UNLOAD: String = "UNLOAD"

    val REQUIRED: Set<String> = setOf(
        PROBE,
        LOAD,
        CREATE_SESSION,
        PLAN,
        COMMIT,
        START,
        GENERATE,
        CLOSE,
        UNLOAD,
    )

    fun isKnown(phase: String): Boolean = phase in REQUIRED
}

/**
 * Cancellation modes (`specs/engine-qualification-schema.yaml#cancellationModes`).
 * UNKNOWN cancellation is worker-only (failClosedRules).
 */
object CancellationModes {
    const val COOPERATIVE: String = "COOPERATIVE"
    const val INTERRUPTIBLE: String = "INTERRUPTIBLE"
    const val WORKER_KILL_ONLY: String = "WORKER_KILL_ONLY"
    const val NOT_SUPPORTED: String = "NOT_SUPPORTED"
    const val UNKNOWN: String = "UNKNOWN"

    val ALL: Set<String> = setOf(
        COOPERATIVE,
        INTERRUPTIBLE,
        WORKER_KILL_ONLY,
        NOT_SUPPORTED,
        UNKNOWN,
    )

    fun isKnown(mode: String): Boolean = mode in ALL

    /** Fail-closed: UNKNOWN is never treated as privileged-safe. */
    fun isPrivilegedSafe(mode: String): Boolean =
        mode == COOPERATIVE || mode == INTERRUPTIBLE
}

/**
 * Evidence status values (`specs/engine-qualification-schema.yaml#evidenceStatusValues`).
 * Default is [NOT_EXECUTED]. Distinct from cell qualification status.
 */
object EvidenceStatusLabels {
    const val NOT_EXECUTED: String = "NOT_EXECUTED"
    const val PASS: String = "PASS"
    const val FAIL: String = "FAIL"
    const val EXPIRED: String = "EXPIRED"
    const val INVALIDATED: String = "INVALIDATED"

    val ALL: Set<String> = setOf(
        NOT_EXECUTED,
        PASS,
        FAIL,
        EXPIRED,
        INVALIDATED,
    )

    const val DEFAULT: String = NOT_EXECUTED

    fun isKnown(status: String): Boolean = status in ALL
}
