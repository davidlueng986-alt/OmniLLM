package com.omnillm.android.runtimeservice.storage

import android.content.Context
import com.omnillm.data.modelstore.FilesystemModelStorePort
import com.omnillm.data.modelstore.FilesystemQuarantineStore
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.data.modelstore.ModelStoreModule
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.StorageLayout
import java.io.File
import java.nio.file.Path

/**
 * Platform roots for DATA-STORAGE layout (ANDROID-STORAGE §1).
 *
 * - Model store / quarantine / installations → [Context.getFilesDir] (app-private)
 * - Trust / journal → [Context.getNoBackupFilesDir]
 * - DB → [Context.getDatabasePath]
 *
 * External shared storage is **not** an active model store; SAF is import/export only.
 */
object AndroidStorageRoots {

    fun filesRoot(context: Context): File = context.filesDir

    fun filesRootPath(context: Context): Path = context.filesDir.toPath()

    fun noBackupRoot(context: Context): File = context.noBackupFilesDir

    fun databaseFile(context: Context): File =
        context.getDatabasePath(StorageLayout.DATABASE_NAME)

    fun ensureLayout(context: Context) {
        val files = filesRoot(context)
        listOf(
            StorageLayout.MODEL_STORE,
            "${StorageLayout.MODEL_STORE}/${StorageLayout.BLOBS}",
            "${StorageLayout.MODEL_STORE}/${StorageLayout.PACKAGES}",
            StorageLayout.INSTALLATIONS,
            StorageLayout.QUARANTINE,
            StorageLayout.LICENSE_TEXT,
            StorageLayout.DIAGNOSTICS,
        ).forEach { rel ->
            File(files, rel).mkdirs()
        }
        val noBackup = noBackupRoot(context)
        File(noBackup, StorageLayout.TRUST).mkdirs()
        File(noBackup, StorageLayout.JOURNAL).mkdirs()
    }

    /**
     * Control-plane factory for [FilesystemQuarantineStore].
     * Must not be constructed from UI / worker / isolated / companion (ADR-010).
     */
    fun quarantineStore(
        context: Context,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ): FilesystemQuarantineStore {
        ensureLayout(context)
        return FilesystemQuarantineStore(
            filesRoot = filesRootPath(context),
            bounds = bounds,
            monotonicNowMs = monotonicNowMs,
        )
    }

    /**
     * Full filesystem [ModelStorePort] (quarantine + atomic promote + ready FDs).
     * Control-plane only (ADR-010). Used by durable ModelManager attach.
     */
    fun modelStore(
        context: Context,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ): ModelStorePort {
        ensureLayout(context)
        return ModelStoreModule.createFilesystemPort(
            filesRoot = filesRootPath(context),
            bounds = bounds,
            monotonicNowMs = monotonicNowMs,
        )
    }

    /** Same as [modelStore] returning the concrete composite type. */
    fun filesystemModelStore(
        context: Context,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ): FilesystemModelStorePort {
        ensureLayout(context)
        return FilesystemModelStorePort.create(
            filesRoot = filesRootPath(context),
            bounds = bounds,
            monotonicNowMs = monotonicNowMs,
        )
    }
}
