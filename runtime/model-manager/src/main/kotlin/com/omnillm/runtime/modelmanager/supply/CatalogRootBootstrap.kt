package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.errors.generated.OmniError

/**
 * Catalog root bootstrap (SEC-SUPPLY §1, SEC-PROFILE catalog/artifact signature).
 *
 * App package embeds **full** initial root metadata bytes and their digest —
 * not only a remote URL. First offline launch can establish trust from the APK
 * embedded root alone. Root rotation must verify each intermediate version
 * (no skip). Root recovery has an explicit manual/update path.
 */
data class EmbeddedCatalogRoot(
    /** Opaque root metadata payload (typed schema bytes, not free statement_json). */
    val metadataBytes: ByteArray,
    /** SHA-256 of [metadataBytes] (lower-case hex). */
    val metadataDigestHex: String,
    /** Root role schema major version pinned in package. */
    val schemaMajorVersion: Int,
    /** Root sequence / version at embed time. */
    val rootVersion: Long,
    /** Optional expiry wall-ms; null means no package-level expiry claim. */
    val expiresAtEpochMs: Long? = null,
    /** Ed25519 (or profile-allowed) public key ids embedded with root. */
    val keyIds: List<String> = emptyList(),
) {
    init {
        require(metadataBytes.isNotEmpty()) { "metadataBytes must be non-empty" }
        require(metadataDigestHex.matches(HEX64)) { "metadataDigestHex must be 64 hex chars" }
        require(schemaMajorVersion >= 1) { "schemaMajorVersion must be >= 1" }
        require(rootVersion >= 0L) { "rootVersion must be non-negative" }
        expiresAtEpochMs?.let { require(it > 0L) }
        // Integrity: digest must match bytes (fail closed on packaging mistakes).
        val actual = IdentityHashing.sha256Hex(metadataBytes)
        require(actual == metadataDigestHex.lowercase()) {
            "embedded root digest mismatch"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedCatalogRoot) return false
        return metadataBytes.contentEquals(other.metadataBytes) &&
            metadataDigestHex == other.metadataDigestHex &&
            schemaMajorVersion == other.schemaMajorVersion &&
            rootVersion == other.rootVersion &&
            expiresAtEpochMs == other.expiresAtEpochMs &&
            keyIds == other.keyIds
    }

    override fun hashCode(): Int {
        var r = metadataBytes.contentHashCode()
        r = 31 * r + metadataDigestHex.hashCode()
        r = 31 * r + schemaMajorVersion
        r = 31 * r + rootVersion.hashCode()
        r = 31 * r + (expiresAtEpochMs?.hashCode() ?: 0)
        r = 31 * r + keyIds.hashCode()
        return r
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /**
         * Build from raw bytes, computing digest (package build pipeline entry).
         */
        fun fromBytes(
            metadataBytes: ByteArray,
            schemaMajorVersion: Int,
            rootVersion: Long,
            expiresAtEpochMs: Long? = null,
            keyIds: List<String> = emptyList(),
        ): EmbeddedCatalogRoot {
            val digest = IdentityHashing.sha256Hex(metadataBytes)
            return EmbeddedCatalogRoot(
                metadataBytes = metadataBytes,
                metadataDigestHex = digest,
                schemaMajorVersion = schemaMajorVersion,
                rootVersion = rootVersion,
                expiresAtEpochMs = expiresAtEpochMs,
                keyIds = keyIds,
            )
        }
    }
}

/**
 * Durable catalog trust state for the **current app install lifecycle**
 * (SEC-SUPPLY §2 / §4 anti-rollback boundary).
 *
 * Uninstall/reinstall without external trusted persistence cannot claim prior
 * higher sequences were observed.
 */
data class CatalogTrustState(
    val highestRootVersion: Long,
    val highestMetadataSequence: Long,
    val trustedClock: TrustedClockRecord?,
    /** Opaque handle / digest of last accepted source artifact. */
    val sourceArtifactDigestHex: String?,
    val schemaMajorVersion: Int,
) {
    init {
        require(highestRootVersion >= 0L)
        require(highestMetadataSequence >= 0L)
        require(schemaMajorVersion >= 1)
        sourceArtifactDigestHex?.let {
            require(it.matches(Regex("^[0-9a-f]{64}$")))
        }
    }
}

/**
 * Root rotation / recovery policy (SEC-SUPPLY §1).
 */
object CatalogRootBootstrap {

    sealed class Outcome {
        data class Accepted(val state: CatalogTrustState) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    /**
     * Cold start: establish trust from APK-embedded root only.
     * Remote metadata must not replace bootstrap trust.
     */
    fun bootstrapFromEmbedded(
        embedded: EmbeddedCatalogRoot,
        nowEpochMs: Long,
        trustedClock: TrustedClockRecord? = null,
    ): Outcome {
        if (embedded.expiresAtEpochMs != null && nowEpochMs > embedded.expiresAtEpochMs) {
            // Completely offline + expired embedded root ⇒ fail closed for remote;
            // recovery requires manual/update path.
            return reject(
                OmniError.MODEL_REVOKED(
                    message = "embedded catalog root expired",
                    details = mapOf(
                        "rootVersion" to embedded.rootVersion.toString(),
                        "expiresAtEpochMs" to embedded.expiresAtEpochMs.toString(),
                    ),
                ),
                "embedded root expired",
            )
        }
        val state = CatalogTrustState(
            highestRootVersion = embedded.rootVersion,
            highestMetadataSequence = 0L,
            trustedClock = trustedClock,
            sourceArtifactDigestHex = embedded.metadataDigestHex,
            schemaMajorVersion = embedded.schemaMajorVersion,
        )
        return Outcome.Accepted(state)
    }

    /**
     * Accept a new root version only if it is exactly previous+1..N without skip
     * of intermediate signed roots (caller supplies ordered chain).
     *
     * [orderedNewRoots] must be strictly increasing by [EmbeddedCatalogRoot.rootVersion]
     * starting at current+1.
     */
    fun rotateRoot(
        current: CatalogTrustState,
        orderedNewRoots: List<EmbeddedCatalogRoot>,
        nowEpochMs: Long,
        maxSupportedSchemaMajor: Int,
    ): Outcome {
        if (orderedNewRoots.isEmpty()) {
            return reject(
                OmniError.INVALID_REQUEST(message = "empty root rotation chain"),
                "empty rotation chain",
            )
        }
        var expected = current.highestRootVersion + 1
        var last = current
        for (root in orderedNewRoots) {
            if (root.schemaMajorVersion > maxSupportedSchemaMajor) {
                return reject(
                    OmniError.CAPABILITY_UNKNOWN(
                        message = "unknown catalog root schema major",
                        details = mapOf(
                            "schemaMajorVersion" to root.schemaMajorVersion.toString(),
                            "maxSupported" to maxSupportedSchemaMajor.toString(),
                        ),
                    ),
                    "unknown schema major",
                )
            }
            if (root.rootVersion != expected) {
                return reject(
                    OmniError.INVALID_REQUEST(
                        message = "root rotation must not skip versions",
                        details = mapOf(
                            "expected" to expected.toString(),
                            "actual" to root.rootVersion.toString(),
                        ),
                    ),
                    "root version skip",
                )
            }
            if (root.expiresAtEpochMs != null && nowEpochMs > root.expiresAtEpochMs) {
                return reject(
                    OmniError.MODEL_REVOKED(message = "rotated root already expired"),
                    "rotated root expired",
                )
            }
            last = CatalogTrustState(
                highestRootVersion = root.rootVersion,
                highestMetadataSequence = last.highestMetadataSequence,
                trustedClock = last.trustedClock,
                sourceArtifactDigestHex = root.metadataDigestHex,
                schemaMajorVersion = root.schemaMajorVersion,
            )
            expected = root.rootVersion + 1
        }
        return Outcome.Accepted(last)
    }

    /**
     * Advance metadata sequence with anti-rollback within this install.
     */
    fun advanceSequence(
        current: CatalogTrustState,
        newSequence: Long,
        sourceArtifactDigestHex: String,
    ): Outcome {
        if (newSequence < 0L) {
            return reject(OmniError.INVALID_REQUEST(message = "sequence negative"), "negative sequence")
        }
        if (newSequence < current.highestMetadataSequence) {
            return reject(
                OmniError.INVALID_REQUEST(
                    message = "metadata sequence rollback rejected",
                    details = mapOf(
                        "current" to current.highestMetadataSequence.toString(),
                        "new" to newSequence.toString(),
                    ),
                ),
                "sequence rollback",
            )
        }
        if (!sourceArtifactDigestHex.matches(Regex("^[0-9a-f]{64}$"))) {
            return reject(OmniError.INVALID_REQUEST(message = "invalid source digest"), "bad digest")
        }
        return Outcome.Accepted(
            current.copy(
                highestMetadataSequence = newSequence,
                sourceArtifactDigestHex = sourceArtifactDigestHex,
            ),
        )
    }

    private fun reject(error: OmniError, reason: String): Outcome.Rejected =
        Outcome.Rejected(error = error, reason = reason)
}
