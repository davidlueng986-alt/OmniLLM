package com.omnillm.runtime.orchestrator

/**
 * Cost-class labels used by the global scheduler (CORE-ORCHESTRATOR §3, FEAT-ROUTING §5).
 *
 * These are **operational labels**, not catalog enums. load / probe / benchmark /
 * generation share one fairness policy but carry different default cost quanta.
 * Unknown labels fail closed at admission of scheduled work.
 */
object CostClassLabels {
    const val LOAD: String = "LOAD"
    const val PROBE: String = "PROBE"
    const val BENCHMARK: String = "BENCHMARK"
    const val GENERATION: String = "GENERATION"

    val ALL: Set<String> = setOf(LOAD, PROBE, BENCHMARK, GENERATION)

    fun isKnown(label: String): Boolean = label in ALL

    /** Default cost units per class (deterministic fixture baseline). */
    fun defaultCostUnits(label: String): Long =
        when (label) {
            PROBE -> 1L
            GENERATION -> 4L
            LOAD -> 16L
            BENCHMARK -> 32L
            else -> error("unknown cost class (fail closed): $label")
        }

    /** Default DRR quantum contribution per class tick. */
    fun defaultQuantum(label: String): Long =
        when (label) {
            PROBE -> 4L
            GENERATION -> 8L
            LOAD -> 16L
            BENCHMARK -> 32L
            else -> error("unknown cost class (fail closed): $label")
        }
}
