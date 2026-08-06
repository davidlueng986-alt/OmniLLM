package com.omnillm.features.playground.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.playground.api.StructuredToolsRequestSpec

/**
 * Structured / tool-calling surface for Playground STRUCTURED_TOOLS tab (FEAT-PLAYGROUND + FEAT-TOOLS).
 *
 * Implemented on the control plane by adapting [com.omnillm.features.tools.api.ToolsApi]
 * (schema admission, mode policy, proposal ledger). Feature never executes host tools.
 */
interface PlaygroundStructuredPort {
    suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle>

    suspend fun startToolCalling(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle>
}

/** Default: structured tab fails closed until Tools path is wired. */
object FailClosedPlaygroundStructuredPort : PlaygroundStructuredPort {
    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle> =
        OmniResult.err(
            com.omnillm.core.errors.generated.OmniError.CAPABILITY_UNSUPPORTED(
                message = "structured output path not wired",
                details = mapOf("tab" to "STRUCTURED_TOOLS"),
            ),
        )

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<InferenceHandle> =
        OmniResult.err(
            com.omnillm.core.errors.generated.OmniError.CAPABILITY_UNSUPPORTED(
                message = "tool calling path not wired",
                details = mapOf("tab" to "STRUCTURED_TOOLS"),
            ),
        )
}

