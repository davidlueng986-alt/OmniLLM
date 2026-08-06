// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

import com.omnillm.core.errors.generated.OmniError

/**
 * Canonical operation result. Cross-module suspend APIs return OmniResult<T>
 * rather than throwing for expected semantic failures.
 */
sealed class OmniResult<out T> {
    data class Ok<T>(val value: T) : OmniResult<T>()
    data class Err(val error: OmniError) : OmniResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    fun errorOrNull(): OmniError? = when (this) {
        is Ok -> null
        is Err -> error
    }

    inline fun <R> map(transform: (T) -> R): OmniResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun <R> flatMap(transform: (T) -> OmniResult<R>): OmniResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    inline fun getOrElse(default: (OmniError) -> @UnsafeVariance T): T = when (this) {
        is Ok -> value
        is Err -> default(error)
    }

    companion object {
        fun <T> ok(value: T): OmniResult<T> = Ok(value)
        fun err(error: OmniError): OmniResult<Nothing> = Err(error)
    }
}
