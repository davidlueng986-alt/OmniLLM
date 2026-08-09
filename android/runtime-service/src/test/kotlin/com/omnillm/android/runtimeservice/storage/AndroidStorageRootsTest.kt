package com.omnillm.android.runtimeservice.storage

import com.omnillm.android.runtimeservice.security.TestContext
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.data.modelstore.StorageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * TST-04: AndroidStorageRoots layout (ANDROID-STORAGE §1).
 *
 * Uses a generated [TestContext] stub backed by real temp dirs so the
 * filesystem layout contract is verifiable hermetically:
 * - files/ = app-private model store + quarantine + diagnostics
 * - noBackup/ = trust + journal (excluded from backup by platform rules)
 * - getDatabasePath() resolves under the DB dir
 */
class AndroidStorageRootsTest {

    private fun context(): TestContext {
        val root = createTempDirectory("omnillm-roots-test").toFile()
        return TestContext(
            File(root, "files"),
            File(root, "no_backup"),
            File(root, "cache"),
            File(root, "databases"),
        )
    }

    @Test
    fun ensureLayout_createsModelStoreQuarantineAndTrustDirs() {
        val ctx = context()
        AndroidStorageRoots.ensureLayout(ctx)
        val files = ctx.filesDir
        assertTrue(File(files, StorageLayout.MODEL_STORE).isDirectory)
        assertTrue(File(files, "${StorageLayout.MODEL_STORE}/${StorageLayout.BLOBS}").isDirectory)
        assertTrue(File(files, "${StorageLayout.MODEL_STORE}/${StorageLayout.PACKAGES}").isDirectory)
        assertTrue(File(files, StorageLayout.INSTALLATIONS).isDirectory)
        assertTrue(File(files, StorageLayout.QUARANTINE).isDirectory)
        assertTrue(File(files, StorageLayout.LICENSE_TEXT).isDirectory)
        assertTrue(File(files, StorageLayout.DIAGNOSTICS).isDirectory)
        // Trust + journal live on noBackup (excluded from auto-backup).
        assertTrue(File(ctx.noBackupDir, StorageLayout.TRUST).isDirectory)
        assertTrue(File(ctx.noBackupDir, StorageLayout.JOURNAL).isDirectory)
    }

    @Test
    fun filesRoot_andDatabaseFile_resolveUnderAppPrivateDirs() {
        val ctx = context()
        assertEquals(ctx.filesDir, AndroidStorageRoots.filesRoot(ctx))
        assertEquals(ctx.noBackupDir, AndroidStorageRoots.noBackupRoot(ctx))
        assertEquals(
            File(ctx.dbDir, StorageLayout.DATABASE_NAME),
            AndroidStorageRoots.databaseFile(ctx),
        )
    }

    @Test
    fun quarantineStore_isConstructibleAndFilesUnderFilesRoot() {
        val ctx = context()
        val store = AndroidStorageRoots.quarantineStore(
            context = ctx,
            bounds = MaterializeBounds.DEFAULT,
            monotonicNowMs = { 1L },
        )
        assertNotNull(store)
        assertTrue(
            File(ctx.filesDir, StorageLayout.QUARANTINE).isDirectory,
        )
    }

    @Test
    fun modelStore_isConstructible() {
        val ctx = context()
        val store = AndroidStorageRoots.modelStore(
            context = ctx,
            bounds = MaterializeBounds.DEFAULT,
            monotonicNowMs = { 1L },
        )
        assertNotNull(store)
    }
}
