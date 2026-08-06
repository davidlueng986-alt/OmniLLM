package com.omnillm.features.benchmark.api

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.benchmark.domain.MeasurementProfile

/**
 * Client-generated command identity for Benchmark mutations (ADR-004/005).
 */
data class BenchmarkCommandIdentity(
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
 * Plan-only request (ADR-002 — no domain mutation).
 * UI templates expand into a full [MeasurementProfile].
 */
data class PlanBenchmarkSpec(
    val profile: MeasurementProfile,
    val templateId: String? = null,
    val iterations: Int? = null,
    val fallbackPolicy: String = FallbackPolicy.NONE.name,
    val allowedRevisionIds: Set<String> = emptySet(),
) {
    init {
        iterations?.let {
            require(it in 1..10_000) { "iterations must be 1..10000" }
        }
    }
}

/**
 * Start a BENCHMARK job. Client generates jobId / runId / idempotencyKey (ADR-004/005).
 * OpenAPI: JobSpec.kind = BENCHMARK + BenchmarkJobParameters.
 */
data class StartBenchmarkSpec(
    val jobId: String,
    val runId: String,
    val profile: MeasurementProfile,
    val iterations: Int? = null,
    val fallbackPolicy: String = FallbackPolicy.NONE.name,
    val allowedRevisionIds: Set<String> = emptySet(),
    val command: BenchmarkCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
        require(runId.isNotBlank()) { "runId must be non-blank" }
        iterations?.let {
            require(it in 1..10_000) { "iterations must be 1..10000" }
        }
    }
}

/**
 * Cancel a non-terminal benchmark job (JOB cancel edges).
 */
data class CancelBenchmarkSpec(
    val jobId: String,
    val requestOnly: Boolean = false,
    val command: BenchmarkCommandIdentity,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must be non-blank" }
    }
}

/**
 * Export a sealed measurement report (local-admin only; report ≠ telemetry).
 * Default excludes prompt/output raw text (FEAT-BENCHMARK §5).
 */
data class ExportReportSpec(
    val reportId: String,
    val profileId: String,
    val runIds: List<String> = emptyList(),
    val includePromptOutput: Boolean = false,
    val command: BenchmarkCommandIdentity,
) {
    init {
        require(reportId.isNotBlank()) { "reportId must be non-blank" }
        require(profileId.matches(HEX64)) { "profileId must be 64-char hex" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
