package com.omnillm.interfaces

/**
 * Module marker for `:interfaces:aidl`.
 *
 * Semantic IDL authority: `specs/aidl/omnillm-aidl.yaml`
 * Projection: `src/main/aidl/ai/omnillm/api/` (generated/extracted; do not hand-edit).
 * Canonical state/error/request semantics remain in shared catalogs (projection rule in YAML).
 */
object AidlModule {
    const val MODULE_PATH: String = ":interfaces:aidl"
    const val AIDL_SPEC_PATH: String = "specs/aidl/omnillm-aidl.yaml"
    const val AIDL_PACKAGE: String = "ai.omnillm.api"
    const val PROTOCOL_PACKAGE_ROOT: String = "ai.omnillm.api"
}
