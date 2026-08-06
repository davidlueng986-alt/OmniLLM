package com.omnillm.features.server.api

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError

/**
 * Loopback HTTP gateway status (FEAT-SERVER §2 / CORE-INTERFACE).
 * [baseUrl] is always literal loopback — never a LAN address.
 */
data class LoopbackServerStatus(
    val enabled: Boolean,
    val running: Boolean,
    val host: String,
    val port: Int,
    /** RUNTIME FSM projection: STOPPED / STARTING / READY / DEGRADED / … */
    val runtimeState: String,
    val resourceVersion: Long,
    val degradedReasons: List<String> = emptyList(),
) {
    init {
        require(port in 1..65535) { "port out of range" }
        require(host == "127.0.0.1" || host == "::1" || host == "localhost") {
            "loopback host must be literal loopback (got $host)"
        }
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
    }

    val baseUrl: String get() = "http://$host:$port"

    val isHealthy: Boolean
        get() = running && runtimeState == "READY" && degradedReasons.isEmpty()

    val isDegraded: Boolean
        get() = running && (runtimeState == "DEGRADED" || runtimeState == "RECOVERING" ||
            degradedReasons.isNotEmpty())
}

/**
 * CLIENT_REGISTRATION projection for developer client list (state-machines.yaml).
 * States are catalog-only — fail closed if unknown.
 */
data class ClientSummaryView(
    val clientId: String,
    val displayName: String,
    /** CLIENT_REGISTRATION state. */
    val state: String,
    val scopes: Set<String>,
    val revocationEpoch: Long,
    val lastSeenAtEpochMs: Long? = null,
    /** Observed UID evidence when AIDL-registered (display only). */
    val observedUidHint: String? = null,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        require(state in CLIENT_REGISTRATION_STATES) {
            "unknown CLIENT_REGISTRATION state (fail closed): $state"
        }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }

    companion object {
        val CLIENT_REGISTRATION_STATES: Set<String> = setOf(
            "PENDING",
            "ACTIVE",
            "SUSPENDED",
            "REVOCATION_REQUESTED",
            "DRAINING",
            "REVOKED",
            "EXPIRED",
        )
    }
}

/**
 * TOKEN FSM projection (no plaintext). Plaintext only on [TokenIssuanceReceipt].
 */
data class DeveloperTokenView(
    val tokenId: String,
    val clientId: String,
    /** TOKEN state. */
    val state: String,
    val scopes: Set<String>,
    val expiresAtEpochMs: Long,
    val revocationEpoch: Long,
    val loopbackOnly: Boolean = true,
    val label: String? = null,
    val lastSeenAtEpochMs: Long? = null,
) {
    init {
        require(tokenId.isNotBlank()) { "tokenId must be non-blank" }
        require(clientId.isNotBlank()) { "clientId must be non-blank" }
        require(state in TOKEN_STATES) {
            "unknown TOKEN state (fail closed): $state"
        }
        require(expiresAtEpochMs >= 0L) { "expiresAtEpochMs must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }

    companion object {
        val TOKEN_STATES: Set<String> = setOf(
            "ISSUING",
            "ACTIVE",
            "REVOCATION_REQUESTED",
            "DRAINING",
            "REVOKED",
            "EXPIRED",
            "FAILED",
        )
    }
}

/**
 * Bounded issuance receipt — plaintext shown once (TOKEN FSM invariant).
 * Feature never persists plaintext; control plane stores HMAC verifier only.
 *
 * [issuanceKey] enables one-time Secret Broker re-display via
 * [com.omnillm.runtime.policy.security.TokenService.takePlaintextOnce] until
 * [receiptExpiresAtEpochMs] or acknowledge; never durable.
 */
data class TokenIssuanceReceipt(
    val tokenId: String,
    val clientId: String,
    /** Plaintext bearer — UI must show once then clear. */
    val tokenPlaintext: String,
    val scopes: Set<String>,
    val expiresAtEpochMs: Long,
    val receiptExpiresAtEpochMs: Long,
    val revocationEpoch: Long,
    val loopbackOnly: Boolean = true,
    /** Opaque key for one-time plaintext re-display (not the bearer secret). */
    val issuanceKey: String? = null,
) {
    init {
        require(tokenId.isNotBlank()) { "tokenId must be non-blank" }
        require(clientId.isNotBlank()) { "clientId must be non-blank" }
        require(tokenPlaintext.isNotBlank()) { "tokenPlaintext must be non-blank" }
        require(scopes.isNotEmpty()) { "scopes must be non-empty" }
        require(expiresAtEpochMs >= 0L) { "expiresAtEpochMs must be non-negative" }
        require(receiptExpiresAtEpochMs >= 0L) { "receiptExpiresAtEpochMs must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }

    fun isReceiptExpired(nowEpochMs: Long): Boolean = nowEpochMs >= receiptExpiresAtEpochMs
}

/**
 * Model + capability cells for CAPABILITY_NEGOTIATION (FEAT-SERVER §7).
 * Client must not assume HTTP 200 ⇒ all fields supported.
 */
data class ModelCapabilityView(
    val modelId: String,
    val modelRevisionId: String?,
    val displayName: String?,
    val engineBuildId: String?,
    val backend: String?,
    val trustClass: String?,
    val capabilities: List<CapabilityCellView>,
    val degradationNotes: List<String> = emptyList(),
) {
    init {
        require(modelId.isNotBlank()) { "modelId must be non-blank" }
    }
}

data class CapabilityCellView(
    val capabilityId: String,
    val state: CapabilityState,
    /** Conditions when state is CONDITIONAL. */
    val conditions: List<String> = emptyList(),
    val evidenceLabel: EvidenceLabel = EvidenceLabel.REPORTED,
    val sampledAtEpochMs: Long = 0L,
    val source: String? = null,
) {
    init {
        require(capabilityId.isNotBlank()) { "capabilityId must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            // Source recommended; soft — port may omit for catalog-static cells.
        }
        if (state == CapabilityState.CONDITIONAL) {
            require(conditions.isNotEmpty()) {
                "CONDITIONAL capability must list conditions"
            }
        }
    }

    val isUsable: Boolean
        get() = state == CapabilityState.SUPPORTED || state == CapabilityState.CONDITIONAL
}

/**
 * Metric sample with mandatory evidence label (CORE-OBSERVABILITY).
 * UNKNOWN never rendered as 0.
 */
data class EvidencedMetricView(
    val name: String,
    val value: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val dimensions: Map<String, String> = emptyMap(),
    val source: String? = null,
) {
    init {
        require(name.isNotBlank()) { "metric name must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.UNKNOWN) {
            // value may be null — never invent 0 for UNKNOWN.
        } else {
            value?.let { require(it.isFinite()) { "metric value must be finite" } }
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED metric must disclose source"
            }
        }
    }

    /** Numeric display allowed only when evidence is not UNKNOWN. */
    fun displayValueOrNull(): Double? =
        if (evidenceLabel == EvidenceLabel.UNKNOWN) null else value
}

/** Client-generated create-client / issue-token command identity (ADR-004/005). */
data class ServerCommandIdentity(
    val commandId: String,
    val idempotencyKey: String,
) {
    init {
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
    }
}

data class CreateDeveloperClientSpec(
    val command: ServerCommandIdentity,
    val clientId: String,
    val displayName: String,
    val scopes: Set<String>,
    /** Token TTL seconds (OpenAPI 60..31536000). */
    val expiresInSeconds: Long,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        require(scopes.isNotEmpty()) { "scopes must be non-empty" }
        require(expiresInSeconds in 60L..31_536_000L) {
            "expiresInSeconds out of OpenAPI range"
        }
    }
}

data class IssueTokenSpec(
    val command: ServerCommandIdentity,
    val clientId: String,
    val displayName: String,
    val scopes: Set<String>,
    val expiresInSeconds: Long,
    val label: String? = null,
) {
    init {
        require(clientId.isNotBlank()) { "clientId must be non-blank" }
        require(displayName.isNotBlank()) { "displayName must be non-blank" }
        require(scopes.isNotEmpty()) { "scopes must be non-empty" }
        require(expiresInSeconds in 60L..31_536_000L) {
            "expiresInSeconds out of OpenAPI range"
        }
    }
}

data class RevokeSpec(
    val command: ServerCommandIdentity,
    val targetId: String,
) {
    init {
        require(targetId.isNotBlank()) { "targetId must be non-blank" }
    }
}

/**
 * Client-generated inference claim for smoke / samples (ADR-004/005).
 * [requestId] and [idempotencyKey] must be minted by the client before send.
 */
data class InferenceClaimSpec(
    val requestId: String,
    val idempotencyKey: String,
    val operationKind: String,
    /** Canonical request digest hex (64-char). */
    val canonicalRequestDigestHex: String,
    val requiredCapabilities: Set<String>,
    val modelRevisionIdHex: String,
    val deadlineMonotonic: Long,
    val runtimeEpoch: Long = 1L,
    val revocationEpoch: Long = 0L,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be client-generated non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be client-generated non-blank" }
        require(operationKind.isNotBlank()) { "operationKind must be non-blank" }
        require(canonicalRequestDigestHex.matches(HEX64)) {
            "canonicalRequestDigestHex must be 64-char hex"
        }
        require(modelRevisionIdHex.matches(HEX64)) {
            "modelRevisionIdHex must be 64-char hex"
        }
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
        require(requiredCapabilities.isNotEmpty()) {
            "requiredCapabilities must be non-empty"
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

data class SmokeTestResult(
    val step: String,
    val success: Boolean,
    val requestId: String?,
    val requestState: String?,
    val error: OmniError?,
    val completedAtEpochMs: Long,
    /** Actual engine/backend/revision when known (FEAT-SERVER §7 disclosure). */
    val actualModelRevisionId: String? = null,
    val actualEngineBuildId: String? = null,
    val actualBackend: String? = null,
) {
    init {
        require(step.isNotBlank()) { "step must be non-blank" }
        require(completedAtEpochMs >= 0L) { "completedAtEpochMs must be non-negative" }
    }
}

/** Outcome of capability negotiation before submit. */
data class CapabilityNegotiationResult(
    val usable: Boolean,
    val cells: List<CapabilityCellView>,
    val blocking: List<CapabilityBlocker>,
) {
    val hasUnsupported: Boolean
        get() = blocking.any { it.kind == CapabilityBlockerKind.UNSUPPORTED }

    val hasUnknown: Boolean
        get() = blocking.any { it.kind == CapabilityBlockerKind.UNKNOWN }
}

data class CapabilityBlocker(
    val capabilityId: String,
    val kind: CapabilityBlockerKind,
    val state: CapabilityState,
    val message: String,
) {
    init {
        require(capabilityId.isNotBlank()) { "capabilityId must be non-blank" }
        require(message.isNotBlank()) { "message must be non-blank" }
    }
}

enum class CapabilityBlockerKind {
    UNSUPPORTED,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE,
    MISSING,
}
