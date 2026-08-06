package com.omnillm.features

/**
 * Compatibility entry for `:features:auto-setup`.
 * Prefer [com.omnillm.features.autosetup.AutoSetupModule].
 */
@Deprecated(
    message = "Use com.omnillm.features.autosetup.AutoSetupModule",
    replaceWith = ReplaceWith(
        "AutoSetupModule",
        "com.omnillm.features.autosetup.AutoSetupModule",
    ),
)
object AutoSetupModule {
    const val MODULE_PATH: String = com.omnillm.features.autosetup.AutoSetupModule.MODULE_PATH
    const val FEATURE_ID: String = com.omnillm.features.autosetup.AutoSetupModule.FEATURE_ID
}
