package com.omnillm.data.modelstore

import java.nio.file.Path

/**
 * Module `:data:model-store` — content-addressed blobs / installations (ADR-008).
 * Only runtime control plane may write (ADR-010).
 *
 * Layout authority: [StorageLayout] (DATA-STORAGE).
 * Materialize: [StreamMaterializer] + platform PFD helpers in `:android:runtime-service`.
 * Filesystem adapter: [FilesystemQuarantineStore] / [FilesystemModelStorePort].
 * Privileged ready FD re-verify: [FilesystemReadyContentPort] (INV-010).
 * Quarantine structural rules: [QuarantineRules] / [QuarantineRuleEngine] (SEC-INPUT section 5-6).
 */
object ModelStoreModule {
    const val MODULE_PATH: String = ":data:model-store"

    fun defaultQuarantineRules(): QuarantineRules = QuarantineRules.DEFAULT

    /**
     * Production filesystem model store (quarantine + atomic promote + ready FDs).
     * Call only from runtime control plane (ADR-010).
     */
    fun createFilesystemPort(
        filesRoot: Path,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        monotonicNowMs: () -> Long = { System.currentTimeMillis() },
    ): ModelStorePort =
        FilesystemModelStorePort.create(
            filesRoot = filesRoot,
            bounds = bounds,
            monotonicNowMs = monotonicNowMs,
        )
}

