package com.omnillm.features.tools.usecase

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.tools.api.StructuredResultView
import com.omnillm.features.tools.api.ToolCallingResultView
import com.omnillm.features.tools.api.ToolProposalView
import com.omnillm.features.tools.api.ToolResultSubmitView
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.features.tools.api.ToolsNegotiationView
import com.omnillm.features.tools.api.ToolsSnapshot
import com.omnillm.features.tools.domain.SchemaAdmission
import com.omnillm.features.tools.domain.SchemaLimits
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.StructuredOutputValidator
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.domain.ToolDefinition
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ToolResult
import com.omnillm.features.tools.domain.ValidationStatusCode
import com.omnillm.features.tools.ports.ToolsFeaturePorts
import com.omnillm.features.tools.policy.RoutingRevisionPolicy
import com.omnillm.features.tools.policy.StructuredModePolicy
import com.omnillm.features.tools.policy.ToolExecutionPolicy
import com.omnillm.features.tools.policy.ToolResultClaimOutcome
import com.omnillm.features.tools.projection.ToolsProjections
import java.util.concurrent.atomic.AtomicReference

/**
 * FEAT-TOOLS control-plane use-case facade.
 *
 * - Schema bomb admission before engine compile
 * - Explicit structured mode (no silent free-text demotion)
 * - Tool proposals only; host-submitted results with claim-or-return
 * - Auth/scope fail closed (LAN without inference.create denied)
 * - Never silent cross-revision fallback
 * - Privacy-safe traces; report ≠ telemetry
 */
class ToolsService(
    private val ports: ToolsFeaturePorts,
    private val limits: SchemaLimits = SchemaLimits.DEFAULT,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ToolsApi {

    private val lastStructured = AtomicReference<StructuredResultView?>(null)
    private val lastToolCalling = AtomicReference<ToolCallingResultView?>(null)
    private val lastError = AtomicReference<OmniError?>(null)

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<ToolsSnapshot> {
        if (!requireAuth(principal, AccessScope.inference_read_own)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_read_own)
        }
        // Open proposals for the most recent tool-calling request (feature snapshot).
        val openViews = lastToolCalling.get()?.requestId
            ?.let { ports.ledger.listProposals(it) }
            .orEmpty()
            .filter {
                it.state == ToolProposalState.PROPOSED ||
                    it.state == ToolProposalState.HOST_CLAIMED ||
                    it.state == ToolProposalState.UNCERTAIN
            }
            .map { ToolsProjections.projectProposal(it) }

        return OmniResult.ok(
            ToolsSnapshot(
                lastStructured = lastStructured.get(),
                lastToolCalling = lastToolCalling.get(),
                openProposals = openViews,
                lastError = lastError.get(),
            ),
        )
    }

    override suspend fun negotiate(
        principal: PrincipalId,
        modelRevisionId: String,
        requireToolCalling: Boolean,
    ): OmniResult<ToolsNegotiationView> {
        if (!requireAuth(principal, AccessScope.inference_read_own) &&
            !requireAuth(principal, AccessScope.models_read)
        ) {
            // models.read also sufficient for catalog-style negotiation.
            return unauthorizedOrForbidden(principal, AccessScope.inference_read_own)
        }
        if (modelRevisionId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "modelRevisionId required"))
        }

        val required = buildList {
            add(CapabilityId.TEXT_GENERATION)
            add(CapabilityId.STRUCTURED_OUTPUT)
            if (requireToolCalling) add(CapabilityId.TOOL_CALLING)
            add(CapabilityId.REQUEST_LIFECYCLE)
        }
        val cell = ports.capabilities.offeredStructuredMode(modelRevisionId)
        val cells = required.map { cap ->
            val st = ports.capabilities.state(cap, modelRevisionId)
            ToolsProjections.projectCell(
                capability = cap,
                state = st,
                offeredMode = if (cap == CapabilityId.STRUCTURED_OUTPUT) cell.offeredMode else null,
            )
        }
        return OmniResult.ok(
            ToolsProjections.projectNegotiation(
                modelRevisionId = modelRevisionId,
                cells = cells,
                offeredMode = cell.offeredMode,
            ),
        )
    }

    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
    ): OmniResult<StructuredResultView> {
        if (!requireAuth(principal, AccessScope.inference_create)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_create)
        }

        // Capability gate (fail closed).
        val gate = gateStructuredCapabilities(spec.modelRevisionId)
        if (gate != null) {
            lastError.set(gate)
            return OmniResult.err(gate)
        }

        // Schema bomb admission — never reach engine compile on reject.
        when (val admission = SchemaAdmission.admit(spec.schema, limits, spec.schemaName)) {
            is SchemaAdmission.Outcome.Rejected -> {
                val err = OmniError.INVALID_REQUEST(
                    message = admission.reason,
                    details = admission.details + mapOf(
                        "validationCode" to admission.code.name,
                        "schemaName" to spec.schemaName,
                    ),
                )
                lastError.set(err)
                return OmniResult.err(err)
            }
            is SchemaAdmission.Outcome.Accepted -> Unit
        }

        val cell = ports.capabilities.offeredStructuredMode(spec.modelRevisionId)
        when (val mode = StructuredModePolicy.resolve(cell, spec.callerPolicy)) {
            is StructuredModePolicy.ResolveResult.Deny -> {
                lastError.set(mode.denial.error)
                return OmniResult.err(mode.denial.error)
            }
            is StructuredModePolicy.ResolveResult.Allow -> {
                // Routing: never silent cross-revision (selected == requested for feature path).
                when (
                    val route = RoutingRevisionPolicy.resolveActualRevision(
                        requestedRevisionId = spec.modelRevisionId,
                        selectedRevisionId = spec.modelRevisionId,
                        fallbackPolicy = spec.fallbackPolicy,
                        allowedRevisionIds = spec.allowedRevisionIds,
                    )
                ) {
                    is RoutingRevisionPolicy.Result.Denied -> {
                        lastError.set(route.error)
                        return OmniResult.err(route.error)
                    }
                    is RoutingRevisionPolicy.Result.Ok -> {
                        return when (
                            val started = ports.inference.startStructured(
                                principal = principal,
                                spec = spec,
                                actualMode = mode.decision.actualMode,
                                maxAttempts = mode.decision.maxAttempts,
                                maxRepairAttempts = mode.decision.maxRepairAttempts,
                            )
                        ) {
                            is OmniResult.Err -> {
                                lastError.set(started.error)
                                OmniResult.err(started.error)
                            }
                            is OmniResult.Ok -> {
                                val silent = StructuredModePolicy.assertDisclosedMode(
                                    plannedMode = mode.decision.actualMode,
                                    actualMode = started.value.actualMode,
                                    freeTextWithoutSchema = started.value.structuredJson == null &&
                                        started.value.isTerminal &&
                                        started.value.error == null &&
                                        mode.decision.actualMode != StructuredMode.UNSUPPORTED,
                                )
                                if (silent != null) {
                                    lastError.set(silent)
                                    return OmniResult.err(silent)
                                }
                                // Cross-revision check on actual returned revision.
                                when (
                                    val actualRoute = RoutingRevisionPolicy.resolveActualRevision(
                                        requestedRevisionId = spec.modelRevisionId,
                                        selectedRevisionId = started.value.actualModelRevisionId,
                                        fallbackPolicy = spec.fallbackPolicy,
                                        allowedRevisionIds = spec.allowedRevisionIds,
                                    )
                                ) {
                                    is RoutingRevisionPolicy.Result.Denied -> {
                                        lastError.set(actualRoute.error)
                                        return OmniResult.err(actualRoute.error)
                                    }
                                    is RoutingRevisionPolicy.Result.Ok -> {
                                        val validation = validateStructuredOutput(
                                            mode = mode.decision.actualMode,
                                            schema = spec.schema,
                                            structuredJson = started.value.structuredJson,
                                            terminal = started.value.isTerminal,
                                            priorError = started.value.error,
                                        )
                                        val view = ToolsProjections.projectStructured(
                                            handle = started.value,
                                            schemaDigest = spec.schemaDigest,
                                            schemaName = spec.schemaName,
                                            modeDecision = mode.decision,
                                            fallbackPolicy = actualRoute.outcome.fallbackPolicy,
                                            fallbackApplied = actualRoute.outcome.fallbackApplied,
                                            validationOverride = validation.status,
                                        ).let { projected ->
                                            if (validation.error != null && projected.error == null) {
                                                projected.copy(error = validation.error)
                                            } else {
                                                projected
                                            }
                                        }
                                        lastStructured.set(view)
                                        if (validation.error != null) {
                                            lastError.set(validation.error)
                                            OmniResult.err(validation.error)
                                        } else {
                                            lastError.set(null)
                                            OmniResult.ok(view)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
    ): OmniResult<ToolCallingResultView> {
        if (!requireAuth(principal, AccessScope.inference_create)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_create)
        }

        val gate = gateToolCapabilities(spec.modelRevisionId)
        if (gate != null) {
            lastError.set(gate)
            return OmniResult.err(gate)
        }

        when (val admission = SchemaAdmission.admitTools(spec.tools, limits)) {
            is SchemaAdmission.Outcome.Rejected -> {
                val err = OmniError.INVALID_REQUEST(
                    message = admission.reason,
                    details = admission.details + mapOf("validationCode" to admission.code.name),
                )
                lastError.set(err)
                return OmniResult.err(err)
            }
            is SchemaAdmission.Outcome.Accepted -> Unit
        }

        // Tool allowlist from request (LAN/AIDL may inject).
        if (spec.toolAllowlist != null) {
            val unknown = spec.tools.map { it.toolId }.filter { it !in spec.toolAllowlist }
            if (unknown.isNotEmpty()) {
                val err = OmniError.FORBIDDEN(
                    message = "tools not on allowlist",
                    details = mapOf("toolIds" to unknown.joinToString(",")),
                )
                lastError.set(err)
                return OmniResult.err(err)
            }
        }

        val cell = ports.capabilities.offeredStructuredMode(spec.modelRevisionId)
        when (val mode = StructuredModePolicy.resolve(cell, spec.callerPolicy)) {
            is StructuredModePolicy.ResolveResult.Deny -> {
                lastError.set(mode.denial.error)
                return OmniResult.err(mode.denial.error)
            }
            is StructuredModePolicy.ResolveResult.Allow -> {
                return when (
                    val started = ports.inference.startToolCalling(
                        principal = principal,
                        spec = spec,
                        actualMode = mode.decision.actualMode,
                        maxAttempts = mode.decision.maxAttempts,
                    )
                ) {
                    is OmniResult.Err -> {
                        lastError.set(started.error)
                        OmniResult.err(started.error)
                    }
                    is OmniResult.Ok -> {
                        when (
                            val actualRoute = RoutingRevisionPolicy.resolveActualRevision(
                                requestedRevisionId = spec.modelRevisionId,
                                selectedRevisionId = started.value.actualModelRevisionId,
                                fallbackPolicy = spec.fallbackPolicy,
                                allowedRevisionIds = spec.allowedRevisionIds,
                            )
                        ) {
                            is RoutingRevisionPolicy.Result.Denied -> {
                                lastError.set(actualRoute.error)
                                return OmniResult.err(actualRoute.error)
                            }
                            is RoutingRevisionPolicy.Result.Ok -> {
                                val defs = spec.tools.associateBy { it.toolId }
                                // Record proposals without executing (FEAT-TOOLS §3 / §7.3).
                                for (p in started.value.proposals) {
                                    when (
                                        val admitted = ToolExecutionPolicy.admitProposal(
                                            proposal = p,
                                            definitions = defs,
                                            allowlist = spec.toolAllowlist,
                                            limits = limits,
                                        )
                                    ) {
                                        is OmniResult.Ok -> ports.ledger.putProposal(admitted.value)
                                        is OmniResult.Err -> {
                                            lastError.set(admitted.error)
                                            return OmniResult.err(admitted.error)
                                        }
                                    }
                                }
                                val view = ToolsProjections.projectToolCalling(
                                    handle = started.value,
                                    definitions = defs,
                                    fallbackPolicy = actualRoute.outcome.fallbackPolicy,
                                    fallbackApplied = actualRoute.outcome.fallbackApplied,
                                )
                                lastToolCalling.set(view)
                                lastError.set(null)
                                OmniResult.ok(view)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun recordProposals(
        principal: PrincipalId,
        requestId: String,
        proposals: List<ToolProposal>,
        toolAllowlist: Set<String>?,
        definitions: Map<String, ToolDefinition>,
    ): OmniResult<List<ToolProposalView>> {
        if (!requireAuth(principal, AccessScope.inference_create)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_create)
        }
        if (requestId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "requestId required"))
        }
        val out = mutableListOf<ToolProposalView>()
        for (p in proposals) {
            if (p.requestId != requestId) {
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "proposal requestId mismatch",
                        details = mapOf(
                            "expected" to requestId,
                            "actual" to p.requestId,
                        ),
                    ),
                )
            }
            when (
                val admitted = ToolExecutionPolicy.admitProposal(
                    proposal = p,
                    definitions = definitions,
                    allowlist = toolAllowlist,
                    limits = limits,
                )
            ) {
                is OmniResult.Err -> return OmniResult.err(admitted.error)
                is OmniResult.Ok -> {
                    ports.ledger.putProposal(admitted.value)
                    out += ToolsProjections.projectProposal(
                        admitted.value,
                        definitions[admitted.value.toolId],
                    )
                }
            }
        }
        return OmniResult.ok(out)
    }

    override suspend fun submitToolResult(
        principal: PrincipalId,
        result: ToolResult,
    ): OmniResult<ToolResultSubmitView> {
        // Host path: must hold inference.create (or host-specific grant).
        if (!requireAuth(principal, AccessScope.inference_create)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_create)
        }
        // Platform never executes — only host claim.
        if (ToolExecutionPolicy.platformExecutionOwner() !=
            com.omnillm.features.tools.domain.ToolExecutionOwner.PLATFORM_FORBIDDEN
        ) {
            return OmniResult.err(ToolExecutionPolicy.refusePlatformExecution("?"))
        }

        val hostOk = ports.scopes.maySubmitToolResult(principal, result.requestId)
        val proposal = ports.ledger.getProposal(result.proposalId)
        val existing = ports.ledger.getResultClaim(result.proposalId, result.idempotencyKey)

        when (
            val claim = ToolExecutionPolicy.claimToolResult(
                existing = existing,
                incoming = result,
                proposal = proposal,
                hostAuthorized = hostOk,
            )
        ) {
            is ToolResultClaimOutcome.Denied -> {
                lastError.set(claim.error)
                return OmniResult.err(claim.error)
            }
            is ToolResultClaimOutcome.Conflict -> {
                ports.ledger.updateProposalState(result.proposalId, ToolProposalState.CONFLICT)
                lastError.set(claim.error)
                return OmniResult.err(claim.error)
            }
            is ToolResultClaimOutcome.Existing -> {
                return OmniResult.ok(
                    ToolResultSubmitView(
                        requestId = claim.claim.requestId,
                        proposalId = claim.claim.proposalId,
                        state = ToolProposalState.RESULT_COMMITTED,
                        claimStatus = "EXISTING",
                        resultPayloadDigest = claim.claim.resultPayloadDigest,
                        isError = claim.claim.isError,
                    ),
                )
            }
            is ToolResultClaimOutcome.Accepted -> {
                ports.ledger.putResultClaim(claim.claim)
                ports.ledger.updateProposalState(
                    result.proposalId,
                    ToolProposalState.RESULT_COMMITTED,
                )
                lastError.set(null)
                return OmniResult.ok(
                    ToolResultSubmitView(
                        requestId = claim.claim.requestId,
                        proposalId = claim.claim.proposalId,
                        state = ToolProposalState.RESULT_COMMITTED,
                        claimStatus = "ACCEPTED",
                        resultPayloadDigest = claim.claim.resultPayloadDigest,
                        isError = claim.claim.isError,
                    ),
                )
            }
        }
    }

    override suspend fun queryProposal(
        principal: PrincipalId,
        proposalId: String,
    ): OmniResult<ToolProposalView> {
        if (!requireAuth(principal, AccessScope.inference_read_own)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_read_own)
        }
        val p = ports.ledger.getProposal(proposalId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "proposal not found",
                    details = mapOf("proposalId" to proposalId),
                ),
            )
        return OmniResult.ok(ToolsProjections.projectProposal(p))
    }

    override suspend fun markProposalUncertain(
        principal: PrincipalId,
        proposalId: String,
    ): OmniResult<ToolProposalView> {
        if (!requireAuth(principal, AccessScope.inference_create)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_create)
        }
        val p = ports.ledger.getProposal(proposalId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "proposal not found",
                    details = mapOf("proposalId" to proposalId),
                ),
            )
        val next = ToolExecutionPolicy.markUncertain(p)
        ports.ledger.putProposal(next)
        return OmniResult.ok(ToolsProjections.projectProposal(next))
    }

    override suspend fun cancel(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<Unit> {
        if (!requireAuth(principal, AccessScope.inference_cancel)) {
            return unauthorizedOrForbidden(principal, AccessScope.inference_cancel)
        }
        if (requestId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "requestId required"))
        }
        for (p in ports.ledger.listProposals(requestId)) {
            if (p.state == ToolProposalState.PROPOSED || p.state == ToolProposalState.HOST_CLAIMED) {
                ports.ledger.updateProposalState(p.proposalId, ToolProposalState.CANCELLED)
            }
        }
        return ports.inference.cancel(principal, requestId)
    }

    // --- helpers ---

    private fun requireAuth(principal: PrincipalId, scope: AccessScope): Boolean =
        ports.scopes.allows(principal, scope)

    private fun <T> unauthorizedOrForbidden(
        principal: PrincipalId,
        scope: AccessScope,
    ): OmniResult<T> {
        // LAN / external without credentials → UNAUTHORIZED; principal present but scope missing → FORBIDDEN.
        val err = if (principal.value.isBlank()) {
            OmniError.UNAUTHORIZED(
                message = "authentication required (fail closed)",
                details = mapOf("scope" to scope.id),
            )
        } else {
            OmniError.FORBIDDEN(
                message = "missing required scope (fail closed)",
                details = mapOf(
                    "scope" to scope.id,
                    "principal" to principal.value,
                ),
            )
        }
        lastError.set(err)
        return OmniResult.err(err)
    }

    private fun gateStructuredCapabilities(modelRevisionId: String): OmniError? {
        val required = listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.REQUEST_LIFECYCLE,
        )
        return gateCaps(modelRevisionId, required)
    }

    private fun gateToolCapabilities(modelRevisionId: String): OmniError? {
        val required = listOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.TOOL_CALLING,
            CapabilityId.REQUEST_LIFECYCLE,
        )
        return gateCaps(modelRevisionId, required)
    }

    private fun gateCaps(modelRevisionId: String, required: List<CapabilityId>): OmniError? {
        for (cap in required) {
            when (val st = ports.capabilities.state(cap, modelRevisionId)) {
                CapabilityState.SUPPORTED,
                CapabilityState.CONDITIONAL,
                -> Unit
                CapabilityState.UNKNOWN -> {
                    return OmniError.CAPABILITY_UNKNOWN(
                        message = "capability cell unknown (fail closed)",
                        details = mapOf(
                            "capability" to cap.id,
                            "state" to st.name,
                            "modelRevisionId" to modelRevisionId,
                        ),
                    )
                }
                CapabilityState.UNSUPPORTED,
                CapabilityState.TEMPORARILY_UNAVAILABLE,
                -> {
                    return OmniError.CAPABILITY_UNSUPPORTED(
                        message = "capability not available",
                        details = mapOf(
                            "capability" to cap.id,
                            "state" to st.name,
                            "modelRevisionId" to modelRevisionId,
                        ),
                    )
                }
            }
        }
        return null
    }

    /**
     * POST_VALIDATE / REPAIR_RETRY must validate structured JSON against schema
     * (FEAT-TOOLS §2). NATIVE_CONSTRAINED trusts engine grammar when output present.
     * Missing terminal free-text without schema is already blocked by mode disclosure.
     */
    private fun validateStructuredOutput(
        mode: StructuredMode,
        schema: Map<String, Any?>,
        structuredJson: String?,
        terminal: Boolean,
        priorError: OmniError?,
    ): StructuredValidation {
        if (priorError != null) {
            return StructuredValidation(ValidationStatusCode.OUTPUT_INVALID, priorError)
        }
        if (!terminal) {
            return StructuredValidation(ValidationStatusCode.OK, null)
        }
        if (structuredJson.isNullOrBlank()) {
            return StructuredValidation(
                status = ValidationStatusCode.OUTPUT_INVALID,
                error = OmniError.INVALID_REQUEST(
                    message = "structured output missing at terminal (mode=${mode.name})",
                    details = mapOf(
                        "validationCode" to ValidationStatusCode.OUTPUT_INVALID.name,
                        "actualMode" to mode.name,
                    ),
                ),
            )
        }
        // Native constrained: still sanity-check parse when caller wants strict,
        // but POST_VALIDATE / REPAIR_RETRY always enforce schema instance rules.
        val needsSchemaCheck = mode == StructuredMode.POST_VALIDATE ||
            mode == StructuredMode.REPAIR_RETRY ||
            mode == StructuredMode.NATIVE_CONSTRAINED
        if (!needsSchemaCheck) {
            return StructuredValidation(ValidationStatusCode.OK, null)
        }
        return when (
            val v = StructuredOutputValidator.validateJson(structuredJson, schema, limits)
        ) {
            is StructuredOutputValidator.Outcome.Valid ->
                StructuredValidation(ValidationStatusCode.OK, null)
            is StructuredOutputValidator.Outcome.Invalid ->
                StructuredValidation(
                    status = v.code,
                    error = OmniError.INVALID_REQUEST(
                        message = v.reason,
                        details = v.details + mapOf(
                            "validationCode" to v.code.name,
                            "path" to v.path,
                            "actualMode" to mode.name,
                        ),
                    ),
                )
        }
    }

    private data class StructuredValidation(
        val status: ValidationStatusCode,
        val error: OmniError?,
    )
}
