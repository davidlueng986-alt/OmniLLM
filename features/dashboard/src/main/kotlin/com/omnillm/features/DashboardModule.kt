package com.omnillm.features

import com.omnillm.features.dashboard.DashboardFeatureModule

/**
 * Backward-compatible marker for `:features:dashboard` (FEAT-DASHBOARD).
 *
 * Prefer [DashboardFeatureModule] for API / ViewModel construction.
 *
 * Types and states must come from product `specs/` catalogs — do not invent enums.
 */
object DashboardModule {
    const val MODULE_PATH: String = DashboardFeatureModule.MODULE_PATH
    const val FEATURE_ID: String = DashboardFeatureModule.FEATURE_ID
}
