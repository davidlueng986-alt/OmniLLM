package com.omnillm.data.modelstore

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Composite filesystem [ModelStorePort]: quarantine + atomic promote + ready FD re-verify.
 *
 * Control-plane only (ADR-010 / INV-001). Construct from `:android:runtime-service`
 * attach path via [ModelStoreModule.createFilesystemPort] or [AndroidStorageRoots].
 *
 * Atomic promote (fsync + rename) is implemented by [FilesystemQuarantineStore];
 * READY marker is written separately by Model Manager after promote returns
 * (CORE-MODEL §4 / DATA-OWNERSHIP §3 recoverable boundary).
 */
class FilesystemModelStorePort(
    private val quarantine: FilesystemQuarantineStore,
    private val ready: FilesystemReadyContentPort,
) : ModelStorePort,
    QuarantineStorePort by quarantine,
    AtomicPromotePort by quarantine,
    ReadyContentPort by ready {

    /**
     * Stream materialize into an open quarantine (SEC-INPUT §5–§6, FEAT-MODELHUB §4).
     * Used by acquisition pipeline after [openQuarantine] / beginAcquire.
     */
    fun materializeFromStream(
        key: QuarantineKey,
        role: String,
        expectedBlobId: BlobId,
        expectedByteLength: Long,
        input: InputStream,
        shardIndex: Int = 0,
        seekable: Boolean = false,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): OmniResult<QuarantineSnapshot> =
        quarantine.materializeFromStream(
            key = key,
            role = role,
            expectedBlobId = expectedBlobId,
            expectedByteLength = expectedByteLength,
            shardIndex = shardIndex,
            input = input,
            seekable = seekable,
            cancel = cancel,
        )

    companion object {
        /**
         * Build under [filesRoot] (e.g. Context.filesDir).
         * Ensures logical layout directories exist.
         */
        fun create(
            filesRoot: Path,
            bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
            monotonicNowMs: () -> Long = { System.currentTimeMillis() },
        ): FilesystemModelStorePort {
            val quarantineStore = FilesystemQuarantineStore(
                filesRoot = filesRoot,
                bounds = bounds,
                monotonicNowMs = monotonicNowMs,
            )
            val readyPort = FilesystemReadyContentPort(filesRoot = filesRoot)
            return FilesystemModelStorePort(quarantineStore, readyPort)
        }
    }
}
