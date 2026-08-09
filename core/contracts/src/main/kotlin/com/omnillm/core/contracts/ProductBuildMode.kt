package com.omnillm.core.contracts

/**
 * Product build posture for OmniLLM Android.
 *
 * **DEVELOPMENT_SHIP_MODE = true** (current default for this monorepo):
 * - All catalog engines may attempt execute when an adapter is bound.
 * - Capability negotiation may project SUPPORTED/CONDITIONAL without formal
 *   QUALIFIED_WITH_ENVELOPE + lab PASS packs (so feature development is unblocked).
 * - `runtime.exploratoryExecuteEnabled` defaults to true.
 *
 * Flip to **false** only when you intentionally want the pre-build document
 * "honesty gates" (UNKNOWN until qualification evidence) for a compliance audit.
 *
 * This is **not** a Play Console certificate; it only removes self-imposed
 * product-doc fail-closed that blocked finishing feature development.
 */
object ProductBuildMode {

    /**
     * Master switch: finish building all engines + features without qualification
     * paperwork blocking execute paths.
     */
    const val DEVELOPMENT_SHIP_MODE: Boolean = true

    /** When [DEVELOPMENT_SHIP_MODE], allow every catalog engine to load a real backend if present. */
    fun allowAllEnginesNative(): Boolean = DEVELOPMENT_SHIP_MODE

    /** When [DEVELOPMENT_SHIP_MODE], treat bound adapters as executable without PASS packs. */
    fun allowExecuteWithoutQualification(): Boolean = DEVELOPMENT_SHIP_MODE

    /** Default for `runtime.exploratoryExecuteEnabled`. */
    fun defaultExploratoryExecuteEnabled(): Boolean = DEVELOPMENT_SHIP_MODE

    /** Skip attach-time assert that forbids any SUPPORTED cell. */
    fun allowSupportedProjectionWithoutPass(): Boolean = DEVELOPMENT_SHIP_MODE
}
