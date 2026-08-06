package com.omnillm.data.modelstore

import com.omnillm.core.errors.generated.OmniError

/**
 * Formal quarantine admission rules (SEC-INPUT §5–§6, SEC-002, DATA-STORAGE §2).
 *
 * [MaterializeBounds] supplies numeric floors; this object encodes the
 * structural fail-closed checks applied before / during materialize:
 * - no symlink / hardlink / special files
 * - path length / nested directory depth
 * - file count / total / individual size
 * - sparse allocation rejection (declared vs actual size)
 * - manifest FD list matched one-to-one with size/digest
 *
 * Trust elevation is **not** performed here (ADR-009).
 */
data class QuarantineRules(
    val bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
    val maxPathLength: Int = DEFAULT_MAX_PATH_LENGTH,
    val maxNestedDirectories: Int = DEFAULT_MAX_NESTED_DIRECTORIES,
    val maxPathComponentLength: Int = DEFAULT_MAX_PATH_COMPONENT,
    val rejectSymlinks: Boolean = true,
    val rejectHardlinks: Boolean = true,
    val rejectSpecialFiles: Boolean = true,
    val rejectSparseWhenDeclaredKnown: Boolean = true,
    val requireManifestFdOneToOne: Boolean = true,
) {
    init {
        require(maxPathLength > 0)
        require(maxNestedDirectories > 0)
        require(maxPathComponentLength > 0)
    }

    companion object {
        const val DEFAULT_MAX_PATH_LENGTH: Int = 512
        const val DEFAULT_MAX_NESTED_DIRECTORIES: Int = 8
        const val DEFAULT_MAX_PATH_COMPONENT: Int = 255

        val DEFAULT: QuarantineRules = QuarantineRules()
    }
}

/**
 * Declared entry for rule checks (manifest side).
 */
data class QuarantineDeclaredEntry(
    val role: String,
    val relativePath: String? = null,
    val expectedByteLength: Long,
    val expectedSha256Hex: String? = null,
    val shardIndex: Int = 0,
) {
    init {
        require(role.isNotEmpty())
        require(expectedByteLength >= 0L)
        require(shardIndex >= 0)
    }
}

/**
 * Observed file facts after fstat / materialize (platform adapter fills).
 */
data class QuarantineObservedFile(
    val role: String,
    val relativePath: String? = null,
    val actualByteLength: Long,
    val actualSha256Hex: String? = null,
    val isRegularFile: Boolean,
    val isSymlink: Boolean = false,
    val isHardlinkSuspect: Boolean = false,
    val isSpecialFile: Boolean = false,
    /** True when allocated blocks imply sparse (optional platform signal). */
    val appearsSparse: Boolean = false,
    val shardIndex: Int = 0,
) {
    init {
        require(role.isNotEmpty())
        require(actualByteLength >= 0L)
        require(shardIndex >= 0)
    }
}

object QuarantineRuleEngine {

    sealed class Outcome {
        data class Accepted(
            val declaredCount: Int,
            val totalBytes: Long,
        ) : Outcome()

        data class Rejected(
            val error: OmniError,
            val reason: String,
            val details: Map<String, String> = emptyMap(),
        ) : Outcome()
    }

    /**
     * Admit declared manifest list before any I/O.
     */
    fun admitDeclared(
        declared: List<QuarantineDeclaredEntry>,
        rules: QuarantineRules = QuarantineRules.DEFAULT,
    ): Outcome {
        if (declared.isEmpty()) {
            return rejectInvalid("declared files empty")
        }
        if (declared.size > rules.bounds.maxFileCount) {
            return rejectTooLarge(
                "declared file count exceeds cap",
                mapOf(
                    "fileCount" to declared.size.toString(),
                    "maxFileCount" to rules.bounds.maxFileCount.toString(),
                ),
            )
        }
        val roles = HashSet<String>()
        var total = 0L
        for (d in declared) {
            val slot = "${d.role}|${d.shardIndex}"
            if (!roles.add(slot)) {
                return rejectInvalid(
                    "duplicate role/shardIndex",
                    mapOf("role" to d.role, "shardIndex" to d.shardIndex.toString()),
                )
            }
            try {
                PathSafety.requireRole(d.role)
            } catch (e: IllegalArgumentException) {
                return rejectInvalid(e.message ?: "invalid role")
            }
            d.relativePath?.let { rel ->
                try {
                    PathSafety.validateRelativePath(
                        rel,
                        maxDepth = rules.maxNestedDirectories,
                        maxComponent = rules.maxPathComponentLength,
                    )
                } catch (e: IllegalArgumentException) {
                    return rejectInvalid(e.message ?: "invalid relative path")
                }
                if (rel.length > rules.maxPathLength) {
                    return rejectTooLarge(
                        "relative path too long",
                        mapOf(
                            "length" to rel.length.toString(),
                            "maxPathLength" to rules.maxPathLength.toString(),
                        ),
                    )
                }
            }
            if (d.expectedByteLength > rules.bounds.maxFileBytes) {
                return rejectTooLarge(
                    "file exceeds individual size cap",
                    mapOf(
                        "role" to d.role,
                        "bytes" to d.expectedByteLength.toString(),
                        "maxFileBytes" to rules.bounds.maxFileBytes.toString(),
                    ),
                )
            }
            total += d.expectedByteLength
            if (total > rules.bounds.maxTotalBytes) {
                return rejectTooLarge(
                    "declared total exceeds cap",
                    mapOf(
                        "totalBytes" to total.toString(),
                        "maxTotalBytes" to rules.bounds.maxTotalBytes.toString(),
                    ),
                )
            }
            d.expectedSha256Hex?.let {
                try {
                    PathSafety.requireDigestHex(it, "expectedSha256Hex")
                } catch (e: IllegalArgumentException) {
                    return rejectInvalid(e.message ?: "invalid digest")
                }
            }
        }
        return Outcome.Accepted(declaredCount = declared.size, totalBytes = total)
    }

    /**
     * Admit observed materialize facts against declared manifest (identity only).
     */
    fun admitObserved(
        declared: List<QuarantineDeclaredEntry>,
        observed: List<QuarantineObservedFile>,
        rules: QuarantineRules = QuarantineRules.DEFAULT,
    ): Outcome {
        val declaredAdmit = admitDeclared(declared, rules)
        if (declaredAdmit is Outcome.Rejected) return declaredAdmit

        if (rules.requireManifestFdOneToOne && declared.size != observed.size) {
            return rejectInvalid(
                "manifest/observed count mismatch",
                mapOf(
                    "declared" to declared.size.toString(),
                    "observed" to observed.size.toString(),
                ),
            )
        }

        val bySlot = declared.associateBy { "${it.role}|${it.shardIndex}" }
        val seen = HashSet<String>()
        var total = 0L
        for (o in observed) {
            val slot = "${o.role}|${o.shardIndex}"
            if (!seen.add(slot)) {
                return rejectInvalid("duplicate observed role/shardIndex", mapOf("role" to o.role))
            }
            val d = bySlot[slot]
                ?: return rejectInvalid("observed role not in manifest", mapOf("role" to o.role))

            if (rules.rejectSymlinks && o.isSymlink) {
                return rejectInvalid("symlink rejected", mapOf("role" to o.role))
            }
            if (rules.rejectHardlinks && o.isHardlinkSuspect) {
                return rejectInvalid("hardlink rejected", mapOf("role" to o.role))
            }
            if (rules.rejectSpecialFiles && (o.isSpecialFile || !o.isRegularFile)) {
                return rejectInvalid("special/non-regular file rejected", mapOf("role" to o.role))
            }
            if (o.actualByteLength != d.expectedByteLength) {
                return rejectInvalid(
                    "size mismatch",
                    mapOf(
                        "role" to o.role,
                        "expected" to d.expectedByteLength.toString(),
                        "actual" to o.actualByteLength.toString(),
                    ),
                )
            }
            if (rules.rejectSparseWhenDeclaredKnown && o.appearsSparse) {
                return rejectInvalid("sparse allocation rejected", mapOf("role" to o.role))
            }
            val expDigest = d.expectedSha256Hex
            val actDigest = o.actualSha256Hex
            if (expDigest != null && actDigest != null) {
                val exp = expDigest.lowercase()
                val act = actDigest.lowercase()
                if (exp != act) {
                    return rejectInvalid(
                        "digest mismatch",
                        mapOf("role" to o.role),
                    )
                }
            }
            if (o.actualByteLength > rules.bounds.maxFileBytes) {
                return rejectTooLarge(
                    "actual file exceeds cap",
                    mapOf("role" to o.role),
                )
            }
            total += o.actualByteLength
            if (total > rules.bounds.maxTotalBytes) {
                return rejectTooLarge("actual total exceeds cap")
            }
        }
        if (rules.requireManifestFdOneToOne && seen.size != bySlot.size) {
            return rejectInvalid("not all declared roles observed")
        }
        return Outcome.Accepted(declaredCount = declared.size, totalBytes = total)
    }

    private fun rejectInvalid(
        reason: String,
        details: Map<String, String> = emptyMap(),
    ): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.INVALID_REQUEST(message = reason, details = details),
            reason = reason,
            details = details,
        )

    private fun rejectTooLarge(
        reason: String,
        details: Map<String, String> = emptyMap(),
    ): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.TRANSPORT_TOO_LARGE(message = reason, details = details),
            reason = reason,
            details = details,
        )
}
