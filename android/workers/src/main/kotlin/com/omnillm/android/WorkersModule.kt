package com.omnillm.android

/**
 * Module `:android:workers` — same-UID crash containment workers.
 *
 * Authority: ARCH-TRUST-TOPOLOGY, SEC-PLACEMENT, ANDROID-SERVICE.
 *
 * - Process [ProcessTopology.ENGINE_WORKER] shares App UID with runtime.
 * - Provides crash containment only; **not** a security sandbox (ADR-007).
 * - Must not open domain DB, model-store writable paths, or token vault (ADR-010).
 * - Supervisor binder death ⇒ fence epoch and exit (no stale work).
 */
object WorkersModule {
    const val MODULE_PATH: String = ":android:workers"
    const val PROCESS_ROLE: String = "engine-worker"
}
