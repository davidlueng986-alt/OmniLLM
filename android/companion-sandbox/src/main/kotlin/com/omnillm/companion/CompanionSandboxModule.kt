package com.omnillm.companion

/**
 * Module `:android:companion-sandbox` — different package/UID acceleration sandbox.
 *
 * Authority: SEC-EXTERNAL-SANDBOX, SEC-PLACEMENT, ADR-007, ARCH-TRUST-TOPOLOGY.
 *
 * - applicationId [PACKAGE_NAME] ≠ main app `com.omnillm` (no sharedUserId).
 * - Ticket handshake + read-only PFDs only; never paths/DB handles/directory FDs.
 * - Supervisor binder death ⇒ stop accepting, fence outputs, clear request data, exit.
 * - Companion private data is fully attacker-controlled for that session — never store
 *   main-app secrets, catalog roots, tokens, or long-term model library.
 */
object CompanionSandboxModule {
    const val MODULE_PATH: String = ":android:companion-sandbox"
    const val PACKAGE_NAME: String = "com.omnillm.companion"
    const val MAIN_APP_PACKAGE: String = "com.omnillm"
    const val PROCESS_ROLE: String = "companion-sandbox"

    const val PERMISSION_BIND_SANDBOX: String = "com.omnillm.companion.permission.BIND_SANDBOX"
    const val ACTION_BIND_SANDBOX: String = "com.omnillm.companion.action.BIND_SANDBOX"

    /** Service component class name for explicit bind from runtime. */
    const val SERVICE_CLASS: String = "com.omnillm.companion.CompanionSandboxService"

    /**
     * Protocol major/minor (SEC-EXTERNAL-SANDBOX §7).
     * Old companions reject newer major tickets; host rejects incompatible minors as needed.
     */
    const val PROTOCOL_MAJOR: Int = 1
    const val PROTOCOL_MINOR: Int = 0
}
