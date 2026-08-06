package com.omnillm.core.contracts

/**
 * Opaque / UUID identity newtypes from specs/canonical-types.yaml.
 *
 * Validation is fail-closed at parse boundaries. Formats intentionally stay
 * minimal: UUID kinds use RFC 4122 string form; opaque-handle kinds are
 * non-empty UTF-8 with a byte cap (wire/DB safe).
 */

private val UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private fun parseUuid(raw: String, label: String): String {
    val v = raw.lowercase()
    require(UUID_REGEX.matches(v)) { "$label must be a UUID string, got length=${raw.length}" }
    return v
}

private fun parseOpaque(raw: String, label: String, maxBytes: Int = 256): String {
    require(raw.isNotEmpty()) { "$label must be non-empty" }
    require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes) {
        "$label exceeds $maxBytes bytes"
    }
    return raw
}

/** Client-generated request identity (kind: uuid, generatedBy: client). */
@JvmInline
value class RequestId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): RequestId = RequestId(parseUuid(raw, "RequestId"))
        fun ofValidated(value: String): RequestId = RequestId(value)
    }
}

/**
 * Idempotency key (kind: string, maxBytes: 128).
 * Scope: principalId + operationKind (catalog).
 */
@JvmInline
value class IdempotencyKey private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_BYTES: Int = 128

        fun parse(raw: String): IdempotencyKey {
            require(raw.isNotEmpty()) { "IdempotencyKey must be non-empty" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "IdempotencyKey exceeds $MAX_BYTES bytes"
            }
            return IdempotencyKey(raw)
        }

        fun ofValidated(value: String): IdempotencyKey = IdempotencyKey(value)
    }
}

/** Plan handle (kind: opaque-handle, oneShot). */
@JvmInline
value class PlanId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): PlanId = PlanId(parseOpaque(raw, "PlanId"))
        fun ofValidated(value: String): PlanId = PlanId(value)
    }
}

/** Commit identity (kind: uuid, queryable). */
@JvmInline
value class CommitId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CommitId = CommitId(parseUuid(raw, "CommitId"))
        fun ofValidated(value: String): CommitId = CommitId(value)
    }
}

/**
 * Admin / durable mutation command identity (kind: uuid, client-generated).
 * Queryable via queryCommand after reply loss (ADR-004/005, CommandRequest).
 */
@JvmInline
value class CommandId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CommandId = CommandId(parseUuid(raw, "CommandId"))
        fun ofValidated(value: String): CommandId = CommandId(value)
    }
}

/**
 * Prepared operation handle (kind: opaque-handle, queryable, oneShotStartClaim).
 * Durable authority: prepared_operations.
 */
@JvmInline
value class PreparedOperationId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): PreparedOperationId =
            PreparedOperationId(parseOpaque(raw, "PreparedOperationId"))

        fun ofValidated(value: String): PreparedOperationId = PreparedOperationId(value)
    }
}

/** Authenticated caller principal (never a caller-supplied package name). */
@JvmInline
value class PrincipalId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): PrincipalId = PrincipalId(parseOpaque(raw, "PrincipalId", 512))
        fun ofValidated(value: String): PrincipalId = PrincipalId(value)
    }
}

/** Revision lease handle (kind: opaque-handle). */
@JvmInline
value class RevisionLeaseId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): RevisionLeaseId =
            RevisionLeaseId(parseOpaque(raw, "RevisionLeaseId"))

        fun ofValidated(value: String): RevisionLeaseId = RevisionLeaseId(value)
    }
}

/** Session handle (kind: opaque-handle). */
@JvmInline
value class SessionHandleId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): SessionHandleId =
            SessionHandleId(parseOpaque(raw, "SessionHandleId"))

        fun ofValidated(value: String): SessionHandleId = SessionHandleId(value)
    }
}

/** Asset handle (kind: opaque-handle). */
@JvmInline
value class AssetHandleId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): AssetHandleId = AssetHandleId(parseOpaque(raw, "AssetHandleId"))
        fun ofValidated(value: String): AssetHandleId = AssetHandleId(value)
    }
}

/** Engine build identity (kind: string — opaque versioned descriptor digest/string). */
@JvmInline
value class EngineBuildId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): EngineBuildId = EngineBuildId(parseOpaque(raw, "EngineBuildId", 512))
        fun ofValidated(value: String): EngineBuildId = EngineBuildId(value)
    }
}

/** Device execution fingerprint (kind: string). */
@JvmInline
value class DeviceExecutionFingerprint private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): DeviceExecutionFingerprint =
            DeviceExecutionFingerprint(parseOpaque(raw, "DeviceExecutionFingerprint", 1024))

        fun ofValidated(value: String): DeviceExecutionFingerprint =
            DeviceExecutionFingerprint(value)
    }
}
