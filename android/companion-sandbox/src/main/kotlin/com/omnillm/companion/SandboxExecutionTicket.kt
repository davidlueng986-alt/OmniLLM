package com.omnillm.companion

/**
 * Host-issued work ticket for companion (SEC-EXTERNAL-SANDBOX §3).
 *
 * Main app builds this; companion validates and reports package signer digest,
 * version, process instance, and supported protocol. Any mismatch fails closed.
 * Replay, stale runtime epoch, or different artifact IDs are rejected.
 *
 * Pure data — no Android types (unit-testable without instrumentation).
 */
data class SandboxExecutionTicket(
    val protocolMajor: Int,
    val protocolMinor: Int,
    /** Runtime instance identity (opaque, non-secret). */
    val runtimeInstanceId: String,
    val runtimeEpoch: Long,
    val bootId: String,
    /** Operation / durable commit id (claim ledger). */
    val operationId: String,
    val commitId: String,
    val engineBuildId: String,
    /** Model content digests / revision pins (hex or catalog ids — not paths). */
    val modelContentIds: List<String>,
    val backend: String,
    /** Opaque resource envelope summary (dimensions only; not a charge authority). */
    val resourceEnvelopeSummary: String,
    val operatingConstraintSummary: String,
    val monotonicDeadlineMs: Long,
    /** One-shot nonce; replay rejected. */
    val nonce: String,
    /** Placement class must be EXTERNAL_UID_ACCELERATED for this path. */
    val placementClass: String,
) {
    init {
        require(protocolMajor > 0) { "protocolMajor must be positive" }
        require(protocolMinor >= 0) { "protocolMinor must be non-negative" }
        require(runtimeInstanceId.isNotEmpty()) { "runtimeInstanceId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(bootId.isNotEmpty()) { "bootId must be non-empty" }
        require(operationId.isNotEmpty()) { "operationId must be non-empty" }
        require(commitId.isNotEmpty()) { "commitId must be non-empty" }
        require(engineBuildId.isNotEmpty()) { "engineBuildId must be non-empty" }
        require(modelContentIds.isNotEmpty() && modelContentIds.all { it.isNotEmpty() }) {
            "modelContentIds required"
        }
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(nonce.isNotEmpty()) { "nonce must be non-empty" }
        require(placementClass.isNotEmpty()) { "placementClass must be non-empty" }
        require(monotonicDeadlineMs >= 0L) { "monotonicDeadlineMs must be non-negative" }
    }
}

/**
 * Companion identity report returned after successful handshake (SEC-EXTERNAL-SANDBOX §3).
 */
data class CompanionIdentityReport(
    val packageName: String,
    val packageVersionName: String,
    val packageVersionCode: Long,
    /** Signer cert digest (hex) — host compares to expected same-signer set. */
    val signerDigestHex: String,
    val processInstanceId: String,
    val pid: Int,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val uid: Int,
) {
    init {
        require(packageName.isNotEmpty())
        require(processInstanceId.isNotEmpty())
        require(signerDigestHex.isNotEmpty())
        require(protocolMajor > 0)
        require(pid > 0)
    }
}

sealed class TicketValidationResult {
    data class Accepted(val reportHints: Map<String, String> = emptyMap()) : TicketValidationResult()

    data class Rejected(
        /** Catalog error code when applicable (e.g. TRUST_PLACEMENT_REQUIRED, INVALID_REQUEST). */
        val errorCode: String,
        val message: String,
    ) : TicketValidationResult()
}

/**
 * Pure ticket validation (SEC-EXTERNAL-SANDBOX §3, §7).
 * No I/O; host still performs signer/package probes separately.
 */
object CompanionTicketValidator {

    const val PLACEMENT_EXTERNAL_UID_ACCELERATED: String = "EXTERNAL_UID_ACCELERATED"

    fun validate(
        ticket: SandboxExecutionTicket,
        expectedRuntimeEpoch: Long,
        expectedBootId: String,
        expectedRuntimeInstanceId: String,
        claimedNonces: Set<String>,
        supportedProtocolMajor: Int = CompanionSandboxModule.PROTOCOL_MAJOR,
        supportedProtocolMinor: Int = CompanionSandboxModule.PROTOCOL_MINOR,
        nowMonotonicMs: Long,
    ): TicketValidationResult {
        if (ticket.protocolMajor != supportedProtocolMajor) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "protocol major mismatch",
            )
        }
        if (ticket.protocolMinor > supportedProtocolMinor) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "protocol minor not supported",
            )
        }
        if (ticket.runtimeInstanceId != expectedRuntimeInstanceId) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "runtime instance mismatch",
            )
        }
        if (ticket.bootId != expectedBootId) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "bootId mismatch",
            )
        }
        if (ticket.runtimeEpoch != expectedRuntimeEpoch) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "runtime epoch mismatch or replay",
            )
        }
        if (ticket.placementClass != PLACEMENT_EXTERNAL_UID_ACCELERATED) {
            return TicketValidationResult.Rejected(
                errorCode = "TRUST_PLACEMENT_REQUIRED",
                message = "companion only accepts EXTERNAL_UID_ACCELERATED",
            )
        }
        if (ticket.nonce in claimedNonces) {
            return TicketValidationResult.Rejected(
                errorCode = "IDEMPOTENCY_CONFLICT",
                message = "ticket nonce already claimed; query host ledger",
            )
        }
        if (nowMonotonicMs > ticket.monotonicDeadlineMs) {
            return TicketValidationResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "ticket deadline exceeded",
            )
        }
        return TicketValidationResult.Accepted()
    }
}
