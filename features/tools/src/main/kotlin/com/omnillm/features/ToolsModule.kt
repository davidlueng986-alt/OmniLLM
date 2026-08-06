package com.omnillm.features

/**
 * Gradle path marker for `:features:tools` (FEAT-TOOLS).
 * Prefer [com.omnillm.features.tools.ToolsFeatureModule] for wiring.
 */
object ToolsModule {
    const val MODULE_PATH: String = ":features:tools"
    const val FEATURE_ID: String = "FEAT-TOOLS"
}
