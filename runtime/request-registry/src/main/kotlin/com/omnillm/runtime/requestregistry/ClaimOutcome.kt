package com.omnillm.runtime.requestregistry

import com.omnillm.core.errors.generated.OmniError

/**
 * Result of claim-or-return on a durable ledger (ADR-004/005).
 *
 * - [New]: first acceptance of (principal, operationKind, idempotencyKey) + identity
 * - [Existing]: same claim key and canonical hash — return durable original (do not re-execute)
 * - [Conflict]: same key/identity with different canonical hash ⇒ IDEMPOTENCY_CONFLICT
 */
sealed class ClaimOutcome<out T> {
    data class New<T>(val value: T) : ClaimOutcome<T>()
    data class Existing<T>(val value: T) : ClaimOutcome<T>()
    data class Conflict(val error: OmniError.IDEMPOTENCY_CONFLICT) : ClaimOutcome<Nothing>()

    val isNew: Boolean get() = this is New
    val isExisting: Boolean get() = this is Existing
    val isConflict: Boolean get() = this is Conflict

    fun getOrNull(): T? = when (this) {
        is New -> value
        is Existing -> value
        is Conflict -> null
    }
}
