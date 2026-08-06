package com.omnillm.data.modelstore

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.identity.IdentityHashing
import com.omnillm.core.identity.InstallationId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class FilesystemQuarantineStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digestHex = "a".repeat(64)
    private val blobId get() = BlobId.parse(IdentityHashing.sha256Hex(payload))
    private val payload = "model-bytes-v1".toByteArray()

    private fun ids() = Triple(
        InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
        ModelRevisionId.parse(digestHex),
        ArtifactPackageId.parse(digestHex),
    )

    @Test
    fun materializeVerifyPromote(): Unit = runBlocking {
        val root = tmp.root.toPath()
        val store = FilesystemQuarantineStore(
            filesRoot = root,
            monotonicNowMs = { 1_000L },
        )
        val key = QuarantineKey("job1", "attempt1")
        val (installId, revId, pkgId) = ids()
        val expectedBlob = BlobId.parse(IdentityHashing.sha256Hex(payload))

        val opened = store.openQuarantine(
            key = key,
            installationId = installId,
            modelRevisionId = revId,
            artifactPackageId = pkgId,
            declared = listOf(
                DeclaredArtifactFile(
                    role = "weights",
                    blobId = expectedBlob,
                    byteLength = payload.size.toLong(),
                ),
            ),
            deadlineMonotonic = 60_000L,
        )
        assertTrue(opened is OmniResult.Ok)

        val mat = store.materializeFromStream(
            key = key,
            role = "weights",
            expectedBlobId = expectedBlob,
            expectedByteLength = payload.size.toLong(),
            input = ByteArrayInputStream(payload),
        )
        assertTrue(mat is OmniResult.Ok)

        val verify = store.verifyQuarantineIdentity(key)
        assertTrue(verify is OmniResult.Ok)
        assertTrue((verify as OmniResult.Ok).value.ok)

        val promoted = store.atomicPromote(key, installId, revId, pkgId)
        assertTrue(promoted is OmniResult.Ok)
        val result = (promoted as OmniResult.Ok).value
        assertEquals(installId, result.installationId)
        assertEquals(1, result.promotedBlobIds.size)
        assertEquals(expectedBlob.hex, result.promotedBlobIds.single().hex)

        val blobPath = root
            .resolve("model-store")
            .resolve("blobs")
            .resolve(expectedBlob.hex)
        assertTrue(blobPath.toFile().exists())
        assertTrue(blobPath.toFile().readBytes().contentEquals(payload))
    }

    @Test
    fun rejectsBadRole(): Unit = runBlocking {
        val store = FilesystemQuarantineStore(tmp.root.toPath())
        val (installId, revId, pkgId) = ids()
        val opened = store.openQuarantine(
            key = QuarantineKey("j", "a"),
            installationId = installId,
            modelRevisionId = revId,
            artifactPackageId = pkgId,
            declared = listOf(
                DeclaredArtifactFile(
                    role = "../x",
                    blobId = BlobId.parse(digestHex),
                    byteLength = 1L,
                ),
            ),
            deadlineMonotonic = 1L,
        )
        assertTrue(opened is OmniResult.Err)
    }
}
