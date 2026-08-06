package com.omnillm.features.routing.usecase

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.SessionHandleId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.routing.api.AliasResolveView
import com.omnillm.features.routing.api.PlanRouteSpec
import com.omnillm.features.routing.api.RouteSubmitIdentity
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.routing.api.RoutingCapabilityCellUi
import com.omnillm.features.routing.api.RoutingCapabilityNegotiation
import com.omnillm.features.routing.api.RoutingCandidateSpec
import com.omnillm.features.routing.api.RoutingDecisionView
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.api.RoutingSnapshot
import com.omnillm.features.routing.api.RoutingUiPhase
import com.omnillm.features.routing.domain.AliasResolution
import com.omnillm.features.routing.domain.AliasResolveResult
import com.omnillm.features.routing.domain.FallbackPolicyRules
import com.omnillm.features.routing.domain.PolicyBuildResult
import com.omnillm.features.routing.domain.SessionContinuityPolicy
import com.omnillm.features.routing.ports.RoutingFeaturePorts
import com.omnillm.features.routing.ports.requireLocalUiPrincipal
import com.omnillm.features.routing.projection.RoutingProjection
import com.omnillm.runtime.orchestrator.ActualRouting
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Default [RoutingApi] (FEAT-ROUTING use-cases).
 *
 * - Policy filter before plan (no silent cross-revision)
 * - Orchestrator plan/submit via ports only (ADR-010)
 * - Continuity disclosure on revision/backend switch (FEAT-ROUTING §4)
 * - Capability negotiation fail closed (INV-018)
 */
class RoutingService(
    private val ports: RoutingFeaturePorts,
) : RoutingApi {

    private val lock = Any()

    private var phase: RoutingUiPhase = RoutingUiPhase.EMPTY
    private var lastDecision: RoutingDecisionView? = null
    private var preference: RoutingPreferenceView? = null
    private var negotiation: RoutingCapabilityNegotiation? = null
    private var lastPlanning: PlanningResult? = null
    private var lastSubmit: SubmitResult? = null
    private var lastErrorCode: String? = null
    private var lastErrorMessage: String? = null
    private var loading: Boolean = false

    /**
     * Capabilities this feature requires (`specs/feature-capability-map.yaml` FEAT-ROUTING).
     */
    val requiredCapabilities: List<CapabilityId> = listOf(
        CapabilityId.MULTI_MODEL_ROUTING,
        CapabilityId.FALLBACK_POLICY,
        CapabilityId.CAPABILITY_NEGOTIATION,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.RESOURCE_ACCOUNTING,
    )

    override fun snapshot(): RoutingSnapshot = synchronized(lock) {
        RoutingSnapshot(
            phase = phase,
            lastDecision = lastDecision,
            preference = preference,
            negotiation = negotiation,
            lastErrorCode = lastErrorCode,
            lastErrorMessage = lastErrorMessage,
            aliasEntries = ports.aliases.snapshot().mapValues { it.value.hex },
        )
    }

    override fun resolveAlias(
        principal: PrincipalId,
        aliasOrRevisionHex: String,
    ): OmniResult<AliasResolveView> {
        requireLocalUiPrincipal(principal)
        return when (
            val r = AliasResolution.resolveAtAccept(aliasOrRevisionHex, ports.aliases.snapshot())
        ) {
            is AliasResolveResult.Ok -> OmniResult.ok(
                AliasResolveView(
                    input = aliasOrRevisionHex.trim(),
                    revisionIdHex = r.revisionId.hex,
                    resolvedFromAlias = r.resolvedFromAlias,
                ),
            )
            is AliasResolveResult.Invalid -> OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = r.message,
                    details = r.details + ("input" to aliasOrRevisionHex),
                ),
            )
        }
    }

    override fun negotiate(principal: PrincipalId): OmniResult<RoutingCapabilityNegotiation> {
        requireLocalUiPrincipal(principal)
        val result = buildNegotiation()
        synchronized(lock) {
            negotiation = result
            if (!result.allSupported) {
                lastErrorCode = OmniError.CAPABILITY_UNSUPPORTED().code.code
                lastErrorMessage = "routing capabilities not supported: ${result.blockingIds}"
                phase = RoutingUiPhase.ERROR
            } else if (phase == RoutingUiPhase.EMPTY || phase == RoutingUiPhase.ERROR) {
                phase = if (lastDecision != null) RoutingUiPhase.READY else RoutingUiPhase.EMPTY
                lastErrorCode = null
                lastErrorMessage = null
            }
        }
        return if (result.allSupported) {
            OmniResult.ok(result)
        } else {
            OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "routing capabilities not supported: ${result.blockingIds}",
                    details = mapOf(
                        "blocking" to result.blockingIds.joinToString(","),
                        "unsupported" to result.unsupportedIds.joinToString(","),
                    ),
                ),
            )
        }
    }

    override fun validatePreference(
        principal: PrincipalId,
        preference: RoutingPreferenceView,
    ): OmniResult<RoutingPreferenceView> {
        requireLocalUiPrincipal(principal)
        return when (val built = toPreference(preference)) {
            is PolicyBuildResult.Invalid ->
                OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = built.message,
                        details = built.details + ("code" to built.code),
                    ),
                )
            is PolicyBuildResult.Ok -> {
                synchronized(lock) {
                    this.preference = RoutingProjection.preferenceView(built.preference)
                    lastErrorCode = null
                    lastErrorMessage = null
                }
                OmniResult.ok(RoutingProjection.preferenceView(built.preference))
            }
        }
    }

    override suspend fun planRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
    ): OmniResult<RoutingDecisionView> {
        requireLocalUiPrincipal(principal)
        capGateError()?.let { return OmniResult.err(it) }

        setLoading(true)
        val pref = when (val b = toPreference(spec.preference)) {
            is PolicyBuildResult.Invalid -> {
                fail(OmniError.INVALID_REQUEST(message = b.message, details = b.details + ("code" to b.code)))
                return OmniResult.err(
                    OmniError.INVALID_REQUEST(message = b.message, details = b.details + ("code" to b.code)),
                )
            }
            is PolicyBuildResult.Ok -> b.preference
        }

        // Alias fixed at accept — never re-resolved mid-request (FEAT-ROUTING §1).
        val requested = when (
            val resolved = AliasResolution.resolveAtAccept(
                spec.requestedRevisionIdHex,
                ports.aliases.snapshot(),
            )
        ) {
            is AliasResolveResult.Ok -> resolved.revisionId
            is AliasResolveResult.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = resolved.message,
                    details = resolved.details + mapOf(
                        "requestedRevisionId" to spec.requestedRevisionIdHex,
                    ),
                )
                fail(err)
                return OmniResult.err(err)
            }
        }

        val candidates = try {
            spec.candidates.map { toCandidate(it) }
        } catch (e: IllegalArgumentException) {
            val err = OmniError.INVALID_REQUEST(message = e.message ?: "invalid candidate")
            fail(err)
            return OmniResult.err(err)
        }
        if (candidates.isEmpty()) {
            val err = OmniError.INVALID_REQUEST(message = "at least one candidate is required")
            fail(err)
            return OmniResult.err(err)
        }

        // Feature-level policy filter first (stricter NONE semantics than bare revision match).
        // ALLOW_LIST allowlist enforced here + orchestrator orderByFallbackPolicy.
        val filtered = FallbackPolicyRules.apply(requested, pref, candidates)
        if (!filtered.hasAllowed) {
            val view = RoutingProjection.decisionView(
                requestedRevisionHex = requested.hex,
                preference = pref,
                allCandidates = candidates,
                policyRejections = filtered.rejections,
                planning = null,
                selected = null,
                actual = null,
                continuity = null,
            )
            storeDecision(view, pref, planning = null, submit = null, error = OmniError.ADMISSION_REJECTED(
                message = "no candidates remain after fallback policy filter",
                details = mapOf(
                    "fallbackPolicy" to pref.fallbackPolicy.name,
                    "rejectionCount" to filtered.rejections.size.toString(),
                ),
            ))
            return OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "no candidates remain after fallback policy filter",
                    details = mapOf(
                        "fallbackPolicy" to pref.fallbackPolicy.name,
                        "rejectionCount" to filtered.rejections.size.toString(),
                    ),
                ),
            )
        }

        val orchRequest = try {
            buildPlanRequest(
                principal = principal,
                requested = requested,
                preference = pref,
                candidates = filtered.allowed,
                requiredCapabilityIds = spec.requiredCapabilityIds,
            )
        } catch (e: IllegalArgumentException) {
            val err = OmniError.INVALID_REQUEST(message = e.message ?: "invalid orchestration request")
            fail(err)
            return OmniResult.err(err)
        }

        return when (val planned = ports.orchestrator.plan(orchRequest)) {
            is OmniResult.Err -> {
                val view = RoutingProjection.decisionView(
                    requestedRevisionHex = requested.hex,
                    preference = pref,
                    allCandidates = candidates,
                    policyRejections = filtered.rejections,
                    planning = null,
                    selected = null,
                    actual = null,
                    continuity = null,
                )
                storeDecision(view, pref, planning = null, submit = null, error = planned.error)
                OmniResult.err(planned.error)
            }
            is OmniResult.Ok -> {
                val planning = planned.value
                val head = planning.viable.firstOrNull()?.candidate
                val continuity = head?.let {
                    SessionContinuityPolicy.decide(
                        requestedRevisionId = requested,
                        selected = it,
                        sourceSession = spec.sourceSessionPresent,
                        sourceRevision = spec.sourceRevisionIdHex?.let { hex ->
                            ModelRevisionId.parse(hex.lowercase())
                        },
                        sourceLoadKeyDigest = spec.sourceLoadKeyDigestHex,
                        sourceEngineBuildId = spec.sourceEngineBuildId,
                        provenStateTransfer = spec.provenStateTransfer,
                    )
                }
                val actual = head?.let { selected ->
                    ActualRouting(
                        modelRevisionId = selected.modelRevisionId,
                        engineBuildId = selected.engineBuildId,
                        backend = selected.backend,
                        placementClass = selected.placementClass,
                        installationId = selected.installationId,
                        candidateId = selected.candidateId,
                        usedFallback = FallbackPolicyRules.isFallbackUsage(requested, selected),
                        fallbackPolicy = pref.fallbackPolicy,
                        rejectionTrail = filtered.rejections + planning.rejections,
                    )
                }
                // Guard: never silent cross-revision under non-ALLOW_LIST.
                if (actual != null &&
                    actual.modelRevisionId.hex != requested.hex &&
                    pref.fallbackPolicy != FallbackPolicy.ALLOW_LIST
                ) {
                    val err = OmniError.INTERNAL(
                        message = "cross-revision selection violated fallback policy (fail closed)",
                        details = mapOf(
                            "fallbackPolicy" to pref.fallbackPolicy.name,
                            "selectedRevision" to actual.modelRevisionId.hex,
                        ),
                    )
                    fail(err)
                    return OmniResult.err(err)
                }
                val view = RoutingProjection.decisionView(
                    requestedRevisionHex = requested.hex,
                    preference = pref,
                    allCandidates = candidates,
                    policyRejections = filtered.rejections,
                    planning = planning,
                    selected = head,
                    actual = actual,
                    continuity = continuity,
                )
                storeDecision(view, pref, planning = planning, submit = null, error = null)
                OmniResult.ok(view)
            }
        }
    }

    override suspend fun submitRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
        identity: RouteSubmitIdentity,
    ): OmniResult<RoutingDecisionView> {
        requireLocalUiPrincipal(principal)
        capGateError()?.let { return OmniResult.err(it) }

        setLoading(true)
        val pref = when (val b = toPreference(spec.preference)) {
            is PolicyBuildResult.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = b.message,
                    details = b.details + ("code" to b.code),
                )
                fail(err)
                return OmniResult.err(err)
            }
            is PolicyBuildResult.Ok -> b.preference
        }

        val requested = when (
            val resolved = AliasResolution.resolveAtAccept(
                spec.requestedRevisionIdHex,
                ports.aliases.snapshot(),
            )
        ) {
            is AliasResolveResult.Ok -> resolved.revisionId
            is AliasResolveResult.Invalid -> {
                val err = OmniError.INVALID_REQUEST(
                    message = resolved.message,
                    details = resolved.details + mapOf(
                        "requestedRevisionId" to spec.requestedRevisionIdHex,
                    ),
                )
                fail(err)
                return OmniResult.err(err)
            }
        }

        val candidates = try {
            spec.candidates.map { toCandidate(it) }
        } catch (e: IllegalArgumentException) {
            val err = OmniError.INVALID_REQUEST(message = e.message ?: "invalid candidate")
            fail(err)
            return OmniResult.err(err)
        }
        if (candidates.isEmpty()) {
            val err = OmniError.INVALID_REQUEST(message = "at least one candidate is required")
            fail(err)
            return OmniResult.err(err)
        }

        // Allowlist fallback integrated with orchestrator path (FEAT-ROUTING §1/§6).
        val filtered = FallbackPolicyRules.apply(requested, pref, candidates)
        if (!filtered.hasAllowed) {
            val err = OmniError.ADMISSION_REJECTED(
                message = "no candidates remain after fallback policy filter",
                details = mapOf("fallbackPolicy" to pref.fallbackPolicy.name),
            )
            val view = RoutingProjection.decisionView(
                requestedRevisionHex = requested.hex,
                preference = pref,
                allCandidates = candidates,
                policyRejections = filtered.rejections,
                planning = null,
                selected = null,
                actual = null,
                continuity = null,
                requestId = identity.requestId,
            )
            storeDecision(view, pref, planning = null, submit = null, error = err)
            return OmniResult.err(err)
        }

        val orchRequest = try {
            buildSubmitRequest(
                principal = principal,
                requested = requested,
                preference = pref,
                candidates = filtered.allowed,
                requiredCapabilityIds = spec.requiredCapabilityIds,
                identity = identity,
            )
        } catch (e: IllegalArgumentException) {
            val err = OmniError.INVALID_REQUEST(message = e.message ?: "invalid orchestration request")
            fail(err)
            return OmniResult.err(err)
        }

        return when (val submitted = ports.orchestrator.submit(orchRequest)) {
            is OmniResult.Err -> {
                val view = RoutingProjection.decisionView(
                    requestedRevisionHex = requested.hex,
                    preference = pref,
                    allCandidates = candidates,
                    policyRejections = filtered.rejections,
                    planning = null,
                    selected = null,
                    actual = null,
                    continuity = null,
                    requestId = identity.requestId,
                )
                storeDecision(view, pref, planning = null, submit = null, error = submitted.error)
                OmniResult.err(submitted.error)
            }
            is OmniResult.Ok -> {
                val submit = submitted.value
                val planning = submit.planning
                val head = planning?.viable?.firstOrNull()?.candidate
                    ?: filtered.allowed.firstOrNull()
                val actual = submit.actualRouting ?: head?.let { selected ->
                    ActualRouting(
                        modelRevisionId = selected.modelRevisionId,
                        engineBuildId = selected.engineBuildId,
                        backend = selected.backend,
                        placementClass = selected.placementClass,
                        installationId = selected.installationId,
                        candidateId = selected.candidateId,
                        usedFallback = FallbackPolicyRules.isFallbackUsage(requested, selected),
                        fallbackPolicy = pref.fallbackPolicy,
                        rejectionTrail = filtered.rejections + (planning?.rejections.orEmpty()),
                    )
                }
                if (actual != null &&
                    actual.modelRevisionId.hex != requested.hex &&
                    pref.fallbackPolicy != FallbackPolicy.ALLOW_LIST
                ) {
                    val err = OmniError.INTERNAL(
                        message = "cross-revision selection violated fallback policy (fail closed)",
                    )
                    fail(err)
                    return OmniResult.err(err)
                }
                val continuity = head?.let {
                    SessionContinuityPolicy.decide(
                        requestedRevisionId = requested,
                        selected = it,
                        sourceSession = spec.sourceSessionPresent || identity.sourceSessionId != null,
                        sourceRevision = spec.sourceRevisionIdHex?.let { hex ->
                            ModelRevisionId.parse(hex.lowercase())
                        },
                        sourceLoadKeyDigest = spec.sourceLoadKeyDigestHex,
                        sourceEngineBuildId = spec.sourceEngineBuildId,
                        provenStateTransfer = spec.provenStateTransfer,
                    )
                }
                val view = RoutingProjection.decisionView(
                    requestedRevisionHex = requested.hex,
                    preference = pref,
                    allCandidates = candidates,
                    policyRejections = filtered.rejections,
                    planning = planning,
                    selected = head,
                    actual = actual,
                    continuity = continuity,
                    earliest = submit.earliestStart,
                    requestId = identity.requestId,
                    requestState = submit.state,
                )
                storeDecision(view, pref, planning = planning, submit = submit, error = null)
                OmniResult.ok(view)
            }
        }
    }

    override suspend fun queryRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<RoutingDecisionView> {
        requireLocalUiPrincipal(principal)
        val id = try {
            RequestId.parse(requestId)
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "invalid requestId",
                    details = mapOf("requestId" to requestId),
                ),
            )
        }
        val q = ports.orchestrator.query(id)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to requestId),
                ),
            )
        val prev = synchronized(lock) { lastDecision }
        val actual = q.actualRouting
        val view = if (prev != null && prev.requestId == requestId) {
            prev.copy(
                requestState = q.terminalState ?: q.state,
                actualRouting = actual?.let { RoutingProjection.actualRoutingUi(it) }
                    ?: prev.actualRouting,
            )
        } else {
            RoutingDecisionView(
                requestedRevisionIdHex = actual?.modelRevisionId?.hex
                    ?: prev?.requestedRevisionIdHex
                    ?: "",
                preference = prev?.preference
                    ?: RoutingPreferenceView(
                        fallbackPolicy = actual?.fallbackPolicy ?: FallbackPolicy.NONE,
                        minimumPlacementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    ),
                candidates = prev?.candidates.orEmpty(),
                rejections = actual?.rejectionTrail?.map { RoutingProjection.rejectionRow(it) }
                    .orEmpty(),
                viableCount = if (actual != null) 1 else 0,
                selected = prev?.selected,
                actualRouting = actual?.let { RoutingProjection.actualRoutingUi(it) },
                continuity = prev?.continuity,
                requestId = requestId,
                requestState = q.terminalState ?: q.state,
            )
        }
        synchronized(lock) {
            lastDecision = view
            phase = RoutingUiPhase.READY
            lastErrorCode = q.errorCode
            lastErrorMessage = null
            loading = false
        }
        return OmniResult.ok(view)
    }

    override suspend fun cancelRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<Unit> {
        requireLocalUiPrincipal(principal)
        val id = try {
            RequestId.parse(requestId)
        } catch (e: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "invalid requestId"),
            )
        }
        return when (val r = ports.orchestrator.cancel(id)) {
            is OmniResult.Ok -> {
                synchronized(lock) {
                    lastDecision = lastDecision?.copy(requestState = "CANCEL_REQUESTED")
                }
                r
            }
            is OmniResult.Err -> {
                fail(r.error)
                r
            }
        }
    }

    override fun lastPlanningResult(): PlanningResult? = synchronized(lock) { lastPlanning }

    override fun lastSubmitResult(): SubmitResult? = synchronized(lock) { lastSubmit }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun buildNegotiation(): RoutingCapabilityNegotiation {
        val cells = requiredCapabilities.map { cap ->
            RoutingCapabilityCellUi(
                capabilityId = cap.id,
                state = ports.capabilities.state(cap),
            )
        }
        val allSupported = cells.all {
            it.state == CapabilityState.SUPPORTED || it.state == CapabilityState.CONDITIONAL
        }
        return RoutingCapabilityNegotiation(allSupported = allSupported, cells = cells)
    }

    private fun capGateError(): OmniError? {
        val neg = buildNegotiation()
        synchronized(lock) { negotiation = neg }
        if (neg.allSupported) return null
        val err = OmniError.CAPABILITY_UNSUPPORTED(
            message = "routing capabilities not supported: ${neg.blockingIds}",
            details = mapOf("blocking" to neg.blockingIds.joinToString(",")),
        )
        fail(err)
        return err
    }

    private fun toPreference(view: RoutingPreferenceView): PolicyBuildResult {
        val allowlist = try {
            view.revisionAllowlistHex.map { ModelRevisionId.parse(it.lowercase()) }
        } catch (e: IllegalArgumentException) {
            return PolicyBuildResult.Invalid(
                message = "invalid revision in allowlist: ${e.message}",
                code = "POLICY",
            )
        }
        return FallbackPolicyRules.buildPreference(
            fallbackPolicy = view.fallbackPolicy,
            revisionAllowlist = allowlist,
            allowedBackends = view.allowedBackends,
            minimumPlacementClass = view.minimumPlacementClass,
            preferredBackend = view.preferredBackend,
        )
    }

    private fun toCandidate(spec: RoutingCandidateSpec): RoutingCandidate {
        if (!PlacementClassLabels.isKnown(spec.placementClass)) {
            error("unknown placement class: ${spec.placementClass}")
        }
        return RoutingCandidate(
            candidateId = spec.candidateId,
            modelRevisionId = ModelRevisionId.parse(spec.modelRevisionIdHex.lowercase()),
            installationId = InstallationId.parse(spec.installationId),
            engineBuildId = EngineBuildId.parse(spec.engineBuildId),
            backend = spec.backend,
            placementClass = spec.placementClass,
            loadKeyDigest = Sha256Digest.parse(spec.loadKeyDigestHex.lowercase()),
            isPrimary = spec.isPrimary,
            deviceExecutionFingerprint = DeviceExecutionFingerprint.parse(spec.deviceExecutionFingerprint),
        )
    }

    private fun buildPlanRequest(
        principal: PrincipalId,
        requested: ModelRevisionId,
        preference: RoutingPreference,
        candidates: List<RoutingCandidate>,
        requiredCapabilityIds: Set<String>,
    ): OrchestrationRequest {
        val caps = resolveCapabilities(requiredCapabilityIds)
        // Plan-only uses ephemeral ids — does not claim durable ledger until submit.
        return OrchestrationRequest(
            requestId = RequestId.parse(java.util.UUID.randomUUID().toString()),
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse("plan-only-${java.util.UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = Sha256Digest.parse("a".repeat(64)),
            requiredCapabilities = caps,
            requestedRevisionId = requested,
            candidates = candidates,
            routing = preference,
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 4,
        )
    }

    private fun buildSubmitRequest(
        principal: PrincipalId,
        requested: ModelRevisionId,
        preference: RoutingPreference,
        candidates: List<RoutingCandidate>,
        requiredCapabilityIds: Set<String>,
        identity: RouteSubmitIdentity,
    ): OrchestrationRequest {
        val caps = resolveCapabilities(requiredCapabilityIds)
        if (!CostClassLabels.isKnown(identity.costClass)) {
            error("unknown cost class: ${identity.costClass}")
        }
        return OrchestrationRequest(
            requestId = RequestId.parse(identity.requestId),
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse(identity.idempotencyKey),
            operationKind = identity.operationKind,
            canonicalRequestDigest = Sha256Digest.parse(identity.canonicalRequestDigestHex.lowercase()),
            requiredCapabilities = caps,
            requestedRevisionId = requested,
            candidates = candidates,
            routing = preference,
            costClass = identity.costClass,
            costUnits = identity.costUnits,
            sourceSessionId = identity.sourceSessionId?.let { SessionHandleId.parse(it) },
            sourceSessionEpoch = identity.sourceSessionEpoch,
            runtimeEpoch = identity.runtimeEpoch,
            revocationEpoch = identity.revocationEpoch,
            deadlineMonotonic = identity.deadlineMonotonic,
        )
    }

    private fun resolveCapabilities(ids: Set<String>): Set<CapabilityId> {
        if (ids.isEmpty()) return setOf(CapabilityId.TEXT_GENERATION)
        return ids.map { raw ->
            CapabilityId.fromId(raw)
                ?: error("unknown capability (fail closed): $raw")
        }.toSet()
    }

    private fun setLoading(value: Boolean) {
        synchronized(lock) {
            loading = value
            phase = RoutingProjection.resolveUiPhase(
                hasDecision = lastDecision != null,
                loading = value,
                error = lastErrorCode != null && lastDecision == null,
            )
        }
    }

    private fun fail(error: OmniError) {
        synchronized(lock) {
            loading = false
            lastErrorCode = error.code.code
            lastErrorMessage = error.message
            phase = RoutingUiPhase.ERROR
        }
    }

    private fun storeDecision(
        view: RoutingDecisionView,
        pref: RoutingPreference,
        planning: PlanningResult?,
        submit: SubmitResult?,
        error: OmniError?,
    ) {
        synchronized(lock) {
            loading = false
            lastDecision = view
            preference = RoutingProjection.preferenceView(pref)
            lastPlanning = planning
            lastSubmit = submit
            if (error != null) {
                lastErrorCode = error.code.code
                lastErrorMessage = error.message
                phase = RoutingUiPhase.ERROR
            } else {
                lastErrorCode = null
                lastErrorMessage = null
                phase = RoutingUiPhase.READY
            }
        }
    }
}
