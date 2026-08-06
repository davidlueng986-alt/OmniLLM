package com.omnillm.features.diagnostics.api

/**
 * Client-generated command identity for Diagnostics mutations (ADR-004/005).
 * Mirrors Admin durable-command fields; claim key remains principal + kind + key.
 */
data class DiagnosticsCommandIdentity(
    val commandId: String,
    val idempotencyKey: String,
    /** Canonical input digest (hex SHA-256) of the mutation parameters. */
    val canonicalInputDigest: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(canonicalInputDigest.matches(HEX64)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
        expectedVersion?.let {
            require(it >= 0L) { "expectedVersion must be >= 0 when present" }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Start a redacted diagnostic export (OpenAPI createDiagnosticExport /
 * JobKind.DIAGNOSTIC_EXPORT). Client generates [jobId] and [bundleId] before send.
 */
data class StartExportSpec(
    val jobId: String,
    val bundleId: String,
    val includeDetail: Boolean = false,
    /** Category names from [com.omnillm.runtime.observability.DiagnosticAllowlist.Category]. */
    val categories: List<String> = emptyList(),
    val ttlSeconds: Int? = null,
    /** When true, UI has disclosed that share is irreversible. */
    val shareIrreversibleAcknowledged: Boolean = false,
    val command: DiagnosticsCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
        ttlSeconds?.let {
            require(it in 60..604_800) { "ttlSeconds must be 60..604800" }
        }
    }
}

/**
 * Cancel an in-flight diagnostic export job.
 * [requestOnly] marks cancel-requested when the worker cannot stop immediately.
 */
data class CancelExportSpec(
    val jobId: String,
    val requestOnly: Boolean = false,
    val command: DiagnosticsCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
    }
}

/**
 * Delete / expire a sealed bundle after drain of active share references
 * (FEAT-DIAGNOSTICS §4, retention-policy diagnostic-export).
 */
data class DeleteBundleSpec(
    val bundleId: String,
    val command: DiagnosticsCommandIdentity,
) {
    init {
        require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
    }
}

/**
 * Client-generated inference request identity (ADR-004/005).
 * Used when diagnostics UI launches a related inference or attaches request context.
 */
data class ClientInferenceIdentity(
    val requestId: String,
    val idempotencyKey: String,
) {
    init {
        require(requestId.matches(UUID_REGEX)) {
            "requestId must be a UUID string (client-generated)"
        }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= 128) {
            "idempotencyKey exceeds 128 bytes"
        }
    }

    companion object {
        private val UUID_REGEX =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
