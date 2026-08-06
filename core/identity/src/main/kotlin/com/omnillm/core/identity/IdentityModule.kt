package com.omnillm.core.identity

/**
 * Module marker for `:core:identity`.
 *
 * ADR-008: Blob / ArtifactPackage / ModelRevision / Installation are distinct
 * identity layers. Computation and validation live here; digest newtypes are
 * generated under `:core:canonical`.
 */
object IdentityModule {
    const val MODULE_PATH: String = ":core:identity"
}
