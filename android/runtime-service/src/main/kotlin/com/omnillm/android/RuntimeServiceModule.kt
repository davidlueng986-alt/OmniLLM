package com.omnillm.android

/**
 * Module marker for `:android:runtime-service`.
 *
 * Hosts the `:runtime` process control plane (ANDROID-SERVICE, ARCH-TRUST-TOPOLOGY,
 * ARCH-RUNTIME-LIFECYCLE, ADR-010). UI process must not load native engines or open
 * a domain DB writer (INV-001).
 *
 * Also owns platform storage roots / PFD materialize helpers under
 * `com.omnillm.android.runtimeservice.storage`.
 */
object RuntimeServiceModule {
    const val MODULE_PATH: String = ":android:runtime-service"
    const val PROCESS_ROLE: String = "runtime-control-plane"

    /** Process suffix relative to the host application package. */
    const val RUNTIME_PROCESS_SUFFIX: String = ":runtime"

    /** Explicit intent actions (opaque IDs only — no tokens/paths in extras). */
    object Actions {
        const val RUNTIME_FOREGROUND: String = "com.omnillm.action.RUNTIME_FOREGROUND"
        const val BIND_RUNTIME: String = "com.omnillm.action.BIND_RUNTIME"
        const val BIND_ADMIN: String = "com.omnillm.action.BIND_ADMIN"
        const val TRANSFER: String = "com.omnillm.action.TRANSFER"
    }

    /** Discovery-only normal permission (not an auth boundary). */
    const val PERMISSION_BIND_RUNTIME: String = "com.omnillm.permission.BIND_RUNTIME"

    /**
     * Companion package is a **separate applicationId** (`com.omnillm.companion`).
     * Host binds via signature permission; never merges companion as library (ADR-007).
     */
    object Companion {
        const val PACKAGE_NAME: String = "com.omnillm.companion"
        const val ACTION_BIND: String = "com.omnillm.companion.action.BIND_SANDBOX"
        const val PERMISSION_BIND: String = "com.omnillm.companion.permission.BIND_SANDBOX"
    }
}
