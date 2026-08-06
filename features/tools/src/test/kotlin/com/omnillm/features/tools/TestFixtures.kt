package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.domain.ToolDefinition
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolsRequestIdentity
import com.omnillm.features.tools.domain.ToolsChatMessage
import com.omnillm.features.tools.ports.InMemoryToolProposalLedger
import com.omnillm.features.tools.ports.StaticToolsCapabilityPort
import com.omnillm.features.tools.ports.StructuredInferenceHandle
import com.omnillm.features.tools.ports.ToolCallingHandle
import com.omnillm.features.tools.ports.ToolsFeaturePorts
import com.omnillm.features.tools.ports.ToolsInferencePort
import com.omnillm.features.tools.ports.ToolsQueryHandle
import com.omnillm.features.tools.ports.ToolsScopePort
import com.omnillm.features.tools.usecase.ToolsService
import com.omnillm.interfaces.admin.LocalUiPrincipal

fun digestHex(seed: Char = 'a'): String = seed.toString().repeat(64)

fun identity(
    requestId: String = "11111111-1111-1111-1111-111111111111",
    key: String = "idem-1",
    digest: String = digestHex('b'),
): ToolsRequestIdentity =
    ToolsRequestIdentity(
        requestId = requestId,
        idempotencyKey = key,
        canonicalInputDigest = digest,
    )

fun simpleSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "name" to mapOf("type" to "string"),
        "count" to mapOf("type" to "integer"),
    ),
    "required" to listOf("name"),
)

fun toolDef(
    id: String = "get_weather",
    schema: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "city" to mapOf("type" to "string"),
        ),
    ),
): ToolDefinition =
    ToolDefinition(
        toolId = id,
        description = "demo tool",
        parametersSchema = schema,
        requiredHostPermission = "host.tool.$id",
    )

fun supportedCaps(
    modelId: String,
    mode: StructuredMode = StructuredMode.NATIVE_CONSTRAINED,
    withTools: Boolean = true,
): StaticToolsCapabilityPort {
    val p = StaticToolsCapabilityPort()
    p.set(modelId, CapabilityId.TEXT_GENERATION, CapabilityState.SUPPORTED)
    p.set(modelId, CapabilityId.STRUCTURED_OUTPUT, CapabilityState.SUPPORTED)
    p.set(modelId, CapabilityId.REQUEST_LIFECYCLE, CapabilityState.SUPPORTED)
    p.set(modelId, CapabilityId.STREAMING, CapabilityState.SUPPORTED)
    p.set(modelId, CapabilityId.CANCELLATION, CapabilityState.SUPPORTED)
    p.set(modelId, CapabilityId.DEADLINE, CapabilityState.SUPPORTED)
    if (withTools) {
        p.set(modelId, CapabilityId.TOOL_CALLING, CapabilityState.SUPPORTED)
    }
    p.setMode(modelId, mode)
    return p
}

class RecordingInferencePort(
    var structuredMode: StructuredMode = StructuredMode.NATIVE_CONSTRAINED,
    var actualRevisionOverride: String? = null,
    var proposals: List<ToolProposal> = emptyList(),
    var structuredStartCount: Int = 0,
    var toolStartCount: Int = 0,
) : ToolsInferencePort {
    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
        maxRepairAttempts: Int,
    ): OmniResult<StructuredInferenceHandle> {
        structuredStartCount++
        return OmniResult.ok(
            StructuredInferenceHandle(
                requestId = spec.identity.requestId,
                state = "SUCCEEDED",
                actualMode = actualMode,
                validationStatus = "OK",
                actualModelRevisionId = actualRevisionOverride ?: spec.modelRevisionId,
                engineBuildId = "engine-test",
                attempt = 1,
                structuredJson = """{"name":"x","count":1}""",
                isTerminal = true,
            ),
        )
    }

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
    ): OmniResult<ToolCallingHandle> {
        toolStartCount++
        val props = if (proposals.isNotEmpty()) {
            proposals
        } else {
            val def = spec.tools.first()
            listOf(
                ToolProposal(
                    proposalId = "prop-1",
                    requestId = spec.identity.requestId,
                    toolId = def.toolId,
                    schemaDigest = def.schemaDigest,
                    argumentsJson = """{"city":"Taipei"}""",
                    attempt = 1,
                    createdAtEpochMs = 1_000L,
                ),
            )
        }
        return OmniResult.ok(
            ToolCallingHandle(
                requestId = spec.identity.requestId,
                state = "SUCCEEDED",
                actualMode = actualMode,
                actualModelRevisionId = actualRevisionOverride ?: spec.modelRevisionId,
                engineBuildId = "engine-test",
                proposals = props,
                isTerminal = true,
            ),
        )
    }

    override suspend fun cancel(principal: PrincipalId, requestId: String): OmniResult<Unit> =
        OmniResult.ok(Unit)

    override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<ToolsQueryHandle> =
        OmniResult.ok(
            ToolsQueryHandle(
                requestId = requestId,
                state = "SUCCEEDED",
                isTerminal = true,
            ),
        )
}

/** Scope port with configurable allow set (LAN fail-closed tests). */
class ConfigurableScopePort(
    private val allowed: MutableSet<AccessScope> = AccessScope.entries.toMutableSet(),
    private val maySubmit: Boolean = true,
    private val acceptedPrincipal: String? = LocalUiPrincipal.ID.value,
) : ToolsScopePort {
    fun denyAll() {
        allowed.clear()
    }

    fun allowOnly(vararg scopes: AccessScope) {
        allowed.clear()
        allowed.addAll(scopes)
    }

    override fun allows(principal: PrincipalId, scope: AccessScope): Boolean {
        if (acceptedPrincipal != null && principal.value != acceptedPrincipal) return false
        return scope in allowed
    }

    override fun maySubmitToolResult(principal: PrincipalId, requestId: String): Boolean =
        maySubmit && allows(principal, AccessScope.inference_create)
}

fun buildService(
    modelId: String = "c".repeat(64),
    mode: StructuredMode = StructuredMode.NATIVE_CONSTRAINED,
    inference: RecordingInferencePort = RecordingInferencePort(structuredMode = mode),
    scopes: ToolsScopePort = ConfigurableScopePort(),
    caps: StaticToolsCapabilityPort = supportedCaps(modelId, mode),
): Triple<ToolsService, RecordingInferencePort, StaticToolsCapabilityPort> {
    val ledger = InMemoryToolProposalLedger()
    val service = ToolsService(
        ports = ToolsFeaturePorts(
            inference = inference,
            capabilities = caps,
            scopes = scopes,
            ledger = ledger,
        ),
        clockMs = { 2_000L },
    )
    return Triple(service, inference, caps)
}

fun structuredSpec(
    modelId: String,
    policy: com.omnillm.features.tools.domain.StructuredCallerPolicy =
        com.omnillm.features.tools.domain.StructuredCallerPolicy.NATIVE_ONLY,
    schema: Map<String, Any?> = simpleSchema(),
): StructuredOutputSpec =
    StructuredOutputSpec(
        identity = identity(),
        modelRevisionId = modelId,
        schemaName = "demo",
        schema = schema,
        callerPolicy = policy,
    )

fun toolSpec(
    modelId: String,
    tools: List<ToolDefinition> = listOf(toolDef()),
    allowlist: Set<String>? = null,
): ToolCallingSpec =
    ToolCallingSpec(
        identity = identity(requestId = "22222222-2222-2222-2222-222222222222"),
        modelRevisionId = modelId,
        tools = tools,
        messages = listOf(ToolsChatMessage(role = "user", content = "weather?")),
        toolAllowlist = allowlist,
        callerPolicy = com.omnillm.features.tools.domain.StructuredCallerPolicy.POST_VALIDATE_ALLOWED,
    )
