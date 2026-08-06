package com.omnillm.features

import com.omnillm.features.admin.AdminFeatureModule

/**
 * Backward-compatible marker for `:features:admin` (FEAT-ADMIN / UX-IA).
 *
 * Prefer [AdminFeatureModule] for API / ViewModel construction.
 *
 * UI domain projections talk only through the non-exported Admin binder:
 * - AIDL: `ai.omnillm.api.IOmniAdmin` via [com.omnillm.ui.admin.AdminRuntimeConnection]
 * - Pure API: `:interfaces:admin` adapted by [com.omnillm.features.admin.ports.AdminApiServiceAdapter]
 *
 * Types and states must come from product `specs/` catalogs — do not invent enums.
 */
object AdminModule {
    const val MODULE_PATH: String = AdminFeatureModule.MODULE_PATH
    const val FEATURE_ID: String = AdminFeatureModule.FEATURE_ID
}
