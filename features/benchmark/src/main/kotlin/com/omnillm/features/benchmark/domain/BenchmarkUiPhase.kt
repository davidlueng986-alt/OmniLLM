package com.omnillm.features.benchmark.domain

/**
 * Feature UI phases (UX-STATE-CATALOG empty / loading / error / degraded / content).
 * Local string constants — not JOB FSM states.
 */
object BenchmarkUiPhases {
    const val EMPTY: String = "EMPTY"
    const val PREVIEW: String = "PREVIEW"
    const val LOADING: String = "LOADING"
    const val CONTENT: String = "CONTENT"
    const val DEGRADED: String = "DEGRADED"
    const val ERROR: String = "ERROR"
    const val CANCELLED: String = "CANCELLED"

    private val KNOWN: Set<String> = setOf(
        EMPTY, PREVIEW, LOADING, CONTENT, DEGRADED, ERROR, CANCELLED,
    )

    fun isKnown(phase: String): Boolean = phase in KNOWN
}
