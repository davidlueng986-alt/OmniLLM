package com.omnillm.features.server.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.server.domain.DeveloperServerSnapshot
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Public API for UI / Admin composition (FEAT-SERVER).
 *
 * Semantics:
 * - Token plaintext returned only in bounded issuance receipt (TOKEN FSM).
 * - Client / token mutations go through control-plane ports (ADR-010).
 * - Inference smoke uses Orchestrator path — same scheduler as production
 *   (FEAT-SERVER §2: no Admin shortcut).
 * - Client generates requestId / idempotencyKey before send (ADR-004/005).
 * - Cancel / query on reply loss; never blind replay.
 * - Unknown capability ⇒ fail closed (INV-018).
 */
interface DeveloperServerApi {

    /** Current screen projection (empty / loading / ready / degraded / error). */
    fun snapshot(): DeveloperServerSnapshot

    /**
     * Refresh loopback status, clients, tokens, capabilities, and metrics.
     * Sets presentation to LOADING then READY / DEGRADED / ERROR / EMPTY.
     */
    suspend fun refresh(principal: PrincipalId = LocalUiPrincipal.ID): OmniResult<DeveloperServerSnapshot>

    /** Ensure loopback gateway is enabled/started (control-plane setting + lifecycle). */
    suspend fun ensureLoopbackStarted(
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<LoopbackServerStatus>

    /** Create named developer client + issue loopback token (plaintext once). */
    suspend fun createClientAndIssueToken(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: CreateDeveloperClientSpec,
    ): OmniResult<TokenIssuanceReceipt>

    /** Issue additional token for an existing client. */
    suspend fun issueToken(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: IssueTokenSpec,
    ): OmniResult<TokenIssuanceReceipt>

    /** Acknowledge receipt so UI clears plaintext (does not revoke token). */
    fun acknowledgeTokenReceipt()

    /** Revoke client registration (epoch fence). */
    suspend fun revokeClient(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: RevokeSpec,
    ): OmniResult<ClientSummaryView>

    /** Revoke token (epoch fence). */
    suspend fun revokeToken(
        principal: PrincipalId = LocalUiPrincipal.ID,
        spec: RevokeSpec,
    ): OmniResult<DeveloperTokenView>

    /**
     * CAPABILITY_NEGOTIATION: query model capabilities and classify
     * UNSUPPORTED / UNKNOWN / TEMPORARILY_UNAVAILABLE as blocking.
     * Does not submit inference.
     */
    suspend fun negotiateCapabilities(
        principal: PrincipalId = LocalUiPrincipal.ID,
        modelId: String?,
        requiredCapabilities: Set<String>,
    ): OmniResult<CapabilityNegotiationResult>

    /**
     * Smoke inference using production scheduler path.
     * [claim] must carry client-generated requestId + idempotencyKey.
     */
    suspend fun runSmokeInference(
        principal: PrincipalId = LocalUiPrincipal.ID,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult>

    /**
     * Cancel in-flight smoke / sample request (CANCELLATION capability).
     * Prefer [queryRequest] after cancel for durable terminal (ADR-004/005).
     */
    suspend fun cancelRequest(
        principal: PrincipalId = LocalUiPrincipal.ID,
        requestId: String,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult>

    /** Reply-loss safe query of last / given request. */
    suspend fun queryRequest(
        principal: PrincipalId = LocalUiPrincipal.ID,
        requestId: String,
    ): OmniResult<SmokeTestResult>

    /** Catalog of HTTP / AIDL SDK sample recipes for onboarding UI. */
    fun listSdkSamples(): List<SdkSampleRecipe>
}
