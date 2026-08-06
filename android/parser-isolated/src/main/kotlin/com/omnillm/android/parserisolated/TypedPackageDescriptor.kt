package com.omnillm.android.parserisolated

/**
 * Versioned typed parser output (ARCH-TRUST-TOPOLOGY §5).
 *
 * Free JSON must **not** drive trust placement — only these bounded fields
 * plus separate control-plane trust evaluation (ADR-009).
 */
data class TypedPackageDescriptor(
    val schemaVersion: Int = SCHEMA_VERSION,
    val formatHint: String,
    val fileCount: Int,
    val totalDeclaredBytes: Long,
    val entries: List<TypedFileEntry>,
    val tensors: List<TypedTensorMeta> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val parseDurationMs: Long,
    val truncated: Boolean = false,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "unsupported TypedPackageDescriptor schemaVersion=$schemaVersion"
        }
        require(formatHint.isNotEmpty() && formatHint.length <= ParseBounds.MAX_STRING_FIELD)
        require(fileCount >= 0 && fileCount <= ParseBounds.MAX_FILE_COUNT)
        require(totalDeclaredBytes >= 0L)
        require(entries.size <= ParseBounds.MAX_FILE_COUNT)
        require(tensors.size <= ParseBounds.MAX_TENSOR_ENTRIES)
        require(attributes.size <= ParseBounds.MAX_DESCRIPTOR_ATTRS)
        require(parseDurationMs >= 0L)
        attributes.forEach { (k, v) ->
            require(k.length <= 64 && v.length <= ParseBounds.MAX_STRING_FIELD)
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

data class TypedFileEntry(
    val role: String,
    val relativeName: String,
    val byteLength: Long,
    val sha256Hex: String? = null,
    val contentSniff: String? = null,
) {
    init {
        require(role.isNotEmpty() && role.length <= 128)
        require(relativeName.isNotEmpty() && relativeName.length <= ParseBounds.MAX_PATH_COMPONENT)
        require(byteLength >= 0L)
        sha256Hex?.let {
            require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) {
                "sha256Hex must be 64 lowercase hex chars"
            }
        }
    }
}

data class TypedTensorMeta(
    val name: String,
    val dtype: String,
    val shape: List<Long>,
) {
    init {
        require(name.isNotEmpty() && name.length <= ParseBounds.MAX_STRING_FIELD)
        require(dtype.isNotEmpty() && dtype.length <= 64)
        require(shape.size <= ParseBounds.MAX_SHAPE_RANK)
        require(shape.all { it >= 0L })
    }
}

/**
 * Parse job request from runtime — FD tokens only, no paths/secrets.
 */
data class IsolatedParseRequest(
    val requestId: String,
    val runtimeEpoch: Long,
    val bootId: String,
    val formatHint: String,
    /** Opaque tokens mapping to read-only FDs dup'd by runtime. */
    val inputFdTokens: List<String>,
    val declaredRoles: List<String>,
    val declaredTotalBytes: Long,
    val deadlineMs: Long,
) {
    init {
        require(requestId.isNotEmpty())
        require(runtimeEpoch >= 0L)
        require(bootId.isNotEmpty())
        require(formatHint.isNotEmpty())
        require(inputFdTokens.isNotEmpty())
        require(inputFdTokens.size == declaredRoles.size)
        require(inputFdTokens.all { it.isNotEmpty() })
        require(declaredRoles.all { it.isNotEmpty() })
    }
}

sealed class IsolatedParseResult {
    data class Ok(val descriptor: TypedPackageDescriptor) : IsolatedParseResult()

    data class Err(
        /** Catalog error code string. */
        val errorCode: String,
        val message: String? = null,
    ) : IsolatedParseResult()
}
