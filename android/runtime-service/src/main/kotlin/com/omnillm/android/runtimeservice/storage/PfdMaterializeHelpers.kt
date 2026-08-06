package com.omnillm.android.runtimeservice.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.omnillm.data.modelstore.FilesystemQuarantineStore
import com.omnillm.data.modelstore.ImportSpec
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.data.modelstore.MaterializeError
import com.omnillm.data.modelstore.MaterializeException
import com.omnillm.data.modelstore.MaterializeOutcome
import com.omnillm.data.modelstore.PathSafety
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.modelstore.StreamMaterializer
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.data.modelstore.QuarantineSnapshot
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PFD / SAF materialize helpers (ANDROID-STORAGE §3, SEC-INPUT §5).
 *
 * Control-plane only (ADR-010). Sequence:
 * 1. Receiver **dup** FD
 * 2. **fstat** — regular/pipe policy, size, seekability
 * 3. Bounded copy into runtime-owned quarantine with hash
 * 4. Close ownership per policy (we close the dup; caller closes original)
 *
 * Never pass raw client paths to workers/parser — only opaque handles / FDs.
 */
object PfdMaterializeHelpers {

    /**
     * Inspect a PFD without consuming content.
     * Caller must already hold a usable descriptor (prefer after [dup]).
     */
    fun inspect(pfd: ParcelFileDescriptor): PfdStat {
        val fd = pfd.fileDescriptor
        val st = Os.fstat(fd)
        val mode = st.st_mode
        val isReg = OsConstants.S_ISREG(mode)
        val isFifo = OsConstants.S_ISFIFO(mode)
        val size = st.st_size
        val seekable = try {
            val cur = Os.lseek(fd, 0, OsConstants.SEEK_CUR)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            Os.lseek(fd, cur, OsConstants.SEEK_SET)
            true
        } catch (_: Exception) {
            false
        }
        return PfdStat(
            byteLength = if (size >= 0) size else -1L,
            isRegularFile = isReg,
            isPipeOrFifo = isFifo,
            seekable = seekable,
            mode = mode,
        )
    }

    /** Dup and transfer ownership of the returned PFD to the caller. */
    fun dupOrNull(pfd: ParcelFileDescriptor): ParcelFileDescriptor? =
        try {
            ParcelFileDescriptor.dup(pfd.fileDescriptor)
        } catch (_: Exception) {
            null
        }

    /**
     * Materialize from PFD into [store] quarantine (immediate strategy, ANDROID-STORAGE §2.1).
     *
     * @param allowPipe when true, pipes/FIFOs are accepted with [declaredByteLength] hard cap
     */
    fun materializeIntoQuarantine(
        store: FilesystemQuarantineStore,
        key: QuarantineKey,
        role: String,
        expectedBlobId: BlobId,
        expectedByteLength: Long,
        pfd: ParcelFileDescriptor,
        shardIndex: Int = 0,
        allowPipe: Boolean = false,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): OmniResult<QuarantineSnapshot> {
        PathSafety.requireRole(role)
        val owned = dupOrNull(pfd)
            ?: return OmniResult.err(OmniError.INTERNAL(message = "pfd dup failed"))
        try {
            val stat = inspect(owned)
            if (!stat.isRegularFile && !(allowPipe && stat.isPipeOrFifo)) {
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "PFD not regular file (and pipe not allowed)",
                    ),
                )
            }
            if (stat.isRegularFile && stat.byteLength >= 0 &&
                expectedByteLength >= 0 && stat.byteLength != expectedByteLength
            ) {
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "fstat size ${stat.byteLength} != expected $expectedByteLength",
                    ),
                )
            }
            if (stat.byteLength > bounds.maxFileBytes) {
                return OmniResult.err(
                    OmniError.TRANSPORT_TOO_LARGE(message = "fstat size exceeds cap"),
                )
            }
            return FileInputStream(owned.fileDescriptor).use { input ->
                store.materializeFromStream(
                    key = key,
                    role = role,
                    expectedBlobId = expectedBlobId,
                    expectedByteLength = expectedByteLength,
                    shardIndex = shardIndex,
                    input = input,
                    seekable = stat.seekable,
                    cancel = cancel,
                )
            }
        } finally {
            try {
                owned.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Open SAF URI, dup, materialize immediately (strategy 1).
     * Persistable URI grants are strategy 2 — see [ImportSpec] persistence in job layer.
     */
    fun materializeSafUri(
        contentResolver: ContentResolver,
        uri: Uri,
        store: FilesystemQuarantineStore,
        key: QuarantineKey,
        role: String,
        expectedBlobId: BlobId,
        expectedByteLength: Long,
        mode: String = "r",
    ): OmniResult<QuarantineSnapshot> {
        val pfd = try {
            contentResolver.openFileDescriptor(uri, mode)
        } catch (e: Exception) {
            return OmniResult.err(
                OmniError.INTERNAL(message = "openFileDescriptor: ${e.javaClass.simpleName}"),
            )
        } ?: return OmniResult.err(
            OmniError.NOT_FOUND(message = "provider returned null PFD"),
        )
        pfd.use {
            return materializeIntoQuarantine(
                store = store,
                key = key,
                role = role,
                expectedBlobId = expectedBlobId,
                expectedByteLength = expectedByteLength,
                pfd = it,
            )
        }
    }

    /**
     * Bounded copy from PFD to an [OutputStream] target without quarantine store
     * (e.g. staging for Asset materialize).
     */
    fun copyPfdBounded(
        pfd: ParcelFileDescriptor,
        role: String,
        materializeHandle: String,
        expectedByteLength: Long? = null,
        expectedDigestHex: String? = null,
        bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
        allowPipe: Boolean = false,
        cancel: AtomicBoolean = AtomicBoolean(false),
        outputFactory: () -> FileOutputStream,
    ): Result<MaterializeOutcome> {
        val owned = dupOrNull(pfd)
            ?: return Result.failure(
                MaterializeException(MaterializeError.IoFailure("dup failed")),
            )
        try {
            val stat = inspect(owned)
            if (!stat.isRegularFile && !(allowPipe && stat.isPipeOrFifo)) {
                return Result.failure(
                    MaterializeException(
                        MaterializeError.NotRegularFile("mode not allowed"),
                    ),
                )
            }
            return outputFactory().use { out ->
                FileInputStream(owned.fileDescriptor).use { input ->
                    StreamMaterializer.copyBounded(
                        input = input,
                        output = out,
                        request = StreamMaterializer.CopyRequest(
                            role = role,
                            materializeHandle = materializeHandle,
                            expectedByteLength = expectedByteLength,
                            expectedDigestHex = expectedDigestHex,
                            bounds = bounds,
                            seekable = stat.seekable,
                        ),
                        monotonicNowMs = { SystemClock.elapsedRealtime() },
                        cancel = cancel,
                    )
                }
            }
        } finally {
            try {
                owned.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Build typed [ImportSpec] for deferred SAF (strategy 2) — security fields only.
     * Free JSON must not carry these (ANDROID-STORAGE §2).
     */
    fun importSpec(
        uriToken: String,
        grantFlags: Int,
        formatHint: String,
        ownerKey: String,
        expiryMonotonic: Long,
        expectedByteLength: Long? = null,
        expectedDigestHex: String? = null,
    ): ImportSpec = ImportSpec(
        uriToken = uriToken,
        grantFlags = grantFlags,
        expectedByteLength = expectedByteLength,
        expectedDigestHex = expectedDigestHex,
        formatHint = formatHint,
        ownerKey = ownerKey,
        expiryMonotonic = expiryMonotonic,
    )
}

data class PfdStat(
    /** Size from fstat, or -1 if unknown. */
    val byteLength: Long,
    val isRegularFile: Boolean,
    val isPipeOrFifo: Boolean,
    val seekable: Boolean,
    val mode: Int,
)
