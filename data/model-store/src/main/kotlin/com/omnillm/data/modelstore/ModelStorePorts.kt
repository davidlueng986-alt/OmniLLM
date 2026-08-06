package com.omnillm.data.modelstore

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.identity.InstallationId

/**
 * Content-addressed model store ports (DATA-STORAGE / ADR-008).
 *
 * Blob / ArtifactPackage / ModelRevision / Installation are distinct identities.
 * Only runtime control plane may write (ADR-010). Paths stay internal —
 * clients never receive filesystem paths.
 *
 * Layout (logical):
 * ```
 * model-store/blobs/<sha256>
 * model-store/packages/<artifactPackageId>/manifest
 * installations/<installationId>/...
 * quarantine/<jobId>/<attemptId>/
 * ```
 */

/** Opaque store key for a quarantine attempt (job + attempt). */
data class QuarantineKey(
    val jobId: String,
    val attemptId: String,
) {
    init {
        require(jobId.isNotEmpty()) { "jobId must be non-empty" }
        require(attemptId.isNotEmpty()) { "attemptId must be non-empty" }
    }
}

/** Declared file role within an artifact package (canonical entry). */
data class DeclaredArtifactFile(
    val role: String,
    val blobId: BlobId,
    val byteLength: Long,
    val shardIndex: Int = 0,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(byteLength >= 0L) { "byteLength must be non-negative" }
        require(shardIndex >= 0) { "shardIndex must be non-negative" }
    }
}

/** Materialized file in quarantine after stream/PFD copy. */
data class QuarantineFileRecord(
    val role: String,
    val expectedBlobId: BlobId,
    val expectedByteLength: Long,
    val shardIndex: Int = 0,
    /** Internal opaque handle — never a client-visible path. */
    val materializeHandle: String,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(expectedByteLength >= 0L) { "expectedByteLength must be non-negative" }
        require(materializeHandle.isNotEmpty()) { "materializeHandle must be non-empty" }
    }
}

data class QuarantineSnapshot(
    val key: QuarantineKey,
    val installationId: InstallationId,
    val modelRevisionId: ModelRevisionId,
    val artifactPackageId: ArtifactPackageId,
    val files: List<QuarantineFileRecord>,
    val deadlineMonotonic: Long,
) {
    init {
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
    }
}

/**
 * Read-only open of content for privileged load re-verify (CORE-MODEL §6 / INV-010).
 * Platform adapters map [fdToken] to a real FD; engines receive tokens, not raw paths.
 */
data class ReadOnlyContentFd(
    val role: String,
    val blobId: BlobId,
    val byteLength: Long,
    val fdToken: String,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(byteLength >= 0L) { "byteLength must be non-negative" }
        require(fdToken.isNotEmpty()) { "fdToken must be non-empty" }
    }
}

/**
 * Identity verification result for quarantine or privileged re-open.
 * Digest/size match only — does **not** promote trust (ADR-009 / INV-008).
 */
data class ContentIdentityCheck(
    val ok: Boolean,
    val computedDigests: Map<String, Sha256Digest> = emptyMap(),
    val failureReason: String? = null,
)

/**
 * Quarantine materialization: copy from bounded stream/PFD into runtime-owned quarantine.
 * Rejects symlink, special file, path traversal, sparse abuse (DATA-STORAGE §2).
 */
interface QuarantineStorePort {
    /**
     * Ensure quarantine root for [key] exists under quota/deadline policy.
     * Does not mark Installation READY.
     */
    suspend fun openQuarantine(
        key: QuarantineKey,
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
        declared: List<DeclaredArtifactFile>,
        deadlineMonotonic: Long,
    ): OmniResult<QuarantineSnapshot>

    /** Record that a declared file has been fully materialized into quarantine. */
    suspend fun recordMaterialized(
        key: QuarantineKey,
        file: QuarantineFileRecord,
    ): OmniResult<QuarantineSnapshot>

    suspend fun getQuarantine(key: QuarantineKey): OmniResult<QuarantineSnapshot>

    /**
     * Verify size + digest of all declared quarantine files (identity only).
     * Compatibility/benchmark must not call this to elevate trust.
     */
    suspend fun verifyQuarantineIdentity(key: QuarantineKey): OmniResult<ContentIdentityCheck>

    /** Cleanup partial acquisition (retention policy). */
    suspend fun cleanupQuarantine(key: QuarantineKey): OmniResult<Unit>
}

/**
 * Atomic promote: fsync files + parent, atomic move into content-addressed store,
 * then control plane marks Installation READY in the same recoverable boundary
 * (CORE-MODEL §4, DATA-OWNERSHIP §3).
 */
interface AtomicPromotePort {
    /**
     * Promote quarantine bytes into immutable ready store for [installationId].
     * Returns durable [storageRootKey] for installations table.
     * Must fsync before rename; crash reconciler uses content identity, not temp names.
     */
    suspend fun atomicPromote(
        key: QuarantineKey,
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<PromoteResult>
}

data class PromoteResult(
    val installationId: InstallationId,
    val storageRootKey: String,
    val promotedBlobIds: List<BlobId>,
) {
    init {
        require(storageRootKey.isNotEmpty()) { "storageRootKey must be non-empty" }
    }
}

/**
 * Open ready installation content as read-only FDs for privileged load re-verify.
 * Callers must re-hash actual FD content — must not trust DB READY flag alone (INV-010).
 */
interface ReadyContentPort {
    suspend fun openReadOnly(
        installationId: InstallationId,
        storageRootKey: String,
    ): OmniResult<List<ReadOnlyContentFd>>

    suspend fun verifyOpenFds(fds: List<ReadOnlyContentFd>): OmniResult<ContentIdentityCheck>

    suspend fun closeFds(fds: List<ReadOnlyContentFd>): OmniResult<Unit>
}

/**
 * Aggregate model-store surface used by Model Manager (single writer only).
 */
interface ModelStorePort : QuarantineStorePort, AtomicPromotePort, ReadyContentPort
