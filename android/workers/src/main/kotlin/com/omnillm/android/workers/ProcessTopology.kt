package com.omnillm.android.workers

/**
 * Android process names / roles for OmniLLM (ARCH-TRUST-TOPOLOGY §1).
 *
 * Labels are fixed product names — do not invent alternate process IDs.
 * Same-UID processes are **not** security sandboxes.
 */
object ProcessTopology {
    /** Default application process (UI / navigation). INV-001: no native engines / DB writes. */
    const val MAIN: String = "main"

    /** Control plane process suffix (runtime FGS, single writer). */
    const val RUNTIME_SUFFIX: String = ":runtime"

    /**
     * Same-UID crash worker for trusted but stability-sensitive engine code.
     * Placement class: CRASH_CONTAINED_TRUSTED (SEC-PLACEMENT).
     */
    const val ENGINE_WORKER_SUFFIX: String = ":engine_worker"

    /**
     * isolatedProcess parser — distinct isolated UID (ANDROID-ISOLATED-PROCESS).
     * Receives read-only FDs only; cannot open app-private catalog.
     */
    const val PARSER_SUFFIX: String = ":parser"

    /**
     * isolatedProcess CPU path for untrusted models when CPU-only (ISOLATED_CPU_UNTRUSTED).
     * Not implemented in this module — companion owns accelerated untrusted (ADR-007).
     */
    const val SANDBOX_CPU_SUFFIX: String = ":sandbox_cpu"

    /** Logical process role strings (match SingleWriterPolicy forbidden roles). */
    object Roles {
        const val APP_UI: String = "app-ui"
        const val RUNTIME: String = "runtime-control-plane"
        const val ENGINE_WORKER: String = "engine-worker"
        const val ISOLATED_PARSER: String = "isolated-parser"
        const val COMPANION_SANDBOX: String = "companion-sandbox"
    }
}

/**
 * SEC-PLACEMENT class labels executable in same-UID worker.
 * Stored as catalog strings (not invented enums).
 *
 * Untrusted accelerated work must use companion (EXTERNAL_UID_ACCELERATED);
 * same-UID never acts as a security sandbox (ADR-007 / SEC-PLACEMENT).
 */
object WorkerPlacement {
    const val PRIVILEGED_TRUSTED: String = "PRIVILEGED_TRUSTED"
    const val CRASH_CONTAINED_TRUSTED: String = "CRASH_CONTAINED_TRUSTED"
    const val ISOLATED_CPU_UNTRUSTED: String = "ISOLATED_CPU_UNTRUSTED"
    const val EXTERNAL_UID_ACCELERATED: String = "EXTERNAL_UID_ACCELERATED"

    /** Same-UID worker must refuse untrusted accelerated paths. */
    const val TRUST_PLACEMENT_REQUIRED: String = "TRUST_PLACEMENT_REQUIRED"

    val ALLOWED_IN_ENGINE_WORKER: Set<String> = setOf(
        PRIVILEGED_TRUSTED,
        CRASH_CONTAINED_TRUSTED,
    )

    fun isAllowed(placementClass: String): Boolean =
        placementClass in ALLOWED_IN_ENGINE_WORKER

    /**
     * Classes that require different UID (isolated or companion) — never admit
     * into `:engine_worker` even if engine plan proposes them.
     */
    fun requiresExternalUid(placementClass: String): Boolean =
        placementClass == ISOLATED_CPU_UNTRUSTED ||
            placementClass == EXTERNAL_UID_ACCELERATED
}
