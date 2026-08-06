package com.omnillm.data.persistence

/**
 * Control-plane JOB durable row models (FEAT-ADMIN / DATA-OWNERSHIP).
 *
 * Authority base: `specs/database/omnillm-schema.sql#jobs` / `job_attempts` / `job_events`.
 *
 * [canonicalSpecJson] is the control-plane job body envelope (digest + parameters +
 * progress + error + cancel + currentAttempt). Domain mapping lives in
 * `:runtime:job-manager` [com.omnillm.runtime.job.SqlDelightJobStore].
 */

/** JOB FSM states from specs/state-machines.yaml#JOB. */
object JobLedgerStates {
    val ALL: Set<String> = setOf(
        "QUEUED",
        "RUNNING",
        "PAUSED_WAITING_INPUT",
        "PAUSED_WAITING_NETWORK",
        "PAUSED_WAITING_FOREGROUND",
        "RECOVERING",
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
    )

    val TERMINAL: Set<String> = setOf("SUCCEEDED", "FAILED", "CANCELLED")
}

/**
 * Durable jobs table row.
 *
 * Claim key: (principalId, jobKind, idempotencyKey).
 */
data class JobLedgerRow(
    val jobId: String,
    val principalId: String,
    val jobKind: String,
    val idempotencyKey: String,
    val canonicalSpecJson: String,
    val state: String,
    val resourceVersion: Long,
    val checkpointJson: String?,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(jobId.isNotEmpty()) { "jobId must be non-empty" }
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(jobKind.isNotEmpty()) { "jobKind must be non-empty" }
        require(idempotencyKey.isNotEmpty()) { "idempotencyKey must be non-empty" }
        require(canonicalSpecJson.isNotEmpty()) { "canonicalSpecJson must be non-empty" }
        require(state in JobLedgerStates.ALL) { "unknown job state: $state" }
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
    }
}

data class JobAttemptLedgerRow(
    val jobId: String,
    val attemptNo: Int,
    val state: String,
    val startedAt: String,
    val endedAt: String? = null,
) {
    init {
        require(jobId.isNotEmpty()) { "jobId must be non-empty" }
        require(attemptNo >= 1) { "attemptNo must be >= 1" }
        require(state.isNotEmpty()) { "attempt state must be non-empty" }
        require(startedAt.isNotEmpty()) { "startedAt must be non-empty" }
    }
}

data class JobEventLedgerRow(
    val eventId: Long,
    val jobId: String,
    val attemptNo: Int?,
    val eventKind: String,
    val payloadJson: String?,
    val occurredAt: String,
) {
    init {
        require(jobId.isNotEmpty()) { "jobId must be non-empty" }
        require(eventId >= 1L) { "eventId must be >= 1" }
        require(eventKind.isNotEmpty()) { "eventKind must be non-empty" }
        require(occurredAt.isNotEmpty()) { "occurredAt must be non-empty" }
    }
}

/** Catalog trust / sequence projection (SEC-SUPPLY). */
data class CatalogTrustStateRow(
    val highestSequence: Long = 0L,
    val trustedClockEpochMs: Long? = null,
    val bootId: String? = null,
    val elapsedRealtimeAnchorMs: Long? = null,
    val uncertain: Boolean = true,
    val rootDigest: String? = null,
    val updatedAt: String,
)
