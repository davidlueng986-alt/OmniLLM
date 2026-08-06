package com.omnillm.ui.admin

import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniCommandRequest
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.omnillm.core.canonical.ArtifactPackageCanonicalizer
import com.omnillm.core.canonical.ArtifactPackageEntry
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.modelhub.api.ModelHubJobHandle
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import java.security.MessageDigest
import java.util.UUID

/**
 * UI-side helper: open SAF/content URI, hash bytes, pass RO PFD to runtime via [IOmniAdmin.importLocalFile].
 * UI never writes model-store / DB (INV-001 / ADR-010).
 */
class ModelHubLocalImporter(
    private val admin: IOmniAdmin,
) {
    data class HashedLocalFile(
        val uri: Uri,
        val displayName: String,
        val sha256Hex: String,
        val byteLength: Long,
        val modelRevisionId: String,
        val artifactPackageId: String,
    )

    fun hashUri(context: Context, uri: Uri, displayName: String): OmniResult<HashedLocalFile> {
        return try {
            val cr = context.contentResolver
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            cr.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1024 * 256)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    total += n
                }
            } ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "cannot open content uri"))
            if (total <= 0L) {
                return OmniResult.err(OmniError.INVALID_REQUEST(message = "empty file"))
            }
            val sha = digest.digest().joinToString("") { b -> "%02x".format(b) }
            val blob = BlobId.parse(sha)
                ?: return OmniResult.err(OmniError.INTERNAL(message = "invalid blob id"))
            val entry = ArtifactPackageEntry(
                role = FixtureArtifact.ROLE_WEIGHTS,
                blobId = blob,
                byteLength = total,
                shardIndex = 0,
            )
            val pkg = ArtifactPackageCanonicalizer.artifactPackageId(listOf(entry))
            val rev = IdentityHashing.modelRevisionIdOfCanonicalJson(
                """{"artifactPackageId":"${pkg.hex}","format":"gguf","schemaVersion":1}""",
            )
            OmniResult.ok(
                HashedLocalFile(
                    uri = uri,
                    displayName = displayName.ifBlank { "Imported GGUF" },
                    sha256Hex = sha,
                    byteLength = total,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                ),
            )
        } catch (e: Exception) {
            OmniResult.err(OmniError.INTERNAL(message = "hash failed: ${e.javaClass.simpleName}"))
        }
    }

    fun importHashed(
        context: Context,
        hashed: HashedLocalFile,
        jobId: String = UUID.randomUUID().toString(),
        installationId: String = UUID.randomUUID().toString(),
        openPfd: () -> ParcelFileDescriptor? = {
            context.contentResolver.openFileDescriptor(hashed.uri, "r")
        },
    ): OmniResult<ModelHubJobHandle> {
        return try {
            val pfd: ParcelFileDescriptor = openPfd()
                ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "openFileDescriptor failed"))
            val cmd = OmniCommandRequest().apply {
                commandId = UUID.randomUUID().toString()
                idempotencyKey = "import-$jobId"
                canonicalInputDigest = IdentityHashing.sha256Hex(
                    "importLocal|${hashed.sha256Hex}|${hashed.byteLength}|$jobId",
                )
                hasExpectedVersion = false
                expectedVersion = 0L
            }
            val info = admin.importLocalFile(
                pfd,
                hashed.displayName,
                hashed.sha256Hex,
                hashed.byteLength,
                hashed.modelRevisionId,
                hashed.artifactPackageId,
                installationId,
                jobId,
                cmd,
            )
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            OmniResult.ok(
                ModelHubJobHandle(
                    jobId = info.jobId.orEmpty().ifBlank { jobId },
                    kind = "IMPORT",
                    state = info.state.orEmpty(),
                    resourceVersion = info.resourceVersion,
                    createdNew = true,
                    installationId = installationId,
                    modelRevisionId = hashed.modelRevisionId,
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "importLocalFile remote failure"))
        } catch (e: Exception) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "importLocalFile failed"))
        }
    }

    /**
     * Lab/e2e path: import GGUF previously adb-pushed into app-accessible storage.
     *
     * Prefer [Context.getExternalFilesDir] path (no special storage permission):
     *   adb push model.gguf /sdcard/Android/data/com.omnillm.debug/files/e2e/gemma-3-270m-Q8_0.gguf
     * Also tries legacy public Downloads paths when readable.
     */
    fun importFromPublicE2ePath(
        context: Context,
        fileName: String = "gemma-3-270m-Q8_0.gguf",
    ): OmniResult<ModelHubJobHandle> {
        return try {
            Log.i(TAG, "importFromPublicE2ePath start fileName=$fileName")
            val internalE2e = java.io.File(context.filesDir, "e2e")
            val appE2eDir = java.io.File(context.getExternalFilesDir(null), "e2e")
            val candidates = listOf(
                java.io.File(internalE2e, fileName),
                java.io.File(appE2eDir, fileName),
                java.io.File("/sdcard/Android/data/${context.packageName}/files/e2e/$fileName"),
                java.io.File("/storage/emulated/0/Android/data/${context.packageName}/files/e2e/$fileName"),
                java.io.File("/sdcard/Download/omnillm-e2e/$fileName"),
                java.io.File("/storage/emulated/0/Download/omnillm-e2e/$fileName"),
            )
            val target = candidates.firstOrNull { it.isFile && it.canRead() }
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "e2e GGUF not found under app files/e2e or Download/omnillm-e2e",
                        details = mapOf(
                            "tried" to candidates.joinToString { it.absolutePath },
                        ),
                    ),
                )
            Log.i(TAG, "importFromPublicE2ePath target=${target.absolutePath} size=${target.length()}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.inputStream().use { input ->
                val buf = ByteArray(1024 * 256)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    total += n
                }
            }
            val sha = digest.digest().joinToString("") { b -> "%02x".format(b) }
            val blob = BlobId.parse(sha)
                ?: return OmniResult.err(OmniError.INTERNAL(message = "invalid blob id"))
            val entry = ArtifactPackageEntry(
                role = FixtureArtifact.ROLE_WEIGHTS,
                blobId = blob,
                byteLength = total,
                shardIndex = 0,
            )
            val pkg = ArtifactPackageCanonicalizer.artifactPackageId(listOf(entry))
            val rev = IdentityHashing.modelRevisionIdOfCanonicalJson(
                """{"artifactPackageId":"${pkg.hex}","format":"gguf","schemaVersion":1}""",
            )
            val hashed = HashedLocalFile(
                uri = Uri.fromFile(target),
                displayName = target.name,
                sha256Hex = sha,
                byteLength = total,
                modelRevisionId = rev.hex,
                artifactPackageId = pkg.hex,
            )
            importHashed(
                context = context,
                hashed = hashed,
                openPfd = {
                    ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "e2e path import failed", e)
            OmniResult.err(OmniError.INTERNAL(message = "e2e path import failed: ${e.message}"))
        }
    }

    companion object {
        private const val TAG = "ModelHubLocalImporter"
    }
}
