package com.omnillm.android.runtimeservice.process

import com.omnillm.android.RuntimeServiceModule

/**
 * Canonical process name suffixes for ARCH-TRUST-TOPOLOGY.
 *
 * Same-UID processes (`:runtime`, `:engine_worker`) provide crash containment only —
 * not a security sandbox (ADR-007). Isolated / companion UIDs are separate modules.
 *
 * Companion lives in a **different package** (`com.omnillm.companion`) — not a process
 * suffix of the main app.
 */
object ProcessNames {
    const val RUNTIME_SUFFIX: String = RuntimeServiceModule.RUNTIME_PROCESS_SUFFIX
    const val ENGINE_WORKER_SUFFIX: String = ":engine_worker"
    const val PARSER_SUFFIX: String = ":parser"
    const val SANDBOX_CPU_SUFFIX: String = ":sandbox_cpu"

    /** Companion applicationId (separate APK / Linux UID). */
    const val COMPANION_PACKAGE: String = RuntimeServiceModule.Companion.PACKAGE_NAME

    fun isRuntimeProcessName(processName: String): Boolean =
        processName.endsWith(RUNTIME_SUFFIX)
}
