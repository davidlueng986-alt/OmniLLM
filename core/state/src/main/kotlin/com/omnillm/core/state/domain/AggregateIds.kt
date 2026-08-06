package com.omnillm.core.state.domain

/**
 * Lightweight identity wrappers for domain aggregates (DATA-DOMAIN, canonical-types).
 *
 * These are pure value types — no persistence. Digests use lower-case hex when
 * representing content-addressed identities; UUID-shaped ids are opaque non-empty
 * strings validated only for non-blank (full UUID grammar is a transport concern).
 */

@JvmInline
value class RequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "RequestId must be non-blank (client-generated)" }
    }
    override fun toString(): String = value
}

@JvmInline
value class CommitId(val value: String) {
    init {
        require(value.isNotBlank()) { "CommitId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class ReservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ReservationId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class InstallationId(val value: String) {
    init {
        require(value.isNotBlank()) { "InstallationId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class LoadedModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "LoadedModelId must be non-blank" }
    }
    override fun toString(): String = value
}

/** Opaque revision lease handle (canonical-types RevisionLeaseId; schema revision_leases). */
@JvmInline
value class RevisionLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "RevisionLeaseId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class JobId(val value: String) {
    init {
        require(value.isNotBlank()) { "JobId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "AssetId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId must be non-blank" }
    }
    override fun toString(): String = value
}

@JvmInline
value class RuntimeInstanceId(val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeInstanceId must be non-blank" }
    }
    override fun toString(): String = value
}

/** Owner partition key: principal + Android user + revision/load key binding (CORE-SESSION). */
@JvmInline
value class OwnerKey(val value: String) {
    init {
        require(value.isNotBlank()) { "OwnerKey must be non-blank" }
    }
    override fun toString(): String = value
}
