package com.omnillm.data.modelstore

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.identity.IdentityHashing
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Filesystem quarantine + atomic promote (DATA-STORAGE / ANDROID-STORAGE §4).
 *
 * **Single writer only** — construct inside runtime control plane (ADR-010).
 * Rejects symlink / non-regular files under quarantine roots.
 *
 * [filesRoot] is the platform files directory (e.g. Context.filesDir). Paths
 * never leave this class as client-visible strings — only opaque handles.
 */
class FilesystemQuarantineStore(
    private val filesRoot: Path,
    private val bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
    private val monotonicNowMs: () -> Long = { System.currentTimeMillis() },
    private val quarantineRules: QuarantineRules = QuarantineRules(bounds = bounds),
) : QuarantineStorePort, AtomicPromotePort {

    private val snapshots = ConcurrentHashMap<String, QuarantineSnapshot>()

    private fun keyId(key: QuarantineKey): String = "${key.jobId}/${key.attemptId}"

    private fun quarantineDir(key: QuarantineKey): Path {
        val segments = StorageLayout.quarantineRelativeSegments(key.jobId, key.attemptId)
        return segments.fold(filesRoot) { acc, s -> acc.resolve(s) }
    }

    override suspend fun openQuarantine(
        key: QuarantineKey,
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
        declared: List<DeclaredArtifactFile>,
        deadlineMonotonic: Long,
    ): OmniResult<QuarantineSnapshot> {
        // SEC-INPUT §5–§6 formal quarantine rules (count/size/path/role).
        when (
            val admitted = QuarantineRuleEngine.admitDeclared(
                declared = declared.map {
                    QuarantineDeclaredEntry(
                        role = it.role,
                        expectedByteLength = it.byteLength,
                        expectedSha256Hex = it.blobId.hex,
                        shardIndex = it.shardIndex,
                    )
                },
                rules = quarantineRules,
            )
        ) {
            is QuarantineRuleEngine.Outcome.Rejected -> return OmniResult.err(admitted.error)
            is QuarantineRuleEngine.Outcome.Accepted -> Unit
        }

        val dir = quarantineDir(key)
        try {
            if (dir.exists(LinkOption.NOFOLLOW_LINKS) && dir.isSymbolicLink()) {
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(message = "quarantine path is symlink"),
                )
            }
            Files.createDirectories(dir)
            if (!dir.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                return OmniResult.err(
                    OmniError.INTERNAL(message = "quarantine root not a directory"),
                )
            }
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INTERNAL(message = "openQuarantine io: ${e.javaClass.simpleName}"),
            )
        }

        val snap = QuarantineSnapshot(
            key = key,
            installationId = installationId,
            modelRevisionId = modelRevisionId,
            artifactPackageId = artifactPackageId,
            files = emptyList(),
            deadlineMonotonic = deadlineMonotonic,
        )
        snapshots[keyId(key)] = snap
        return OmniResult.ok(snap)
    }

    /**
     * Materialize one declared file from [input] into quarantine.
     * Call after [openQuarantine]; records identity for later verify/promote.
     */
    fun materializeFromStream(
        key: QuarantineKey,
        role: String,
        expectedBlobId: BlobId,
        expectedByteLength: Long,
        shardIndex: Int = 0,
        input: InputStream,
        seekable: Boolean = false,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): OmniResult<QuarantineSnapshot> {
        val existing = snapshots[keyId(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine not open"))
        if (monotonicNowMs() > existing.deadlineMonotonic && existing.deadlineMonotonic > 0L) {
            return OmniResult.err(OmniError.DEADLINE_EXCEEDED(message = "quarantine deadline"))
        }

        val handle = StorageLayout.toHandle(
            StorageLayout.quarantineFileRelativeSegments(
                jobId = key.jobId,
                attemptId = key.attemptId,
                role = role,
                shardIndex = shardIndex,
            ),
        )
        val dest = quarantineDir(key).resolve(
            if (shardIndex == 0) PathSafety.requireRole(role)
            else "${PathSafety.requireRole(role)}.$shardIndex",
        )

        // Refuse overwrite of symlink / special.
        if (dest.exists(LinkOption.NOFOLLOW_LINKS)) {
            if (dest.isSymbolicLink() || !dest.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(message = "destination not regular file"),
                )
            }
        }

        return try {
            Files.newOutputStream(
                dest,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { out ->
                val copy = StreamMaterializer.copyBounded(
                    input = input,
                    output = out,
                    request = StreamMaterializer.CopyRequest(
                        role = role,
                        materializeHandle = handle,
                        expectedByteLength = expectedByteLength,
                        expectedDigestHex = expectedBlobId.hex,
                        bounds = bounds,
                        seekable = seekable,
                    ),
                    monotonicNowMs = monotonicNowMs,
                    cancel = cancel,
                )
                copy.fold(
                    onSuccess = { outcome ->
                        // fsync content
                        fsyncPath(dest)
                        fsyncPath(dest.parent)
                        val record = QuarantineFileRecord(
                            role = role,
                            expectedBlobId = expectedBlobId,
                            expectedByteLength = expectedByteLength,
                            shardIndex = shardIndex,
                            materializeHandle = outcome.materializeHandle,
                        )
                        val updated = existing.copy(files = existing.files + record)
                        snapshots[keyId(key)] = updated
                        OmniResult.ok(updated)
                    },
                    onFailure = { err ->
                        val me = (err as? MaterializeException)?.error
                        val code = me?.toCatalogCode() ?: "INTERNAL"
                        OmniResult.err(
                            OmniError.ofCode(code, message = me?.toString() ?: err.message),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(message = "materialize io: ${e.javaClass.simpleName}"),
            )
        }
    }

    override suspend fun recordMaterialized(
        key: QuarantineKey,
        file: QuarantineFileRecord,
    ): OmniResult<QuarantineSnapshot> {
        val existing = snapshots[keyId(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine not open"))
        val updated = existing.copy(files = existing.files + file)
        snapshots[keyId(key)] = updated
        return OmniResult.ok(updated)
    }

    override suspend fun getQuarantine(key: QuarantineKey): OmniResult<QuarantineSnapshot> {
        val snap = snapshots[keyId(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine not found"))
        return OmniResult.ok(snap)
    }

    override suspend fun verifyQuarantineIdentity(key: QuarantineKey): OmniResult<ContentIdentityCheck> {
        val snap = snapshots[keyId(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine not found"))
        if (snap.files.isEmpty()) {
            return OmniResult.ok(
                ContentIdentityCheck(ok = false, failureReason = "no materialized files"),
            )
        }
        val digests = LinkedHashMap<String, Sha256Digest>()
        for (file in snap.files) {
            val path = resolveHandle(file.materializeHandle)
                ?: return OmniResult.ok(
                    ContentIdentityCheck(ok = false, failureReason = "missing handle"),
                )
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || path.isSymbolicLink()) {
                return OmniResult.ok(
                    ContentIdentityCheck(ok = false, failureReason = "not regular file"),
                )
            }
            val size = path.fileSize()
            if (size != file.expectedByteLength) {
                return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "size mismatch role=${file.role}",
                    ),
                )
            }
            val computed = hashFile(path)
            if (computed.hex != file.expectedBlobId.hex) {
                return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "digest mismatch role=${file.role}",
                        computedDigests = digests,
                    ),
                )
            }
            digests[file.role] = computed
        }
        return OmniResult.ok(ContentIdentityCheck(ok = true, computedDigests = digests))
    }

    override suspend fun cleanupQuarantine(key: QuarantineKey): OmniResult<Unit> {
        snapshots.remove(keyId(key))
        val dir = quarantineDir(key)
        return try {
            if (dir.exists(LinkOption.NOFOLLOW_LINKS)) {
                if (dir.isSymbolicLink()) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(message = "refusing to delete symlink root"),
                    )
                }
                deleteRecursivelySafe(dir)
            }
            OmniResult.ok(Unit)
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(message = "cleanup: ${e.javaClass.simpleName}"),
            )
        }
    }

    override suspend fun atomicPromote(
        key: QuarantineKey,
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<PromoteResult> {
        val snap = snapshots[keyId(key)]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "quarantine not found"))
        val identity = verifyQuarantineIdentity(key)
        when (identity) {
            is OmniResult.Err -> return identity
            is OmniResult.Ok -> {
                if (!identity.value.ok) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = identity.value.failureReason ?: "identity failed",
                        ),
                    )
                }
            }
        }

        val promoted = ArrayList<BlobId>()
        try {
            for (file in snap.files) {
                val src = resolveHandle(file.materializeHandle)
                    ?: return OmniResult.err(
                        OmniError.NOT_FOUND(message = "missing ${file.materializeHandle}"),
                    )
                val blobSeg = StorageLayout.blobRelativeSegments(file.expectedBlobId.hex)
                val blobPath = blobSeg.fold(filesRoot) { acc, s -> acc.resolve(s) }
                Files.createDirectories(blobPath.parent)
                if (!blobPath.exists(LinkOption.NOFOLLOW_LINKS)) {
                    // Copy to temp then atomic move into content-addressed location.
                    val tmp = blobPath.resolveSibling(blobPath.fileName.toString() + ".tmp")
                    Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
                    fsyncPath(tmp)
                    Files.move(
                        tmp,
                        blobPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    fsyncPath(blobPath.parent)
                }
                promoted.add(file.expectedBlobId)
            }
            val installSeg = StorageLayout.installationRelativeSegments(installationId.value)
            val installDir = installSeg.fold(filesRoot) { acc, s -> acc.resolve(s) }
            Files.createDirectories(installDir)
            fsyncPath(installDir)
            val storageRootKey = StorageLayout.toHandle(installSeg)
            // Leave quarantine snapshot for reconciler until cleanup job runs.
            return OmniResult.ok(
                PromoteResult(
                    installationId = installationId,
                    storageRootKey = storageRootKey,
                    promotedBlobIds = promoted,
                ),
            )
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INTERNAL(message = "promote: ${e.javaClass.simpleName}"),
            )
        }
    }

    private fun resolveHandle(handle: String): Path? {
        return try {
            val segments = StorageLayout.fromHandle(handle)
            segments.fold(filesRoot) { acc, s -> acc.resolve(s) }
        } catch (_: Exception) {
            null
        }
    }

    private fun hashFile(path: Path): Sha256Digest {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buf = ByteArray(bounds.bufferSize)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
        return Sha256Digest.parse(hex)
    }

    private fun fsyncPath(path: Path?) {
        if (path == null) return
        try {
            if (Files.isDirectory(path)) {
                // Best-effort directory fsync via FileChannel on some platforms.
                java.io.FileOutputStream(path.toFile()).use { fos ->
                    fos.fd.sync()
                }
            } else if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                java.io.FileOutputStream(path.toFile(), true).use { fos ->
                    fos.fd.sync()
                }
            }
        } catch (_: Exception) {
            // Platform may not support dir fsync; content fsync is primary.
        }
    }

    private fun deleteRecursivelySafe(root: Path) {
        if (!root.exists(LinkOption.NOFOLLOW_LINKS)) return
        if (root.isSymbolicLink()) {
            Files.deleteIfExists(root)
            return
        }
        if (root.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            Files.list(root).use { stream ->
                stream.forEach { child ->
                    if (child.isSymbolicLink()) {
                        Files.deleteIfExists(child)
                    } else if (child.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                        deleteRecursivelySafe(child)
                    } else {
                        Files.deleteIfExists(child)
                    }
                }
            }
        }
        Files.deleteIfExists(root)
    }
}

/**
 * Verify raw bytes match expected [BlobId] (INV-010 helper).
 */
fun verifyBlobBytes(raw: ByteArray, expected: BlobId): Boolean =
    IdentityHashing.blobIdOfRawBytes(raw).hex == expected.hex
