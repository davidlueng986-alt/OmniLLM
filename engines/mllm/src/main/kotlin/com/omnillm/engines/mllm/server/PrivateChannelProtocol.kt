package com.omnillm.engines.mllm.server

/**
 * Private server channel protocol for mllm (ENGINE-MLLM §2, §7).
 *
 * The embedded Go `mllm_server` is an **engine-private** service. OmniLLM Adapter
 * owns lifecycle, channel selection, runtime credential, and canonical translation.
 * External HTTP/AIDL Gateway remains the only public entry (ADR-011).
 *
 * Prefer [ChannelKind.UNIX_DOMAIN] / private IPC. [ChannelKind.LOCALHOST_TCP] is
 * allowed only with port isolation + Host/auth + orphan cleanup — Host/CORS alone
 * is never sufficient.
 *
 * This object defines the **software contract** used by [ServerBackend] adapters.
 * It does not open sockets; real AAR wiring lives behind a future implementation.
 */
object PrivateChannelProtocol {

    /** Protocol schema id recorded into UPSTREAM.lock `serverProtocolDigest` inputs. */
    const val PROTOCOL_ID: String = "omnillm.mllm.private-channel"

    /** Wire / RPC schema version. Bumping this requires a new EngineBuildId. */
    const val PROTOCOL_VERSION: Int = 1

    /** Runtime credential length (bytes) before base64url encoding. */
    const val CREDENTIAL_BYTES: Int = 32

    /** Default auth header name when the pinned server uses HTTP framing. */
    const val AUTH_HEADER: String = "X-Omni-Mllm-Credential"

    /** Operation epoch header — prevents replay across runtime restarts. */
    const val EPOCH_HEADER: String = "X-Omni-Runtime-Epoch"

    /** Protocol version header for fail-closed negotiation. */
    const val VERSION_HEADER: String = "X-Omni-Mllm-Protocol"

    /**
     * Catalog of RPC methods mapped from OmniEngine operations.
     * Server without a separate plan keeps PLAN local (conservative envelope only).
     */
    object Methods {
        const val HEALTH: String = "health"
        const val PROBE: String = "probe"
        const val LOAD_MODEL: String = "load_model"
        const val CREATE_SESSION: String = "create_session"
        const val COMMIT_QUERY: String = "commit_query"
        const val GENERATE: String = "generate"
        const val GENERATE_CANCEL: String = "generate_cancel"
        const val EMBED: String = "embed"
        const val CLOSE_SESSION: String = "close_session"
        const val UNLOAD_MODEL: String = "unload_model"
        const val SHUTDOWN: String = "shutdown"

        val ALL: Set<String> = setOf(
            HEALTH,
            PROBE,
            LOAD_MODEL,
            CREATE_SESSION,
            COMMIT_QUERY,
            GENERATE,
            GENERATE_CANCEL,
            EMBED,
            CLOSE_SESSION,
            UNLOAD_MODEL,
            SHUTDOWN,
        )

        fun isKnown(method: String): Boolean = method in ALL
    }

    /**
     * Fail-closed channel policy checks before any execute path.
     * Returns null when allowed; otherwise a human-readable refusal reason.
     */
    fun refuseReason(
        kind: ChannelKind,
        bindHost: String?,
        hasRuntimeCredential: Boolean,
        allowLocalhostTcp: Boolean,
    ): String? {
        if (!hasRuntimeCredential) {
            return "runtime credential required (ENGINE-MLLM §7)"
        }
        when (kind) {
            ChannelKind.UNIX_DOMAIN, ChannelKind.PRIVATE_IPC -> return null
            ChannelKind.LOCALHOST_TCP -> {
                if (!allowLocalhostTcp) {
                    return "localhost TCP disabled by policy; prefer UDS/private IPC"
                }
                val host = bindHost?.lowercase()?.trim().orEmpty()
                if (host.isNotEmpty() && host != "127.0.0.1" && host != "localhost" && host != "::1") {
                    return "embedded server must not bind non-loopback host (got policy refusal)"
                }
                return null
            }
            ChannelKind.UNKNOWN -> return "channel kind UNKNOWN — fail closed"
        }
    }

    /**
     * Canonical digest material for lock `serverProtocolDigest` computation.
     * Humans pin the resulting SHA-256 of this catalog + version after freeze.
     */
    fun protocolCatalogFingerprint(): String =
        "$PROTOCOL_ID|v$PROTOCOL_VERSION|${Methods.ALL.sorted().joinToString(",")}"
}

/** Transport kinds for the private embedded server. */
enum class ChannelKind {
    /** Preferred: Unix domain socket / abstract namespace, process-local. */
    UNIX_DOMAIN,

    /** Preferred: same-UID private binder / pipe IPC (no TCP). */
    PRIVATE_IPC,

    /**
     * Fallback only: loopback TCP with isolation + strong runtime credential.
     * Never LAN-bind; never CORS wildcard; never unauthenticated management.
     */
    LOCALHOST_TCP,

    /** Unresolved / unconfigured — fail closed. */
    UNKNOWN,
}

/**
 * Runtime channel configuration owned by the Adapter (not by the UI process).
 * No secrets are persisted to OmniLLM DB; credential is process-ephemeral.
 */
data class PrivateChannelConfig(
    val kind: ChannelKind = ChannelKind.UNKNOWN,
    /** Loopback host when [ChannelKind.LOCALHOST_TCP]; ignored for UDS/IPC. */
    val bindHost: String? = null,
    /** Ephemeral port; 0 means OS-assigned (must not be published externally). */
    val port: Int = 0,
    /** Opaque UDS path key / abstract name — never a user-visible absolute path on wire. */
    val socketNameKey: String? = null,
    /** Runtime-generated credential (base64url or hex); never log raw value. */
    val runtimeCredential: String? = null,
    val runtimeEpoch: Long = 0L,
    val allowLocalhostTcp: Boolean = false,
    val protocolVersion: Int = PrivateChannelProtocol.PROTOCOL_VERSION,
) {
    init {
        require(port >= 0) { "port must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(protocolVersion > 0) { "protocolVersion must be positive" }
    }

    fun hasCredential(): Boolean = !runtimeCredential.isNullOrBlank()

    fun policyRefuseReason(): String? =
        PrivateChannelProtocol.refuseReason(
            kind = kind,
            bindHost = bindHost,
            hasRuntimeCredential = hasCredential(),
            allowLocalhostTcp = allowLocalhostTcp,
        )

    fun isPolicyAllowed(): Boolean = policyRefuseReason() == null
}

/** Lifecycle states for the embedded server process / AAR runtime. */
enum class ServerLifecycleState {
    STOPPED,
    STARTING,
    READY,
    DRAINING,
    FAILED,
    SHUTDOWN,
}

/**
 * Handshake request after channel open. Server must reject mismatched protocol
 * version and missing/invalid credential (fail closed).
 */
data class ChannelAuthRequest(
    val protocolVersion: Int = PrivateChannelProtocol.PROTOCOL_VERSION,
    val runtimeCredential: String,
    val runtimeEpoch: Long,
    val operationToken: String,
) {
    init {
        require(runtimeCredential.isNotEmpty())
        require(runtimeEpoch >= 0L)
        require(operationToken.isNotEmpty())
    }
}

data class ChannelAuthOutcome(
    val accepted: Boolean,
    val serverProtocolVersion: Int = PrivateChannelProtocol.PROTOCOL_VERSION,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Optional server-side commit query (ENGINE-MLLM §8).
 * When the pinned protocol lacks idempotent commit/query, adapters keep a
 * local ledger only and must not claim exactly-once.
 */
data class ServerCommitQueryRequest(
    val commitId: String,
    val operationToken: String,
) {
    init {
        require(commitId.isNotEmpty())
        require(operationToken.isNotEmpty())
    }
}

data class ServerCommitQueryOutcome(
    val commitId: String,
    val state: String,
    val attributes: Map<String, String> = emptyMap(),
)
