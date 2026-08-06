package com.omnillm.runtime.job

import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId

/**
 * Canonical job identity envelope (FEAT-ADMIN §3).
 * Claim key: principal + job_kind + idempotency_key (omnillm-schema.sql `jobs`).
 * Client generates [jobId] and [idempotencyKey] before send (ADR-004/005).
 */
data class JobIdentity(
    val jobId: JobId,
    val principalId: PrincipalId,
    val kind: JobKind,
    val idempotencyKey: IdempotencyKey,
    /** Canonical input digest (hex SHA-256) of the job spec parameters. */
    val canonicalSpecDigest: String,
) {
    init {
        require(canonicalSpecDigest.matches(HEX64)) {
            "canonicalSpecDigest must be 64-char lower-case hex SHA-256"
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Kind-specific parameters (OpenAPI JobSpec oneOf). Opaque maps for pure
 * control-plane logic; transport adapters map DTOs into these records.
 */
sealed class JobParameters {
    data class Download(
        val sourceUrl: String,
        val expectedSha256: String? = null,
        val expectedBytes: Long? = null,
        val targetName: String? = null,
    ) : JobParameters() {
        init {
            require(sourceUrl.isNotBlank()) { "sourceUrl must be non-blank" }
            expectedSha256?.let {
                require(it.matches(HEX64)) { "expectedSha256 must be 64-char hex" }
            }
            expectedBytes?.let { require(it >= 0L) { "expectedBytes must be non-negative" } }
            targetName?.let {
                require(it.length <= 255) { "targetName maxLength 255" }
            }
        }
    }

    data class Import(
        val assetId: String,
        val expectedFormat: String? = null,
        val expectedSha256: String? = null,
    ) : JobParameters() {
        init {
            require(assetId.isNotBlank()) { "assetId must be non-blank" }
            expectedSha256?.let {
                require(it.matches(HEX64)) { "expectedSha256 must be 64-char hex" }
            }
        }
    }

    data class Benchmark(
        val modelRevisionId: String,
        val engineBuildId: String,
        val backend: String,
        val measurementProfileId: String,
        val iterations: Int? = null,
    ) : JobParameters() {
        init {
            require(modelRevisionId.matches(HEX64)) { "modelRevisionId must be 64-char hex" }
            require(engineBuildId.isNotBlank()) { "engineBuildId must be non-blank" }
            require(backend.isNotBlank()) { "backend must be non-blank" }
            require(measurementProfileId.matches(HEX64)) {
                "measurementProfileId must be 64-char hex"
            }
            iterations?.let {
                require(it in 1..10_000) { "iterations must be 1..10000" }
            }
        }
    }

    data class Delete(
        val resourceKind: DeleteResourceKind,
        val resourceId: String,
        val expectedResourceVersion: Long,
        val forceAfterDrain: Boolean = false,
    ) : JobParameters() {
        init {
            require(resourceId.isNotBlank()) { "resourceId must be non-blank" }
            require(expectedResourceVersion >= 0L) {
                "expectedResourceVersion must be non-negative"
            }
        }
    }

    data class DiagnosticExport(
        val includeDetail: Boolean = false,
        val categories: List<String> = emptyList(),
        val ttlSeconds: Int? = null,
    ) : JobParameters() {
        init {
            ttlSeconds?.let {
                require(it in 60..604_800) { "ttlSeconds must be 60..604800" }
            }
        }
    }

    data class ContentReport(
        val reportId: String,
    ) : JobParameters() {
        init {
            require(reportId.isNotBlank()) { "reportId must be non-blank" }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Progress dimensions from FEAT-ADMIN §4.
 * Unknown totals leave percentage unset (do not invent 0/100).
 */
data class JobProgress(
    val networkBytes: Long = 0L,
    val materializedBytes: Long = 0L,
    val verifiedBytes: Long = 0L,
    val parsedItems: Long = 0L,
    val sampleCount: Long = 0L,
    /** Catalog phase label (opaque string from worker/orchestrator). */
    val currentPhase: String? = null,
    val estimatedRemainingMs: Long? = null,
    /** Total expected bytes when known (download content-length, etc.). */
    val totalBytesKnown: Long? = null,
) {
    init {
        require(networkBytes >= 0L) { "networkBytes must be non-negative" }
        require(materializedBytes >= 0L) { "materializedBytes must be non-negative" }
        require(verifiedBytes >= 0L) { "verifiedBytes must be non-negative" }
        require(parsedItems >= 0L) { "parsedItems must be non-negative" }
        require(sampleCount >= 0L) { "sampleCount must be non-negative" }
        estimatedRemainingMs?.let {
            require(it >= 0L) { "estimatedRemainingMs must be non-negative" }
        }
        totalBytesKnown?.let {
            require(it >= 0L) { "totalBytesKnown must be non-negative" }
        }
    }

    /**
     * Ratio in [0, 1] only when [totalBytesKnown] is present and > 0.
     * Unknown totals return null (FEAT-ADMIN: 未知總量不顯示百分比).
     */
    fun ratioOrNull(): Double? {
        val total = totalBytesKnown ?: return null
        if (total <= 0L) return null
        val done = maxOf(verifiedBytes, materializedBytes, networkBytes)
        return (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }
}

/**
 * Durable checkpoint bound to job + attempt + payload hash
 * (JOB guard `checkpointValid`).
 *
 * Non-persistable PFD/URI permissions must not be promised across restarts
 * (FEAT-ADMIN §3, ARCH-RUNTIME-LIFECYCLE §5).
 */
data class JobCheckpoint(
    val attemptNo: Int,
    /** Canonical payload hash (hex SHA-256) of checkpoint body. */
    val payloadHash: String,
    /** Opaque durable resume cursor (bytes offset, part index, phase, etc.). */
    val resumeCursor: String,
    /** Optional JSON-shaped durable fields (no ephemeral FDs). */
    val durableFields: Map<String, String> = emptyMap(),
    val recordedAtEpochMs: Long,
) {
    init {
        require(attemptNo >= 1) { "attemptNo must be >= 1" }
        require(payloadHash.matches(HEX64)) { "payloadHash must be 64-char hex" }
        require(resumeCursor.isNotBlank()) { "resumeCursor must be non-blank" }
        require(recordedAtEpochMs >= 0L) { "recordedAtEpochMs must be non-negative" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

data class JobAttemptRecord(
    val attemptNo: Int,
    val state: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
) {
    init {
        require(attemptNo >= 1) { "attemptNo must be >= 1" }
        require(state.isNotBlank()) { "attempt state must be non-blank" }
        require(startedAtEpochMs >= 0L) { "startedAtEpochMs must be non-negative" }
        endedAtEpochMs?.let {
            require(it >= startedAtEpochMs) { "endedAtEpochMs must be >= startedAtEpochMs" }
        }
    }
}

data class JobEventRecord(
    val eventId: Long,
    val attemptNo: Int?,
    val eventKind: String,
    val payload: Map<String, String> = emptyMap(),
    val occurredAtEpochMs: Long,
)

/**
 * Full job snapshot for query / AdminSnapshot projection (FEAT-ADMIN §5).
 */
data class JobRecord(
    val identity: JobIdentity,
    val parameters: JobParameters,
    val state: String,
    val resourceVersion: Long,
    val currentAttemptNo: Int?,
    val attempts: List<JobAttemptRecord>,
    val checkpoint: JobCheckpoint?,
    val progress: JobProgress,
    val pauseReason: JobPauseReason?,
    val error: OmniError?,
    val events: List<JobEventRecord>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Cancel requested while phase cannot interrupt immediately. */
    val cancelRequested: Boolean = false,
) {
    val jobId: JobId get() = identity.jobId
    val kind: JobKind get() = identity.kind
    val isTerminal: Boolean
        get() = state == "SUCCEEDED" || state == "FAILED" || state == "CANCELLED"
}

/** Result of claim-or-return create (ADR-004/005). */
data class JobClaimResult(
    val record: JobRecord,
    /** True when this call inserted a new durable job row. */
    val createdNew: Boolean,
)
