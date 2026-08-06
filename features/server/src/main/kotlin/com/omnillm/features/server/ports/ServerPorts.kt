package com.omnillm.features.server.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt

/**
 * Runtime ports for FEAT-SERVER.
 *
 * Implementations live in the control plane (ADR-010). The feature module
 * never opens DB, token vault, or loads native engines — it only composes
 * these ports (AGENTS.md Feature Pack rules).
 */
data class ServerRuntimePorts(
    val loopback: LoopbackServerPort,
    val tokens: TokenAdminPort,
    val clients: ClientAdminPort,
    val capabilities: CapabilityQueryPort,
    val inference: ServerInferencePort,
    val metrics: ServerMetricsPort,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/** Loopback gateway lifecycle / health (HTTP_INTERFACE). */
interface LoopbackServerPort {
    suspend fun status(): OmniResult<LoopbackServerStatus>

    /**
     * Ensure gateway is listening. Idempotent. Control plane sole writer of
     * settings + bind (server.loopbackEnabled).
     */
    suspend fun ensureStarted(principal: PrincipalId): OmniResult<LoopbackServerStatus>
}

/**
 * Token issue / list / revoke (tokens.manage).
 * Plaintext only in [TokenIssuanceReceipt]; store is HMAC verifier only.
 */
interface TokenAdminPort {
    suspend fun listTokens(principal: PrincipalId): OmniResult<List<DeveloperTokenView>>

    suspend fun issue(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        expiresInSeconds: Long,
        label: String?,
        command: ServerCommandIdentity,
    ): OmniResult<TokenIssuanceReceipt>

    suspend fun revoke(
        principal: PrincipalId,
        tokenId: String,
        command: ServerCommandIdentity,
    ): OmniResult<DeveloperTokenView>
}

/** Client registration list / create / revoke (clients.read / clients.manage). */
interface ClientAdminPort {
    suspend fun listClients(principal: PrincipalId): OmniResult<List<ClientSummaryView>>

    /**
     * Create ACTIVE developer client registration (or claim-or-return existing).
     * Does not issue token — pair with [TokenAdminPort.issue].
     */
    suspend fun createClient(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView>

    suspend fun revoke(
        principal: PrincipalId,
        clientId: String,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView>
}

/**
 * CAPABILITY_NEGOTIATION query surface.
 * Capabilities ride on model list (OpenAPI ModelInfo); fail closed on unknown ids.
 */
interface CapabilityQueryPort {
    suspend fun listModels(principal: PrincipalId): OmniResult<List<ModelCapabilityView>>
}

/**
 * Inference path for smoke / samples — **same Orchestrator scheduler** as
 * production (FEAT-SERVER §2). No Admin shortcut.
 */
interface ServerInferencePort {
    /**
     * Plan + submit with client-generated ids.
     * Unknown / unsupported required capability must fail closed before execute.
     */
    suspend fun submitSmoke(
        principal: PrincipalId,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult>

    suspend fun cancel(
        principal: PrincipalId,
        requestId: RequestId,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult>

    suspend fun query(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<SmokeTestResult>
}

/** Metrics with mandatory evidence labels (CORE-OBSERVABILITY). */
interface ServerMetricsPort {
    suspend fun summary(principal: PrincipalId): OmniResult<List<EvidencedMetricView>>
}
