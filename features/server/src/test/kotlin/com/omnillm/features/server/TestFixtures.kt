package com.omnillm.features.server

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.server.api.CapabilityCellView
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.domain.ScopeCatalog
import com.omnillm.features.server.ports.CapabilityQueryPort
import com.omnillm.features.server.ports.ClientAdminPort
import com.omnillm.features.server.ports.LoopbackServerPort
import com.omnillm.features.server.ports.ServerInferencePort
import com.omnillm.features.server.ports.ServerMetricsPort
import com.omnillm.features.server.ports.ServerRuntimePorts
import com.omnillm.features.server.ports.TokenAdminPort
import com.omnillm.features.server.usecase.DeveloperServerService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun principal(): PrincipalId = LocalUiPrincipal.ID

fun cmd(suffix: String = "1"): ServerCommandIdentity =
    ServerCommandIdentity(
        commandId = uuid("c$suffix"),
        idempotencyKey = "idem-$suffix",
    )

fun uuid(seed: String = "a"): String {
    // Deterministic-looking UUID from seed chars (valid format).
    val h = seed.hashCode().toUInt().toString(16).padStart(8, '0')
    return "$h-aaaa-bbbb-cccc-${h.padStart(12, '0').take(12)}"
}

fun digest(c: Char = 'a'): String = c.toString().repeat(64)

fun claim(
    requestId: String = uuid("req1"),
    capabilities: Set<String> = setOf("TEXT_GENERATION"),
    op: String = "CHAT",
): InferenceClaimSpec =
    InferenceClaimSpec(
        requestId = requestId,
        idempotencyKey = "smoke-$requestId",
        operationKind = op,
        canonicalRequestDigestHex = digest('b'),
        requiredCapabilities = capabilities,
        modelRevisionIdHex = digest('c'),
        deadlineMonotonic = 1_000_000L,
    )

fun ports(
    loopback: FakeLoopback = FakeLoopback(),
    tokens: FakeTokens = FakeTokens(),
    clients: FakeClients = FakeClients(),
    capabilities: FakeCapabilities = FakeCapabilities(),
    inference: FakeInference = FakeInference(),
    metrics: FakeMetrics = FakeMetrics(),
    clock: () -> Long = { 1_700_000_000_000L },
): ServerRuntimePorts =
    ServerRuntimePorts(
        loopback = loopback,
        tokens = tokens,
        clients = clients,
        capabilities = capabilities,
        inference = inference,
        metrics = metrics,
        clockMs = clock,
    )

fun service(p: ServerRuntimePorts = ports()): DeveloperServerService =
    DeveloperServerService(p)

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

class FakeLoopback(
    var statusValue: LoopbackServerStatus = LoopbackServerStatus(
        enabled = false,
        running = false,
        host = "127.0.0.1",
        port = 11434,
        runtimeState = "STOPPED",
        resourceVersion = 0L,
    ),
) : LoopbackServerPort {
    var ensureStartedCalls: Int = 0

    override suspend fun status(): OmniResult<LoopbackServerStatus> =
        OmniResult.ok(statusValue)

    override suspend fun ensureStarted(principal: PrincipalId): OmniResult<LoopbackServerStatus> {
        ensureStartedCalls++
        statusValue = statusValue.copy(
            enabled = true,
            running = true,
            runtimeState = "READY",
            resourceVersion = statusValue.resourceVersion + 1,
            degradedReasons = emptyList(),
        )
        return OmniResult.ok(statusValue)
    }

    fun markDegraded(reason: String = "ENGINE") {
        statusValue = statusValue.copy(
            enabled = true,
            running = true,
            runtimeState = "DEGRADED",
            degradedReasons = listOf(reason),
        )
    }

    fun markReady() {
        statusValue = statusValue.copy(
            enabled = true,
            running = true,
            runtimeState = "READY",
            degradedReasons = emptyList(),
        )
    }
}

class FakeTokens : TokenAdminPort {
    private val byId = ConcurrentHashMap<String, DeveloperTokenView>()
    private val plain = ConcurrentHashMap<String, String>()
    private val seq = AtomicLong(0)

    override suspend fun listTokens(principal: PrincipalId): OmniResult<List<DeveloperTokenView>> =
        OmniResult.ok(byId.values.sortedBy { it.tokenId })

    override suspend fun issue(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        expiresInSeconds: Long,
        label: String?,
        command: ServerCommandIdentity,
    ): OmniResult<TokenIssuanceReceipt> {
        val tokenId = UUID.randomUUID().toString()
        val now = 1_700_000_000_000L
        val plaintext = "tok-${seq.incrementAndGet()}-$tokenId"
        val view = DeveloperTokenView(
            tokenId = tokenId,
            clientId = clientId,
            state = "ACTIVE",
            scopes = scopes,
            expiresAtEpochMs = now + expiresInSeconds * 1000L,
            revocationEpoch = 0L,
            loopbackOnly = true,
            label = label,
        )
        byId[tokenId] = view
        plain[tokenId] = plaintext
        return OmniResult.ok(
            TokenIssuanceReceipt(
                tokenId = tokenId,
                clientId = clientId,
                tokenPlaintext = plaintext,
                scopes = scopes,
                expiresAtEpochMs = view.expiresAtEpochMs,
                receiptExpiresAtEpochMs = now + 60_000L,
                revocationEpoch = 0L,
                loopbackOnly = true,
            ),
        )
    }

    override suspend fun revoke(
        principal: PrincipalId,
        tokenId: String,
        command: ServerCommandIdentity,
    ): OmniResult<DeveloperTokenView> {
        val existing = byId[tokenId]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "token not found"))
        val revoked = existing.copy(state = "REVOKED", revocationEpoch = existing.revocationEpoch + 1)
        byId[tokenId] = revoked
        plain.remove(tokenId)
        return OmniResult.ok(revoked)
    }
}

class FakeClients : ClientAdminPort {
    private val byId = ConcurrentHashMap<String, ClientSummaryView>()

    override suspend fun listClients(principal: PrincipalId): OmniResult<List<ClientSummaryView>> =
        OmniResult.ok(byId.values.sortedBy { it.clientId })

    override suspend fun createClient(
        principal: PrincipalId,
        clientId: String,
        displayName: String,
        scopes: Set<String>,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView> {
        val view = ClientSummaryView(
            clientId = clientId,
            displayName = displayName,
            state = "ACTIVE",
            scopes = scopes,
            revocationEpoch = 0L,
            lastSeenAtEpochMs = 1_700_000_000_000L,
        )
        byId[clientId] = view
        return OmniResult.ok(view)
    }

    override suspend fun revoke(
        principal: PrincipalId,
        clientId: String,
        command: ServerCommandIdentity,
    ): OmniResult<ClientSummaryView> {
        val existing = byId[clientId]
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "client not found"))
        val revoked = existing.copy(state = "REVOKED", revocationEpoch = existing.revocationEpoch + 1)
        byId[clientId] = revoked
        return OmniResult.ok(revoked)
    }
}

class FakeCapabilities(
    var models: List<ModelCapabilityView> = listOf(defaultModel()),
) : CapabilityQueryPort {
    override suspend fun listModels(principal: PrincipalId): OmniResult<List<ModelCapabilityView>> =
        OmniResult.ok(models)

    companion object {
        fun defaultModel(
            textGen: CapabilityState = CapabilityState.SUPPORTED,
            embedding: CapabilityState = CapabilityState.SUPPORTED,
            vision: CapabilityState = CapabilityState.UNSUPPORTED,
        ): ModelCapabilityView =
            ModelCapabilityView(
                modelId = "local-demo",
                modelRevisionId = digest('c'),
                displayName = "Demo",
                engineBuildId = "engine-1",
                backend = "cpu",
                trustClass = "TRUSTED",
                capabilities = listOf(
                    CapabilityCellView(
                        capabilityId = "TEXT_GENERATION",
                        state = textGen,
                        evidenceLabel = EvidenceLabel.REPORTED,
                        sampledAtEpochMs = 1L,
                        source = "catalog",
                    ),
                    CapabilityCellView(
                        capabilityId = "EMBEDDING",
                        state = embedding,
                        evidenceLabel = EvidenceLabel.REPORTED,
                        sampledAtEpochMs = 1L,
                        source = "catalog",
                    ),
                    CapabilityCellView(
                        capabilityId = "VISION_INPUT",
                        state = vision,
                        evidenceLabel = EvidenceLabel.REPORTED,
                        sampledAtEpochMs = 1L,
                        source = "catalog",
                    ),
                    CapabilityCellView(
                        capabilityId = "STREAMING",
                        state = CapabilityState.UNKNOWN,
                        evidenceLabel = EvidenceLabel.UNKNOWN,
                        sampledAtEpochMs = 0L,
                    ),
                ),
            )
    }
}

class FakeInference : ServerInferencePort {
    private val states = ConcurrentHashMap<String, String>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    var lastClaim: InferenceClaimSpec? = null
    var failSubmitWith: OmniError? = null

    override suspend fun submitSmoke(
        principal: PrincipalId,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult> {
        lastClaim = claim
        failSubmitWith?.let {
            return OmniResult.err(it)
        }
        states[claim.requestId] = "QUEUED"
        return OmniResult.ok(
            SmokeTestResult(
                step = "submit",
                success = true,
                requestId = claim.requestId,
                requestState = "QUEUED",
                error = null,
                completedAtEpochMs = 1_700_000_000_000L,
                actualModelRevisionId = claim.modelRevisionIdHex,
                actualEngineBuildId = "engine-1",
                actualBackend = "cpu",
            ),
        )
    }

    override suspend fun cancel(
        principal: PrincipalId,
        requestId: RequestId,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult> {
        val id = requestId.value
        if (!states.containsKey(id) && !cancelRequested.contains(id)) {
            return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to id),
                ),
            )
        }
        cancelRequested += id
        states[id] = "CANCELLED"
        return OmniResult.ok(
            SmokeTestResult(
                step = "cancel",
                success = true,
                requestId = id,
                requestState = "CANCELLED",
                error = null,
                completedAtEpochMs = 1_700_000_000_100L,
            ),
        )
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<SmokeTestResult> {
        val id = requestId.value
        val state = states[id]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to id),
                ),
            )
        return OmniResult.ok(
            SmokeTestResult(
                step = "query",
                success = state == "CANCELLED" || state == "COMPLETED",
                requestId = id,
                requestState = state,
                error = null,
                completedAtEpochMs = 1_700_000_000_200L,
            ),
        )
    }
}

class FakeMetrics(
    var samples: List<EvidencedMetricView> = listOf(
        EvidencedMetricView(
            name = "request.ttft_ms",
            value = 42.0,
            unit = "ms",
            evidenceLabel = EvidenceLabel.MEASURED,
            sampledAtEpochMs = 1_700_000_000_000L,
            dimensions = mapOf("backend" to "cpu"),
        ),
        EvidencedMetricView(
            name = "engine.health",
            value = null,
            unit = "enum",
            evidenceLabel = EvidenceLabel.UNKNOWN,
            sampledAtEpochMs = 0L,
        ),
        EvidencedMetricView(
            name = "resource.reserved_bytes",
            value = 1_024.0,
            unit = "bytes",
            evidenceLabel = EvidenceLabel.REPORTED,
            sampledAtEpochMs = 1_700_000_000_000L,
            source = "governor",
        ),
    ),
) : ServerMetricsPort {
    override suspend fun summary(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> =
        OmniResult.ok(samples)
}

fun defaultScopes(): Set<String> = ScopeCatalog.DEVELOPER_CLIENT_DEFAULT
