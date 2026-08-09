package com.omnillm.core.ports.ledger

/**
 * States from OpenAPI CommandResult / schema CHECK on idempotent_commands / FSM COMMAND.
 * Shared by `:runtime:request-registry`, `:data:persistence` and `:interfaces:admin`
 * (ARC-02: transport facades must not compile against `:data:*` writers).
 */
object CommandLedgerStates {
    val ALL: Set<String> = setOf(
        "RECEIVED",
        "CLAIMED",
        "RUNNING",
        "RECONCILING",
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
        "UNCERTAIN",
    )

    /** Terminal states from specs/state-machines.yaml#COMMAND. */
    val TERMINAL: Set<String> = setOf(
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
        "UNCERTAIN",
    )
}

/**
 * Durable row for `idempotent_commands` (omnillm-schema.sql / ADR-004/005).
 * Port shape only — the SQLite adapter lives in `:data:persistence`.
 */
data class IdempotentCommandClaimRow(
    val commandId: String,
    val principalId: String,
    val operationKind: String,
    val idempotencyKey: String,
    val expectedVersion: Long? = null,
    val canonicalInputDigest: String,
    val state: String,
    val affectedResourceId: String? = null,
    val resultJson: String? = null,
    val errorCode: String? = null,
    val reconciliationDisposition: String? = null,
    val resourceVersion: Long = 0,
    val expiresAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
