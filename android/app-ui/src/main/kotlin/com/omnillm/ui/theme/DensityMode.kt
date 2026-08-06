package com.omnillm.ui.theme

/**
 * Two-layer information density (UX-ARCH §3).
 * Both layers read the same canonical state model; Expert is not hidden semantics.
 */
enum class DensityMode {
    /** Tasks, recommendations, risks, and next steps. */
    STANDARD,

    /** Engine build, backend, driver fingerprint, load key, profiles, raw errors, resource vector. */
    EXPERT,
}
