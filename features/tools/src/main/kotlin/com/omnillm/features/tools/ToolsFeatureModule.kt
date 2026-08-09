package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.ports.ledger.ToolProposalLedgerPorts
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.features.tools.ports.DurableToolProposalLedger
import com.omnillm.features.tools.ports.InMemoryToolProposalLedger
import com.omnillm.features.tools.ports.StaticToolsCapabilityPort
import com.omnillm.features.tools.ports.ToolsCapabilityPort
import com.omnillm.features.tools.ports.ToolsFeaturePorts
import com.omnillm.features.tools.ports.ToolsInferencePort
import com.omnillm.features.tools.ports.ToolsScopePort
import com.omnillm.features.tools.ports.LocalAdminToolsScopePort
import com.omnillm.features.tools.ports.ToolProposalLedgerPort
import com.omnillm.features.tools.usecase.ToolsService

/**
 * Feature pack `:features:tools` (FEAT-TOOLS — Structured Output & Tool Calling).
 *
 * Composes:
 * - STRUCTURED_OUTPUT / TOOL_CALLING / TEXT_GENERATION
 * - REQUEST_LIFECYCLE / STREAMING / CANCELLATION / DEADLINE
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * OmniLLM never executes host tools — only records [com.omnillm.features.tools.domain.ToolProposal]
 * and accepts host-submitted [com.omnillm.features.tools.domain.ToolResult] (FEAT-TOOLS §3).
 *
 * UI process talks only through this API / runtime binder — never opens domain
 * DB or loads native engines (INV-001). Client generates requestId /
 * idempotencyKey before inference (ADR-004/005).
 */
object ToolsFeatureModule {
    const val MODULE_PATH: String = ":features:tools"
    const val FEATURE_ID: String = "FEAT-TOOLS"

    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.STRUCTURED_OUTPUT,
        CapabilityId.TOOL_CALLING,
        CapabilityId.TEXT_GENERATION,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.STREAMING,
        CapabilityId.CANCELLATION,
        CapabilityId.DEADLINE,
    )

    /**
     * Wire control-plane ports. Call only from runtime host or test harness —
     * never construct inference engines inside the UI process.
     *
     * Prefer [createDurableApi] in production so proposals/results survive
     * process death (ADR-010). Default [InMemoryToolProposalLedger] is test-only.
     */
    fun createApi(
        inference: ToolsInferencePort,
        capabilities: ToolsCapabilityPort = StaticToolsCapabilityPort(),
        scopes: ToolsScopePort = LocalAdminToolsScopePort,
        ledger: ToolProposalLedgerPort = InMemoryToolProposalLedger(),
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): ToolsApi =
        ToolsService(
            ports = ToolsFeaturePorts(
                inference = inference,
                capabilities = capabilities,
                scopes = scopes,
                ledger = ledger,
            ),
            clockMs = clockMs,
        )

    /**
     * Production API bound to SQLite tool proposal ledger (control-plane sole writer).
     */
    fun createDurableApi(
        inference: ToolsInferencePort,
        ledger: ToolProposalLedgerPorts,
        capabilities: ToolsCapabilityPort = StaticToolsCapabilityPort(),
        scopes: ToolsScopePort = LocalAdminToolsScopePort,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): ToolsApi =
        createApi(
            inference = inference,
            capabilities = capabilities,
            scopes = scopes,
            ledger = DurableToolProposalLedger(ledger),
            clockMs = clockMs,
        )

    fun createApi(ports: ToolsFeaturePorts, clockMs: () -> Long = { System.currentTimeMillis() }): ToolsApi =
        ToolsService(ports = ports, clockMs = clockMs)
}
