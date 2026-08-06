package com.omnillm.data.modelstore

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Filesystem ReadyContentPort for privileged load re-verify (INV-010).
 *
 * Opens installation-relative ready content under [filesRoot] using opaque
 * [storageRootKey] handles from [StorageLayout] - never client-visible paths.
 * Re-hashes actual file bytes; DB READY flags are never consulted here.
 *
 * Control-plane only (ADR-010). Construct inside :runtime.
 */
class FilesystemReadyContentPort(
    private val filesRoot: Path,
    private val bufferSize: Int = MaterializeBounds.DEFAULT_BUFFER_SIZE,
) : ReadyContentPort {

    private val openFds = ConcurrentHashMap<String, Path>()
    private val tokenSeq = AtomicLong(0L)

    override suspend fun openReadOnly(
        installationId: InstallationId,
        storageRootKey: String,
    ): OmniResult<List<ReadOnlyContentFd>> {
        val segments = try {
            StorageLayout.fromHandle(storageRootKey)
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "invalid storageRootKey",
                    details = mapOf("reason" to (e.message ?: "parse")),
                ),
            )
        }
        // storageRootKey must be installations/<id> shape.
        if (segments.size < 2 || segments[0] != StorageLayout.INSTALLATIONS) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "storageRootKey not an installation root"),
            )
        }
        if (segments[1] != installationId.value) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "storageRootKey installationId mismatch"),
            )
        }
        val installDir = segments.fold(filesRoot) { acc, s -> acc.resolve(s) }
        if (!installDir.exists(LinkOption.NOFOLLOW_LINKS) ||
            !installDir.isDirectory(LinkOption.NOFOLLOW_LINKS) ||
            installDir.isSymbolicLink()
        ) {
            return OmniResult.err(
                OmniError.NOT_FOUND(message = "installation storage root missing or unsafe"),
            )
        }

        val fds = ArrayList<ReadOnlyContentFd>()
        try {
            Files.list(installDir).use { stream ->
                stream.forEach { child ->
                    if (child.isSymbolicLink()) return@forEach
                    if (!child.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val role = child.fileName.toString()
                    if (role.endsWith(".tmp") || role.startsWith(".")) return@forEach
                    val size = child.fileSize()
                    val digest = hashFile(child)
                    val token = "fd-${tokenSeq.incrementAndGet()}-${digest.hex.take(12)}"
                    openFds[token] = child
                    fds.add(
                        ReadOnlyContentFd(
                            role = role,
                            blobId = BlobId.parse(digest.hex),
                            byteLength = size,
                            fdToken = token,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INTERNAL(message = "openReadOnly: ${e.javaClass.simpleName}"),
            )
        }
        if (fds.isEmpty()) {
            return OmniResult.err(
                OmniError.NOT_FOUND(message = "no ready content files under installation"),
            )
        }
        fds.sortBy { it.role }
        return OmniResult.ok(fds)
    }

    override suspend fun verifyOpenFds(fds: List<ReadOnlyContentFd>): OmniResult<ContentIdentityCheck> {
        if (fds.isEmpty()) {
            return OmniResult.ok(
                ContentIdentityCheck(ok = false, failureReason = "no FDs to verify"),
            )
        }
        val digests = LinkedHashMap<String, Sha256Digest>()
        for (fd in fds) {
            val path = openFds[fd.fdToken]
                ?: return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "unknown or closed fdToken role=${fd.role}",
                    ),
                )
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || path.isSymbolicLink()) {
                return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "not regular file role=${fd.role}",
                    ),
                )
            }
            val size = path.fileSize()
            if (size != fd.byteLength) {
                return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "size mismatch role=${fd.role}",
                    ),
                )
            }
            val computed = hashFile(path)
            if (computed.hex != fd.blobId.hex) {
                return OmniResult.ok(
                    ContentIdentityCheck(
                        ok = false,
                        failureReason = "digest mismatch role=${fd.role}",
                        computedDigests = digests,
                    ),
                )
            }
            digests[fd.role] = computed
        }
        return OmniResult.ok(ContentIdentityCheck(ok = true, computedDigests = digests))
    }

    override suspend fun closeFds(fds: List<ReadOnlyContentFd>): OmniResult<Unit> {
        for (fd in fds) {
            openFds.remove(fd.fdToken)
        }
        return OmniResult.ok(Unit)
    }

    private fun hashFile(path: Path): Sha256Digest {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buf = ByteArray(bufferSize.coerceAtLeast(4096))
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
        return Sha256Digest.parse(hex)
    }
}
