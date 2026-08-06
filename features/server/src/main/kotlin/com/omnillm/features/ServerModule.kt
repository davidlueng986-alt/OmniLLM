package com.omnillm.features

/**
 * Compatibility entry for `:features:server`.
 * Prefer [com.omnillm.features.server.ServerFeatureModule].
 */
@Deprecated(
    message = "Use com.omnillm.features.server.ServerFeatureModule",
    replaceWith = ReplaceWith(
        "ServerFeatureModule",
        "com.omnillm.features.server.ServerFeatureModule",
    ),
)
object ServerModule {
    const val MODULE_PATH: String = com.omnillm.features.server.ServerFeatureModule.MODULE_PATH
    const val FEATURE_ID: String = com.omnillm.features.server.ServerFeatureModule.FEATURE_ID
    const val DEFAULT_LOOPBACK_PORT: Int =
        com.omnillm.features.server.ServerFeatureModule.DEFAULT_LOOPBACK_PORT
    const val OPENAPI_SPEC_PATH: String =
        com.omnillm.features.server.ServerFeatureModule.OPENAPI_SPEC_PATH
}
