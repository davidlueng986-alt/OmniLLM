package com.omnillm.features

/**
 * Compatibility entry for `:features:lan`.
 * Prefer [com.omnillm.features.lan.LanFeatureModule].
 */
@Deprecated(
    message = "Use com.omnillm.features.lan.LanFeatureModule",
    replaceWith = ReplaceWith(
        "LanFeatureModule",
        "com.omnillm.features.lan.LanFeatureModule",
    ),
)
object LanModule {
    const val MODULE_PATH: String = com.omnillm.features.lan.LanFeatureModule.MODULE_PATH
    const val FEATURE_ID: String = com.omnillm.features.lan.LanFeatureModule.FEATURE_ID
}
