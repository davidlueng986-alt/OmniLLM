package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.StructuredToolsRequestSpec
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.PlaygroundStructuredPort
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.features.tools.domain.StructuredCallerPolicy
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.domain.ToolDefinition
import com.omnillm.features.tools.domain.ToolChoice
import com.omnillm.features.tools.domain.ToolsChatMessage
import com.omnillm.features.tools.domain.ToolsRequestIdentity

/**
 * Adapts [ToolsApi] for Playground STRUCTURED_TOOLS tab (FEAT-PLAYGROUND + FEAT-TOOLS).
 *
 * Schema admission / mode policy / proposal ledger stay on ToolsService.
 * Late-bound [holder] allows Wave-A playground construction before Wave-B tools attach.
 */
class ToolsApiPlaygroundStructuredAdapter(
    private val holder: ToolsApiHolder,
) : PlaygroundStructuredPort {

    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle> {
        val api = holder.toolsApi
            ?: return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "tools path not attached yet",
                    details = mapOf("tab" to "STRUCTURED_TOOLS"),
                ),
            )
        val toolsSpec = StructuredOutputSpec(
            identity = ToolsRequestIdentity(
                requestId = spec.identity.requestId,
                idempotencyKey = spec.identity.idempotencyKey,
                canonicalInputDigest = spec.identity.canonicalInputDigest,
            ),
            modelRevisionId = spec.modelRevisionId,
            schemaName = spec.schemaName,
            schema = spec.schema.ifEmpty {
                mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any?>(),
                )
            },
            callerPolicy = if (spec.allowPostValidate) {
                StructuredCallerPolicy.POST_VALIDATE_ALLOWED
            } else {
                StructuredCallerPolicy.NATIVE_ONLY
            },
            fallbackPolicy = FallbackPolicy.NONE,
            deadlineElapsedRealtimeNanos = spec.deadlineElapsedRealtimeNanos,
        )
        return when (val r = api.startStructured(principal, toolsSpec)) {
            is OmniResult.Err -> OmniResult.err(r.error)
            is OmniResult.Ok -> OmniResult.ok(
                InferenceHandle(
                    requestId = r.value.requestId,
                    operationKind = "STRUCTURED",
                    state = r.value.state,
                    actualModelRevisionId = r.value.actualModelRevisionId,
                    engineBuildId = r.value.engineBuildId,
                    assistantText = r.value.structuredJson,
                    error = r.value.error,
                    degraded = r.value.fallbackApplied,
                    degradedReasons = if (r.value.fallbackApplied) {
                        listOf("fallback_applied")
                    } else {
                        emptyList()
                    },
                ),
            )
        }
    }

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle> {
        val api = holder.toolsApi
            ?: return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "tools path not attached yet",
                    details = mapOf("tab" to "STRUCTURED_TOOLS"),
                ),
            )
        val tools = spec.tools.map {
            ToolDefinition(
                toolId = it.toolId,
                description = it.description,
                parametersSchema = it.parametersSchema,
            )
        }
        val toolsSpec = ToolCallingSpec(
            identity = ToolsRequestIdentity(
                requestId = spec.identity.requestId,
                idempotencyKey = spec.identity.idempotencyKey,
                canonicalInputDigest = spec.identity.canonicalInputDigest,
            ),
            modelRevisionId = spec.modelRevisionId,
            tools = tools,
            toolChoice = ToolChoice.Auto,
            messages = listOf(
                ToolsChatMessage(role = "user", content = "Invoke tools as needed."),
            ),
            callerPolicy = if (spec.allowPostValidate) {
                StructuredCallerPolicy.POST_VALIDATE_ALLOWED
            } else {
                StructuredCallerPolicy.NATIVE_ONLY
            },
            fallbackPolicy = FallbackPolicy.NONE,
            deadlineElapsedRealtimeNanos = spec.deadlineElapsedRealtimeNanos,
        )
        return when (val r = api.startToolCalling(principal, toolsSpec)) {
            is OmniResult.Err -> OmniResult.err(r.error)
            is OmniResult.Ok -> {
                val proposalSummary = r.value.proposals.joinToString(";") {
                    "${it.toolId}:${it.state.name}"
                }
                OmniResult.ok(
                    InferenceHandle(
                        requestId = r.value.requestId,
                        operationKind = "TOOL_CALLING",
                        state = r.value.state,
                        actualModelRevisionId = r.value.actualModelRevisionId,
                        engineBuildId = r.value.engineBuildId,
                        assistantText = r.value.assistantText
                            ?: if (proposalSummary.isNotBlank()) {
                                "proposals=$proposalSummary"
                            } else {
                                null
                            },
                        error = r.value.error,
                    ),
                )
            }
        }
    }
}

/** Process-local late binding for ToolsApi → playground structured adapter. */
class ToolsApiHolder {
    @Volatile
    var toolsApi: ToolsApi? = null
}
