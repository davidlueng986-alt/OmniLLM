package com.omnillm.features.server.usecase

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.CreateDeveloperClientSpec
import com.omnillm.features.server.api.DeveloperServerApi
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.IssueTokenSpec
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.RevokeSpec
import com.omnillm.features.server.api.SdkSampleCatalog
import com.omnillm.features.server.api.SdkSampleRecipe
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.api.CapabilityNegotiationResult
import com.omnillm.features.server.domain.DeveloperServerSnapshot
import com.omnillm.features.server.domain.ScopeCatalog
import com.omnillm.features.server.domain.ScopeValidation
import com.omnillm.features.server.domain.ServerScreenPhase
import com.omnillm.features.server.ports.ServerRuntimePorts
import com.omnillm.features.server.projection.ServerStateProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Default [DeveloperServerApi] (FEAT-SERVER use-cases).
 *
 * Holds screen projection only — durable token/client/request mutations go
 * through ports owned by the runtime control plane (ADR-010).
 */
class DeveloperServerService(
    private val ports: ServerRuntimePorts,
) : DeveloperServerApi {

    private val lock = Any()

    private var hasRefreshed: Boolean = false
    private var loading: Boolean = false
    private var loopback: LoopbackServerStatus? = null
    private var clients: List<ClientSummaryView> = emptyList()
    private var tokens: List<DeveloperTokenView> = emptyList()
    private var pendingReceipt: TokenIssuanceReceipt? = null
    private var capabilities: List<ModelCapabilityView> = emptyList()
    private var metrics: List<EvidencedMetricView> = emptyList()
    private var lastSmoke: SmokeTestResult? = null
    private var lastRequestId: String? = null
    private var lastRequestState: String? = null
    private var lastError: OmniError? = null

    override fun snapshot(): DeveloperServerSnapshot = synchronized(lock) { projectLocked() }

    override suspend fun refresh(principal: PrincipalId): OmniResult<DeveloperServerSnapshot> {
        requireLocalUi(principal)
        setLoading(true)
        val status = ports.loopback.status()
        val clientList = ports.clients.listClients(principal)
        val tokenList = ports.tokens.listTokens(principal)
        val models = ports.capabilities.listModels(principal)
        val metricList = ports.metrics.summary(principal)

        val err = firstErr(status, clientList, tokenList, models, metricList)
        synchronized(lock) {
            loading = false
            hasRefreshed = true
            if (status is OmniResult.Ok) loopback = status.value
            if (clientList is OmniResult.Ok) clients = clientList.value
            if (tokenList is OmniResult.Ok) tokens = tokenList.value
            if (models is OmniResult.Ok) capabilities = models.value
            if (metricList is OmniResult.Ok) metrics = metricList.value
            // Drop expired plaintext receipt.
            pendingReceipt = pendingReceipt?.takeUnless {
                it.isReceiptExpired(ports.clockMs())
            }
            lastError = err
        }
        val snap = snapshot()
        return if (err != null && snap.presentation == ServerScreenPhase.ERROR) {
            OmniResult.err(err)
        } else {
            OmniResult.ok(snap)
        }
    }

    override suspend fun ensureLoopbackStarted(
        principal: PrincipalId,
    ): OmniResult<LoopbackServerStatus> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.loopback.ensureStarted(principal)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    hasRefreshed = true
                    loopback = r.value
                    lastError = null
                }
                r
            }
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
        }
    }

    override suspend fun createClientAndIssueToken(
        principal: PrincipalId,
        spec: CreateDeveloperClientSpec,
    ): OmniResult<TokenIssuanceReceipt> {
        requireLocalUi(principal)
        when (val v = ScopeCatalog.validateScopes(spec.scopes)) {
            is ScopeValidation.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = v.message,
                    details = buildMap {
                        if (v.unknown.isNotEmpty()) put("unknown", v.unknown.joinToString(","))
                        if (v.disallowed.isNotEmpty()) put("disallowed", v.disallowed.joinToString(","))
                    },
                )
                fail(err)
                return OmniResult.err(err)
            }
            is ScopeValidation.Ok -> {
                val scopes = v.scopes
                setLoading(true)
                return when (
                    val created = ports.clients.createClient(
                        principal = principal,
                        clientId = spec.clientId,
                        displayName = spec.displayName,
                        scopes = scopes,
                        command = spec.command,
                    )
                ) {
                    is OmniResult.Err -> {
                        fail(created.error)
                        created
                    }
                    is OmniResult.Ok -> {
                        when (
                            val issued = ports.tokens.issue(
                                principal = principal,
                                clientId = created.value.clientId,
                                displayName = spec.displayName,
                                scopes = scopes,
                                expiresInSeconds = spec.expiresInSeconds,
                                label = spec.displayName,
                                command = ServerCommandIdentity(
                                    commandId = spec.command.commandId,
                                    idempotencyKey = spec.command.idempotencyKey + ":token",
                                ),
                            )
                        ) {
                            is OmniResult.Err -> {
                                fail(issued.error)
                                issued
                            }
                            is OmniResult.Ok -> {
                                synchronized(lock) {
                                    loading = false
                                    lastError = null
                                    pendingReceipt = issued.value
                                    clients = (clients.filterNot { it.clientId == created.value.clientId } +
                                        created.value)
                                    tokens = tokens.filterNot { it.tokenId == issued.value.tokenId } +
                                        DeveloperTokenView(
                                            tokenId = issued.value.tokenId,
                                            clientId = issued.value.clientId,
                                            state = "ACTIVE",
                                            scopes = issued.value.scopes,
                                            expiresAtEpochMs = issued.value.expiresAtEpochMs,
                                            revocationEpoch = issued.value.revocationEpoch,
                                            loopbackOnly = issued.value.loopbackOnly,
                                            label = spec.displayName,
                                        )
                                }
                                issued
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun issueToken(
        principal: PrincipalId,
        spec: IssueTokenSpec,
    ): OmniResult<TokenIssuanceReceipt> {
        requireLocalUi(principal)
        when (val v = ScopeCatalog.validateScopes(spec.scopes)) {
            is ScopeValidation.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = v.message,
                    details = buildMap {
                        if (v.unknown.isNotEmpty()) put("unknown", v.unknown.joinToString(","))
                        if (v.disallowed.isNotEmpty()) put("disallowed", v.disallowed.joinToString(","))
                    },
                )
                fail(err)
                return OmniResult.err(err)
            }
            is ScopeValidation.Ok -> {
                setLoading(true)
                return when (
                    val issued = ports.tokens.issue(
                        principal = principal,
                        clientId = spec.clientId,
                        displayName = spec.displayName,
                        scopes = v.scopes,
                        expiresInSeconds = spec.expiresInSeconds,
                        label = spec.label,
                        command = spec.command,
                    )
                ) {
                    is OmniResult.Err -> {
                        fail(issued.error)
                        issued
                    }
                    is OmniResult.Ok -> {
                        synchronized(lock) {
                            loading = false
                            lastError = null
                            pendingReceipt = issued.value
                            tokens = tokens.filterNot { it.tokenId == issued.value.tokenId } +
                                DeveloperTokenView(
                                    tokenId = issued.value.tokenId,
                                    clientId = issued.value.clientId,
                                    state = "ACTIVE",
                                    scopes = issued.value.scopes,
                                    expiresAtEpochMs = issued.value.expiresAtEpochMs,
                                    revocationEpoch = issued.value.revocationEpoch,
                                    loopbackOnly = issued.value.loopbackOnly,
                                    label = spec.label,
                                )
                        }
                        issued
                    }
                }
            }
        }
    }

    override fun acknowledgeTokenReceipt() {
        synchronized(lock) {
            pendingReceipt = null
        }
    }

    override suspend fun revokeClient(
        principal: PrincipalId,
        spec: RevokeSpec,
    ): OmniResult<ClientSummaryView> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.clients.revoke(principal, spec.targetId, spec.command)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    clients = clients.map {
                        if (it.clientId == r.value.clientId) r.value else it
                    }
                }
                r
            }
        }
    }

    override suspend fun revokeToken(
        principal: PrincipalId,
        spec: RevokeSpec,
    ): OmniResult<DeveloperTokenView> {
        requireLocalUi(principal)
        setLoading(true)
        return when (val r = ports.tokens.revoke(principal, spec.targetId, spec.command)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    tokens = tokens.map {
                        if (it.tokenId == r.value.tokenId) r.value else it
                    }
                    if (pendingReceipt?.tokenId == r.value.tokenId) {
                        pendingReceipt = null
                    }
                }
                r
            }
        }
    }

    override suspend fun negotiateCapabilities(
        principal: PrincipalId,
        modelId: String?,
        requiredCapabilities: Set<String>,
    ): OmniResult<CapabilityNegotiationResult> {
        requireLocalUi(principal)
        if (requiredCapabilities.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "requiredCapabilities must be non-empty"),
            )
        }
        val models = when (val listed = ports.capabilities.listModels(principal)) {
            is OmniResult.Err -> {
                fail(listed.error)
                return listed
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    capabilities = listed.value
                    lastError = null
                }
                listed.value
            }
        }
        val result = ServerStateProjection.negotiate(
            models = models,
            modelId = modelId,
            requiredCapabilities = requiredCapabilities,
        )
        if (!result.usable) {
            // Surface canonical error for unsupported / unknown paths (INV-018).
            val first = result.blocking.first()
            val err = when (first.kind) {
                com.omnillm.features.server.api.CapabilityBlockerKind.UNSUPPORTED ->
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = first.message,
                        details = mapOf("capabilityId" to first.capabilityId),
                    )
                com.omnillm.features.server.api.CapabilityBlockerKind.UNKNOWN,
                com.omnillm.features.server.api.CapabilityBlockerKind.MISSING,
                ->
                    OmniError.CAPABILITY_UNKNOWN(
                        message = first.message,
                        details = mapOf("capabilityId" to first.capabilityId),
                    )
                com.omnillm.features.server.api.CapabilityBlockerKind.TEMPORARILY_UNAVAILABLE ->
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = first.message,
                        details = mapOf(
                            "capabilityId" to first.capabilityId,
                            "state" to first.state.name,
                        ),
                    )
            }
            // Negotiation itself is a query: keep snapshot READY but return Err.
            return OmniResult.err(err)
        }
        return OmniResult.ok(result)
    }

    override suspend fun runSmokeInference(
        principal: PrincipalId,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult> {
        requireLocalUi(principal)
        // Negotiate first — same fail-closed rules as production clients.
        when (
            val neg = negotiateCapabilities(
                principal = principal,
                modelId = null,
                requiredCapabilities = claim.requiredCapabilities,
            )
        ) {
            is OmniResult.Err -> {
                val smoke = SmokeTestResult(
                    step = "capability-negotiation",
                    success = false,
                    requestId = claim.requestId,
                    requestState = null,
                    error = neg.error,
                    completedAtEpochMs = ports.clockMs(),
                )
                synchronized(lock) {
                    lastSmoke = smoke
                    lastRequestId = claim.requestId
                    lastError = neg.error
                }
                return OmniResult.err(neg.error)
            }
            is OmniResult.Ok -> Unit
        }

        setLoading(true)
        return when (val r = ports.inference.submitSmoke(principal, claim)) {
            is OmniResult.Err -> {
                val smoke = SmokeTestResult(
                    step = "submit",
                    success = false,
                    requestId = claim.requestId,
                    requestState = null,
                    error = r.error,
                    completedAtEpochMs = ports.clockMs(),
                )
                synchronized(lock) {
                    loading = false
                    lastSmoke = smoke
                    lastRequestId = claim.requestId
                    lastError = r.error
                }
                OmniResult.err(r.error)
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    lastSmoke = r.value
                    lastRequestId = r.value.requestId
                    lastRequestState = r.value.requestState
                }
                r
            }
        }
    }

    override suspend fun cancelRequest(
        principal: PrincipalId,
        requestId: String,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult> {
        requireLocalUi(principal)
        val id = try {
            RequestId.parse(requestId)
        } catch (e: IllegalArgumentException) {
            val err = OmniError.INVALID_REQUEST(
                message = "requestId must be UUID",
                details = mapOf("requestId" to requestId),
            )
            fail(err)
            return OmniResult.err(err)
        }
        setLoading(true)
        return when (val r = ports.inference.cancel(principal, id, command)) {
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    loading = false
                    lastError = null
                    lastSmoke = r.value
                    lastRequestId = r.value.requestId
                    lastRequestState = r.value.requestState
                }
                r
            }
        }
    }

    override suspend fun queryRequest(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<SmokeTestResult> {
        requireLocalUi(principal)
        val id = try {
            RequestId.parse(requestId)
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "requestId must be UUID",
                    details = mapOf("requestId" to requestId),
                ),
            )
        }
        return when (val r = ports.inference.query(principal, id)) {
            is OmniResult.Err -> r
            is OmniResult.Ok -> {
                synchronized(lock) {
                    lastSmoke = r.value
                    lastRequestId = r.value.requestId
                    lastRequestState = r.value.requestState
                    lastError = r.value.error
                }
                r
            }
        }
    }

    override fun listSdkSamples(): List<SdkSampleRecipe> = SdkSampleCatalog.all()

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun projectLocked(): DeveloperServerSnapshot =
        ServerStateProjection.project(
            loading = loading,
            loopback = loopback,
            clients = clients,
            tokens = tokens,
            pendingReceipt = pendingReceipt,
            capabilities = capabilities,
            metrics = metrics,
            lastSmoke = lastSmoke,
            lastRequestId = lastRequestId,
            lastRequestState = lastRequestState,
            error = lastError,
            nowEpochMs = ports.clockMs(),
            hasRefreshed = hasRefreshed,
        )

    private fun setLoading(value: Boolean) {
        synchronized(lock) {
            loading = value
            if (value) lastError = null
        }
    }

    private fun fail(error: OmniError) {
        synchronized(lock) {
            loading = false
            lastError = error
            hasRefreshed = true
        }
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "FEAT-SERVER UI mutations require LOCAL_UI principal"
        }
    }

    private fun firstErr(vararg results: OmniResult<*>): OmniError? {
        for (r in results) {
            if (r is OmniResult.Err) return r.error
        }
        return null
    }
}
