package com.omnillm.features.diagnostics.domain

/**
 * Feature-scoped journey / screen phase labels for FEAT-DIAGNOSTICS.
 *
 * Not a second domain FSM — durable state remains JOB (and optional Asset for
 * the sealed bundle) from `specs/state-machines.yaml`. These labels only
 * organize empty / loading / error / degraded / content UX (UX-STATE-CATALOG,
 * FEATURE-SYSTEM §2).
 */
object DiagnosticUiPhases {
    /** No exports yet — empty list / invite to create. */
    const val EMPTY: String = "EMPTY"

    /** Preview categories / sensitivity / size before claim. */
    const val PREVIEW: String = "PREVIEW"

    /** Job claimed / collecting or compressing (JOB non-terminal). */
    const val LOADING: String = "LOADING"

    /** Sealed READY bundle available for share/delete. */
    const val CONTENT: String = "CONTENT"

    /** Service / evidence partially available — still show what we know. */
    const val DEGRADED: String = "DEGRADED"

    /** Terminal failure of the active export. */
    const val ERROR: String = "ERROR"

    /** User/runtime cancelled the export. */
    const val CANCELLED: String = "CANCELLED"

    val ALL: Set<String> = setOf(
        EMPTY,
        PREVIEW,
        LOADING,
        CONTENT,
        DEGRADED,
        ERROR,
        CANCELLED,
    )

    fun isKnown(phase: String): Boolean = phase in ALL

    fun isTerminal(phase: String): Boolean =
        phase == CONTENT || phase == ERROR || phase == CANCELLED || phase == EMPTY
}

/**
 * Bundle readiness projection (FEAT-DIAGNOSTICS §4).
 * Partial collection must never project as READY.
 */
object DiagnosticBundleStates {
    const val DRAFT: String = "DRAFT"
    const val COLLECTING: String = "COLLECTING"
    const val SEALING: String = "SEALING"
    const val READY: String = "READY"
    const val FAILED: String = "FAILED"
    const val CANCELLED: String = "CANCELLED"
    const val EXPIRED: String = "EXPIRED"
    const val DELETED: String = "DELETED"

    val ALL: Set<String> = setOf(
        DRAFT,
        COLLECTING,
        SEALING,
        READY,
        FAILED,
        CANCELLED,
        EXPIRED,
        DELETED,
    )

    fun isKnown(state: String): Boolean = state in ALL

    fun isShareable(state: String): Boolean = state == READY

    fun isTerminal(state: String): Boolean =
        state == READY || state == FAILED || state == CANCELLED ||
            state == EXPIRED || state == DELETED
}
