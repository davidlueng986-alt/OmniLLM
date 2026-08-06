package com.omnillm.data.modelstore

/**
 * Logical storage layout (DATA-STORAGE §1 / ANDROID-STORAGE).
 *
 * Actual filesystem roots are platform-adapter details (app-private / no-backup).
 * Domain code uses content IDs and opaque handles — never expose absolute paths to clients.
 *
 * ```
 * files/
 *   model-store/blobs/<sha256>
 *   model-store/packages/<artifactPackageId>/manifest
 *   installations/<installationId>/...
 *   quarantine/<jobId>/<attemptId>/
 *   license-text/<digest>
 *   diagnostics/<bundleId>
 * noBackupFiles/
 *   trust/
 *   journal/
 * databases/
 *   omnillm.db
 * ```
 */
object StorageLayout {
    const val MODEL_STORE: String = "model-store"
    const val BLOBS: String = "blobs"
    const val PACKAGES: String = "packages"
    const val MANIFEST: String = "manifest"
    const val INSTALLATIONS: String = "installations"
    const val QUARANTINE: String = "quarantine"
    const val LICENSE_TEXT: String = "license-text"
    const val DIAGNOSTICS: String = "diagnostics"
    const val TRUST: String = "trust"
    const val JOURNAL: String = "journal"
    const val DATABASES: String = "databases"
    const val DATABASE_NAME: String = "omnillm.db"

    /** Relative segments under filesDir for content-addressed blob. */
    fun blobRelativeSegments(sha256Hex: String): List<String> {
        val hex = PathSafety.requireDigestHex(sha256Hex, "blobId")
        return listOf(MODEL_STORE, BLOBS, hex)
    }

    fun packageManifestRelativeSegments(artifactPackageIdHex: String): List<String> {
        val id = PathSafety.requireDigestHex(artifactPackageIdHex, "artifactPackageId")
        return listOf(MODEL_STORE, PACKAGES, id, MANIFEST)
    }

    fun installationRelativeSegments(installationId: String): List<String> {
        val id = PathSafety.requireToken(installationId, "installationId")
        return listOf(INSTALLATIONS, id)
    }

    fun quarantineRelativeSegments(jobId: String, attemptId: String): List<String> {
        val j = PathSafety.requireToken(jobId, "jobId")
        val a = PathSafety.requireToken(attemptId, "attemptId")
        return listOf(QUARANTINE, j, a)
    }

    fun quarantineFileRelativeSegments(
        jobId: String,
        attemptId: String,
        role: String,
        shardIndex: Int = 0,
    ): List<String> {
        require(shardIndex >= 0) { "shardIndex must be non-negative" }
        val roleSafe = PathSafety.requireRole(role)
        val fileName = if (shardIndex == 0) roleSafe else "${roleSafe}.$shardIndex"
        return quarantineRelativeSegments(jobId, attemptId) + fileName
    }

    fun licenseTextRelativeSegments(digestHex: String): List<String> {
        val hex = PathSafety.requireDigestHex(digestHex, "licenseDigest")
        return listOf(LICENSE_TEXT, hex)
    }

    fun diagnosticsRelativeSegments(bundleId: String): List<String> {
        val id = PathSafety.requireToken(bundleId, "bundleId")
        return listOf(DIAGNOSTICS, id)
    }

    /** Join relative segments with `/` for opaque materialize handles (not OS paths). */
    fun toHandle(segments: List<String>): String =
        segments.joinToString("/")

    fun fromHandle(handle: String): List<String> {
        require(handle.isNotEmpty()) { "handle must be non-empty" }
        val parts = handle.split('/')
        require(parts.none { it.isEmpty() }) { "handle contains empty segment" }
        parts.forEach { PathSafety.requireToken(it, "handleSegment") }
        return parts
    }
}

/**
 * Path / name safety for quarantine and store materialization
 * (DATA-STORAGE §2, SEC-INPUT §6, ANDROID-STORAGE).
 *
 * Rejects path traversal, absolute forms, null bytes, control characters,
 * and reserved names. Does not follow symlinks at the filesystem layer —
 * [FilesystemQuarantineStore] uses NOFOLLOW / regular-file checks.
 */
object PathSafety {
    private val TOKEN = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val DIGEST_HEX = Regex("^[0-9a-f]{64}$")
    private val ROLE = Regex("^[A-Za-z0-9._-]{1,128}$")

    private val FORBIDDEN_TOKENS = setOf(
        ".", "..", "CON", "PRN", "AUX", "NUL",
        "com1", "com2", "lpt1", "lpt2",
    )

    fun requireToken(raw: String, field: String): String {
        require(raw.isNotEmpty()) { "$field must be non-empty" }
        require(raw.none { it.code == 0 || it.isISOControl() }) {
            "$field contains control characters"
        }
        require(!raw.contains('/') && !raw.contains('\\')) {
            "$field must not contain path separators"
        }
        require(TOKEN.matches(raw)) {
            "$field must match token pattern, got length=${raw.length}"
        }
        require(raw.lowercase() !in FORBIDDEN_TOKENS && raw !in FORBIDDEN_TOKENS) {
            "$field is a forbidden token"
        }
        return raw
    }

    fun requireDigestHex(raw: String, field: String): String {
        val lower = raw.lowercase()
        require(DIGEST_HEX.matches(lower)) {
            "$field must be 64 lowercase hex chars"
        }
        return lower
    }

    fun requireRole(role: String): String {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(role.none { it.code == 0 || it.isISOControl() }) {
            "role contains control characters"
        }
        require(!role.contains('/') && !role.contains('\\') && !role.contains("..")) {
            "role must not contain path separators or traversal"
        }
        require(ROLE.matches(role)) {
            "role must match safe pattern"
        }
        return role
    }

    /**
     * Validate a client-supplied relative path fragment (e.g. zip entry name).
     * Rejects absolute, traversal, empty segments, and oversize components.
     */
    fun validateRelativePath(relative: String, maxDepth: Int = 8, maxComponent: Int = 255): String {
        require(relative.isNotEmpty()) { "relative path must be non-empty" }
        require(relative.none { it.code == 0 }) { "relative path contains NUL" }
        require(!relative.startsWith("/") && !relative.startsWith("\\")) {
            "absolute paths rejected"
        }
        // Windows drive / UNC
        require(!Regex("^[A-Za-z]:").containsMatchIn(relative)) {
            "drive-letter paths rejected"
        }
        val normalized = relative.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "relative path has no segments" }
        require(parts.size <= maxDepth) { "relative path exceeds max depth" }
        for (p in parts) {
            require(p != "." && p != "..") { "path traversal rejected" }
            require(p.length <= maxComponent) { "path component too long" }
            require(p.none { it.isISOControl() }) { "control char in path component" }
        }
        return parts.joinToString("/")
    }
}
