package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.data.persistence.ClaimLedgerPorts
import com.omnillm.data.persistence.CommandLedgerStates
import com.omnillm.data.persistence.IdempotentCommandClaimRow
import com.omnillm.data.persistence.SingleWriterPolicy

/**
 * Durable Command claim/result ledger for **all** durable mutations
 * (CORE-INTERFACE, REL-RECOVERY, command-conformance-fixtures).
 *
 * Claim key: `(principalId, operationKind, idempotencyKey)` + [commandId].
 * Same key / different [canonicalInputDigest] ⇒ IDEMPOTENCY_CONFLICT.
 * Result is written durable **before** reply; reply loss uses [queryCommand] only —
 * never mint a new commandId and blind-replay non-idempotent mutations (ADR-004/005).
 */
class CommandLedger(
    private val ports: ClaimLedgerPorts,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {
    init {
        SingleWriterPolicy.assertWriterAllowed(ports.writerRole)
    }

    /**
     * Claim-or-return for a durable admin / mutation command.
     *
     * @param expectedVersion omitted only when the target resource does not yet exist
     */
    fun claim(
        principal: PrincipalId,
        operationKind: String,
        idempotencyKey: IdempotencyKey,
        canonicalHash: Sha256Digest,
        commandId: CommandId,
        expectedVersion: Long? = null,
        expiresAt: String? = null,
    ): ClaimOutcome<IdempotentCommandClaimRow> {
        require(operationKind.isNotEmpty()) { "operationKind must be non-empty" }
        expectedVersion?.let {
            require(it >= 0L) { "expectedVersion must be >= 0 when present" }
        }
        val now = clock()
        val digest = canonicalHash.hex
        return ports.tx.inTransaction {
            val byId = ports.commands.findByCommandId(commandId.value)
            val byClaim = ports.commands.findByClaimKey(
                principalId = principal.value,
                operationKind = operationKind,
                idempotencyKey = idempotencyKey.value,
            )

            if (byId != null) {
                return@inTransaction if (
                    byId.canonicalInputDigest == digest &&
                    byId.principalId == principal.value &&
                    byId.operationKind == operationKind &&
                    byId.idempotencyKey == idempotencyKey.value
                ) {
                    ClaimOutcome.Existing(byId)
                } else {
                    conflict(
                        "commandId collision with different claim payload",
                        commandId = commandId.value,
                    )
                }
            }

            if (byClaim != null) {
                return@inTransaction if (
                    byClaim.canonicalInputDigest == digest &&
                    byClaim.commandId == commandId.value
                ) {
                    ClaimOutcome.Existing(byClaim)
                } else {
                    conflict(
                        "idempotency claim conflict for command",
                        principalId = principal.value,
                        operationKind = operationKind,
                        idempotencyKey = idempotencyKey.value,
                    )
                }
            }

            val row = IdempotentCommandClaimRow(
                commandId = commandId.value,
                principalId = principal.value,
                operationKind = operationKind,
                idempotencyKey = idempotencyKey.value,
                expectedVersion = expectedVersion,
                canonicalInputDigest = digest,
                state = StateMachines.COMMAND.initial, // RECEIVED
                affectedResourceId = null,
                resultJson = null,
                errorCode = null,
                reconciliationDisposition = null,
                resourceVersion = 0,
                expiresAt = expiresAt,
                createdAt = now,
                updatedAt = now,
            )
            ports.commands.insert(row)
            ClaimOutcome.New(row)
        }
    }

    /** Query durable command by client-generated [commandId] (reply-loss path). */
    fun queryCommand(commandId: CommandId): IdempotentCommandClaimRow? =
        ports.commands.findByCommandId(commandId.value)

    /**
     * Persist command result **before** transport reply (publishResult action).
     * Terminal states: SUCCEEDED | FAILED | CANCELLED | UNCERTAIN.
     */
    fun recordResult(
        commandId: CommandId,
        state: String,
        resultJson: String? = null,
        errorCode: String? = null,
        affectedResourceId: String? = null,
        reconciliationDisposition: String? = null,
    ): OmniResult<IdempotentCommandClaimRow> {
        if (state !in CommandLedgerStates.ALL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown command state: $state",
                    details = mapOf("state" to state),
                ),
            )
        }
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.commands.findByCommandId(commandId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "command not found",
                        details = mapOf("commandId" to commandId.value),
                    ),
                )

            if (existing.state in CommandLedgerStates.TERMINAL) {
                // Idempotent re-publish of identical terminal result.
                if (
                    existing.state == state &&
                    existing.resultJson == resultJson &&
                    existing.errorCode == errorCode &&
                    existing.affectedResourceId == affectedResourceId
                ) {
                    return@inTransaction OmniResult.ok(existing)
                }
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "command already terminal",
                        details = mapOf(
                            "commandId" to commandId.value,
                            "state" to existing.state,
                        ),
                    ),
                )
            }

            val nextVersion = existing.resourceVersion + 1
            ports.commands.updateResult(
                commandId = commandId.value,
                state = state,
                resultJson = resultJson,
                errorCode = errorCode,
                affectedResourceId = affectedResourceId,
                reconciliationDisposition = reconciliationDisposition,
                resourceVersion = nextVersion,
                updatedAt = now,
            )
            OmniResult.ok(ports.commands.findByCommandId(commandId.value)!!)
        }
    }

    /** Non-terminal state advance (e.g. RECEIVED → CLAIMED → RUNNING). */
    fun updateState(commandId: CommandId, state: String): OmniResult<IdempotentCommandClaimRow> {
        if (state !in CommandLedgerStates.ALL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown command state: $state",
                    details = mapOf("state" to state),
                ),
            )
        }
        if (state in CommandLedgerStates.TERMINAL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "use recordResult for terminal command states",
                    details = mapOf("state" to state),
                ),
            )
        }
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.commands.findByCommandId(commandId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "command not found",
                        details = mapOf("commandId" to commandId.value),
                    ),
                )
            if (existing.state in CommandLedgerStates.TERMINAL) {
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "command is already terminal",
                        details = mapOf(
                            "commandId" to commandId.value,
                            "state" to existing.state,
                        ),
                    ),
                )
            }
            ports.commands.updateResult(
                commandId = commandId.value,
                state = state,
                resultJson = existing.resultJson,
                errorCode = existing.errorCode,
                affectedResourceId = existing.affectedResourceId,
                reconciliationDisposition = existing.reconciliationDisposition,
                resourceVersion = existing.resourceVersion + 1,
                updatedAt = now,
            )
            OmniResult.ok(ports.commands.findByCommandId(commandId.value)!!)
        }
    }

    private fun conflict(
        message: String,
        commandId: String? = null,
        principalId: String? = null,
        operationKind: String? = null,
        idempotencyKey: String? = null,
    ): ClaimOutcome.Conflict {
        val details = buildMap {
            commandId?.let { put("commandId", it) }
            principalId?.let { put("principalId", it) }
            operationKind?.let { put("operationKind", it) }
            idempotencyKey?.let { put("idempotencyKey", it) }
        }
        return ClaimOutcome.Conflict(
            OmniError.IDEMPOTENCY_CONFLICT(message = message, details = details),
        )
    }
}
