package com.omnillm.ui.admin

import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniDiagnosticExportJobParameters
import ai.omnillm.api.OmniJobSpec
import android.os.RemoteException
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.CancelReportSpec
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.ContentReportInfoView
import com.omnillm.features.contentreport.api.ContentReportReviewView
import com.omnillm.features.contentreport.api.ContentReportSnapshot
import com.omnillm.features.contentreport.api.CreateProposalSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.GrantIssueResult
import com.omnillm.features.contentreport.api.PayloadPlan
import com.omnillm.features.contentreport.api.ReceiptView
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.api.SubmitResult
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ContentReportPayload
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.projection.ContentReportStateProjection
import com.omnillm.features.diagnostics.api.CancelExportSpec
import com.omnillm.features.diagnostics.api.ClientInferenceIdentity
import com.omnillm.features.diagnostics.api.DeleteBundleSpec
import com.omnillm.features.diagnostics.api.DiagnosticJobHandle
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.diagnostics.api.DiagnosticsSnapshot
import com.omnillm.features.diagnostics.api.EvidencedMetricView
import com.omnillm.features.diagnostics.api.RedactionAllowlistExport
import com.omnillm.features.diagnostics.api.StartExportSpec
import com.omnillm.features.diagnostics.domain.CategorySensitivity
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.diagnostics.domain.ExportCategoryPreview
import com.omnillm.features.diagnostics.export.DiagnosticBundleBuilder
import com.omnillm.features.diagnostics.ports.parseExportCategory
import com.omnillm.features.routing.api.AliasResolveView
import com.omnillm.features.routing.api.PlanRouteSpec
import com.omnillm.features.routing.api.RouteSubmitIdentity
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.routing.api.RoutingCapabilityCellUi
import com.omnillm.features.routing.api.RoutingCapabilityNegotiation
import com.omnillm.features.routing.api.RoutingDecisionView
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.api.RoutingSnapshot
import com.omnillm.features.routing.api.RoutingUiPhase
import com.omnillm.features.routing.domain.FallbackPolicyRules
import com.omnillm.features.routing.domain.PolicyBuildResult
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.policy.SettingValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Admin-binder projections for the diagnostics / routing / content-report
 * feature APIs (C-06; INV-001).
 *
 * The three screens previously rendered their disconnected-fallback state in
 * production because `UiSession` never attached their ViewModels. These
 * projections feed them real plane data over the non-exported [IOmniAdmin]:
 * - reads project from `getSnapshot()` (runtime / jobs / models / settings)
 * - mutations go through the durable Admin command / job / content-report AIDL
 *   surface only — never invent control-plane capabilities that are absent
 *   (fail closed with the reason surfaced to the UI).
 */

// ── Diagnostics ─────────────────────────────────────────────────────────────

/**
 * [DiagnosticsApi] projection over the Admin binder. Export jobs are claimed
 * durably through the shared Job Manager (`DIAGNOSTIC_EXPORT`, same path the
 * HTTP surface uses); bundle sealing / store stay control-plane only.
 */
class AdminDiagnosticsApi(
    private val admin: IOmniAdmin,
) : DiagnosticsApi {

    private val seq = AtomicLong(0L)
    private val lock = Any()
    private var lastPlan: DiagnosticExportPlan? = null
    private var lastError: OmniError? = null
    private var activeJobId: String? = null

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<DiagnosticsSnapshot> {
        requireLocalUi(principal)
        return try {
            val jobHandle = synchronized(lock) { activeJobId }?.let { id ->
                try {
                    admin.getJob(id).takeIf { !it.jobId.isNullOrBlank() }
                } catch (_: RemoteException) {
                    null
                }
            }?.let { aidlToDiagnosticJobHandle(it) }
            val plan = synchronized(lock) { lastPlan }
            val error = synchronized(lock) { lastError }
            OmniResult.ok(
                DiagnosticsSnapshot(
                    snapshotVersion = seq.incrementAndGet(),
                    uiPhase = projectPhase(jobHandle, hasPreview = plan != null, lastError = error),
                    exportPlan = plan,
                    activeJob = jobHandle,
                    bundles = emptyList(),
                    metrics = emptyList(),
                    serviceHealthLevel = null,
                    serviceHealthEvidence = null,
                    degradedReasons = emptyList(),
                    reason = null,
                    lastError = error,
                    redactionAllowlist = buildRedactionAllowlistExport(),
                ),
            )
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "diagnostics snapshot remote failure"))
        }
    }

    override fun planExport(
        principal: PrincipalId,
        includeDetail: Boolean,
        selectedCategories: List<String>,
    ): OmniResult<DiagnosticExportPlan> {
        requireLocalUi(principal)
        val names = selectedCategories.ifEmpty { DEFAULT_CATEGORY_NAMES }
        val categories = mutableListOf<DiagnosticAllowlist.Category>()
        for (name in names) {
            val cat = parseExportCategory(name)
                ?: return OmniResult.err(
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "unknown diagnostic export category",
                        details = mapOf("category" to name),
                    ),
                )
            if (cat !in categories) categories += cat
        }
        val previews = categories.map { category ->
            ExportCategoryPreview(
                category = category,
                sensitivity = CategorySensitivity.forCategory(category),
                estimatedBytes = DiagnosticBundleBuilder.estimateCategoryBytes(category, includeDetail),
                includedByDefault = category.name in DEFAULT_CATEGORY_NAMES,
                descriptionKey = "diagnostics.category.${category.name.lowercase()}",
            )
        }
        val plan = DiagnosticExportPlan(
            categories = previews,
            estimatedTotalBytes = previews.sumOf { it.estimatedBytes },
            defaultTtlSeconds = DEFAULT_TTL_SECONDS,
            encryptionOptional = true,
            includeDetail = includeDetail,
        )
        synchronized(lock) {
            lastPlan = plan
            lastError = null
        }
        return OmniResult.ok(plan)
    }

    override suspend fun startExport(
        principal: PrincipalId,
        spec: StartExportSpec,
    ): OmniResult<DiagnosticJobHandle> {
        requireLocalUi(principal)
        val categoryNames = spec.categories.ifEmpty { DEFAULT_CATEGORY_NAMES }
        return try {
            val aidl = OmniJobSpec()
            aidl.jobId = spec.jobId
            aidl.kind = "DIAGNOSTIC_EXPORT"
            aidl.command = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
            }
            aidl.diagnosticExport = OmniDiagnosticExportJobParameters().apply {
                includeDetail = spec.includeDetail
                categories = categoryNames.toTypedArray()
                ttlSeconds = (spec.ttlSeconds ?: DEFAULT_TTL_SECONDS).toLong()
            }
            val info = admin.startJob(aidl)
            val handle = aidlToDiagnosticJobHandle(info) ?: return OmniResult.err(
                OmniError.INTERNAL(message = "invalid job info from startJob"),
            )
            synchronized(lock) {
                activeJobId = handle.jobId
                lastError = null
            }
            OmniResult.ok(handle)
        } catch (e: RemoteException) {
            val err = OmniError.INTERNAL(message = e.message ?: "startExport remote failure")
            synchronized(lock) { lastError = err }
            OmniResult.err(err)
        }
    }

    override suspend fun cancelExport(
        principal: PrincipalId,
        spec: CancelExportSpec,
    ): OmniResult<DiagnosticJobHandle> {
        requireLocalUi(principal)
        return try {
            val current = try {
                admin.getJob(spec.jobId)
            } catch (_: RemoteException) {
                null
            }
            val cmd = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = spec.command.canonicalInputDigest
                hasExpectedVersion = true
                expectedVersion = current?.resourceVersion ?: 0L
            }
            val result = admin.cancelJob(spec.jobId, cmd)
            if (result.error != null) {
                val err = aidlErrorToDomain(result.error)
                synchronized(lock) { lastError = err }
                return OmniResult.err(err)
            }
            val info = admin.getJob(spec.jobId)
            val handle = aidlToDiagnosticJobHandle(info)
                ?: return OmniResult.err(OmniError.INTERNAL(message = "invalid job info after cancel"))
            if (handle.state == "CANCELLED") synchronized(lock) { activeJobId = null }
            OmniResult.ok(handle)
        } catch (e: RemoteException) {
            val err = OmniError.INTERNAL(message = e.message ?: "cancelExport remote failure")
            synchronized(lock) { lastError = err }
            OmniResult.err(err)
        }
    }

    override suspend fun queryExportJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<DiagnosticJobHandle> {
        requireLocalUi(principal)
        return try {
            val handle = aidlToDiagnosticJobHandle(admin.getJob(jobId))
                ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
            OmniResult.ok(handle)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "queryExportJob remote failure"))
        }
    }

    override suspend fun getBundle(
        principal: PrincipalId,
        bundleId: String,
    ): OmniResult<DiagnosticBundleSnapshot> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "diagnostic bundle store is control-plane only (not on Admin binder)",
            ),
        )

    override suspend fun listBundles(principal: PrincipalId): OmniResult<List<DiagnosticBundleSnapshot>> =
        OmniResult.ok(emptyList())

    override suspend fun collectAndSeal(
        jobId: String,
        forceFailClosed: Boolean,
    ): OmniResult<DiagnosticBundleSnapshot> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "bundle collection/seal runs on the control plane (not on Admin binder)",
            ),
        )

    override suspend fun deleteBundle(
        principal: PrincipalId,
        spec: DeleteBundleSpec,
    ): OmniResult<DiagnosticBundleSnapshot> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "diagnostic bundle delete is control-plane only",
            ),
        )

    override fun newClientInferenceIdentity(idempotencyKey: String?): ClientInferenceIdentity {
        val requestId = RequestId.parse(UUID.randomUUID().toString()).value
        val key = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: "diag-inf-${UUID.randomUUID()}"
        com.omnillm.core.contracts.IdempotencyKey.parse(key)
        return ClientInferenceIdentity(requestId = requestId, idempotencyKey = key)
    }

    override fun listEvidencedMetrics(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> {
        requireLocalUi(principal)
        // No observability facade over Admin binder — empty is honest, not invented.
        return OmniResult.ok(emptyList())
    }

    override fun exportRedactionAllowlist(
        principal: PrincipalId,
    ): OmniResult<RedactionAllowlistExport> {
        requireLocalUi(principal)
        return OmniResult.ok(buildRedactionAllowlistExport())
    }

    private fun buildRedactionAllowlistExport(): RedactionAllowlistExport {
        val doc = DiagnosticAllowlist.exportSchemaDocument()
        @Suppress("UNCHECKED_CAST")
        val categories = doc["categories"] as Map<String, Map<String, Any>>
        val fieldsByCategory = categories.mapValues { (_, body) ->
            (body["fields"] as List<*>).map { it.toString() }
        }
        @Suppress("UNCHECKED_CAST")
        val never = (doc["neverExport"] as List<*>).map { it.toString() }
        return RedactionAllowlistExport(
            schemaVersion = doc["schemaVersion"] as String,
            policy = doc["policy"] as String,
            categoryCount = doc["categoryCount"] as Int,
            allowedFieldCount = doc["allowedFieldCount"] as Int,
            fieldsByCategory = fieldsByCategory,
            neverExport = never,
            canonicalText = DiagnosticAllowlist.exportSchemaCanonicalText(),
        )
    }

    private fun projectPhase(
        activeJob: DiagnosticJobHandle?,
        hasPreview: Boolean,
        lastError: OmniError?,
    ): String = when {
        lastError != null && activeJob == null -> DiagnosticUiPhases.ERROR
        activeJob != null -> when (activeJob.state) {
            "CANCELLED" -> DiagnosticUiPhases.CANCELLED
            "FAILED" -> DiagnosticUiPhases.ERROR
            else -> DiagnosticUiPhases.LOADING
        }
        hasPreview -> DiagnosticUiPhases.PREVIEW
        else -> DiagnosticUiPhases.EMPTY
    }

    private fun aidlToDiagnosticJobHandle(info: ai.omnillm.api.OmniJobInfo?): DiagnosticJobHandle? {
        if (info == null || info.jobId.isNullOrBlank()) return null
        return DiagnosticJobHandle(
            jobId = info.jobId,
            bundleId = null,
            kind = "DIAGNOSTIC_EXPORT",
            state = info.state.orEmpty().ifBlank { "QUEUED" },
            resourceVersion = info.resourceVersion,
            createdNew = true,
            cancelRequested = false,
            progressPhase = null,
            progressRatio = null,
            error = info.error?.let {
                com.omnillm.core.errors.generated.OmniErrorCode.fromCode(it.code.orEmpty())
                    ?.let { code -> OmniError.of(code, it.message) }
            },
        )
    }

    companion object {
        const val DEFAULT_TTL_SECONDS: Int = 86_400
        val DEFAULT_CATEGORY_NAMES: Set<String> = setOf(
            "MANIFEST",
            "RUNTIME_VERSIONS",
            "CONFIGURATION",
            "CAPABILITY_SNAPSHOT",
            "MODEL_ENGINE_IDS",
            "REQUEST_JOB_STATE",
            "RESOURCE_SNAPSHOT",
            "ERROR_CHAIN",
            "EVIDENCE_LABELS",
            "REPRODUCTION_HINTS",
            "FILE_DIGESTS",
            "INTEGRITY_RESULTS",
        )
    }
}

// ── Routing ─────────────────────────────────────────────────────────────────

/**
 * [RoutingApi] projection over the Admin binder (C-06).
 *
 * Read side: preference from the durable `runtime.fallbackPolicy` setting and
 * capability cells from the plane's per-model evidence
 * ([IOmniAdmin.getInferenceCapabilityState]). Mutation side: preference
 * writes go through the durable settings command; plan/submit/query/cancel
 * require the control-plane Orchestrator and fail closed with the reason
 * surfaced to the UI (no silent cross-revision routing from the UI process).
 */
class AdminRoutingApi(
    private val admin: IOmniAdmin,
) : RoutingApi {

    private val lock = Any()
    private var lastDecision: RoutingDecisionView? = null
    private var negotiation: RoutingCapabilityNegotiation? = null
    private var lastErrorCode: String? = null
    private var lastErrorMessage: String? = null

    val requiredCapabilities: List<CapabilityId> = listOf(
        CapabilityId.MULTI_MODEL_ROUTING,
        CapabilityId.FALLBACK_POLICY,
        CapabilityId.CAPABILITY_NEGOTIATION,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.RESOURCE_ACCOUNTING,
    )

    override fun snapshot(): RoutingSnapshot = synchronized(lock) {
        RoutingSnapshot(
            phase = RoutingUiPhase.READY,
            lastDecision = lastDecision,
            preference = preferenceFromSettings(),
            negotiation = negotiation,
            lastErrorCode = lastErrorCode,
            lastErrorMessage = lastErrorMessage,
            aliasEntries = emptyMap(),
        )
    }

    override fun negotiate(principal: PrincipalId): OmniResult<RoutingCapabilityNegotiation> {
        requireLocalUi(principal)
        val cells = requiredCapabilities.map { cap ->
            RoutingCapabilityCellUi(
                capabilityId = cap.id,
                state = capabilityState(cap),
            )
        }
        val allSupported = cells.all {
            it.state == CapabilityState.SUPPORTED || it.state == CapabilityState.CONDITIONAL
        }
        val result = RoutingCapabilityNegotiation(allSupported = allSupported, cells = cells)
        synchronized(lock) {
            negotiation = result
            if (!result.allSupported) {
                lastErrorCode = OmniError.CAPABILITY_UNSUPPORTED().code.code
                lastErrorMessage = "routing capabilities not supported over Admin binder: " +
                    result.blockingIds.joinToString(",")
            } else {
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
                        "reason" to "no plane capability evidence for an installed model",
                    ),
                ),
            )
        }
    }

    override fun validatePreference(
        principal: PrincipalId,
        preference: RoutingPreferenceView,
    ): OmniResult<RoutingPreferenceView> {
        requireLocalUi(principal)
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = preference.fallbackPolicy,
            revisionAllowlist = preference.revisionAllowlistHex.mapNotNull {
                try {
                    com.omnillm.core.canonical.generated.ModelRevisionId.parse(it.lowercase())
                } catch (_: IllegalArgumentException) {
                    null
                }
            },
            allowedBackends = preference.allowedBackends,
            minimumPlacementClass = preference.minimumPlacementClass,
            preferredBackend = preference.preferredBackend,
        )
        if (built is PolicyBuildResult.Invalid) {
            val err = OmniError.INVALID_REQUEST(
                message = built.message,
                details = built.details + ("code" to built.code),
            )
            synchronized(lock) {
                lastErrorCode = err.code.code
                lastErrorMessage = err.message
            }
            return OmniResult.err(err)
        }
        // Persist the catalog policy via the durable settings command.
        return when (
            val patched = patchFallbackPolicySetting(preference.fallbackPolicy.name)
        ) {
            is OmniResult.Err -> {
                synchronized(lock) {
                    lastErrorCode = patched.error.code.code
                    lastErrorMessage = patched.error.message
                }
                OmniResult.err(patched.error)
            }
            is OmniResult.Ok -> {
                synchronized(lock) {
                    lastErrorCode = null
                    lastErrorMessage = null
                }
                OmniResult.ok(preference)
            }
        }
    }

    override fun resolveAlias(
        principal: PrincipalId,
        aliasOrRevisionHex: String,
    ): OmniResult<AliasResolveView> {
        requireLocalUi(principal)
        val input = aliasOrRevisionHex.trim()
        if (input.matches(HEX64)) {
            return OmniResult.ok(AliasResolveView(input = input, revisionIdHex = input, resolvedFromAlias = false))
        }
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "alias table is control-plane only; Admin binder resolves exact revisions only",
            ),
        )
    }

    override suspend fun planRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
    ): OmniResult<RoutingDecisionView> =
        orchestratorOnly("planRoute")

    override suspend fun submitRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
        identity: RouteSubmitIdentity,
    ): OmniResult<RoutingDecisionView> =
        orchestratorOnly("submitRoute")

    override suspend fun queryRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<RoutingDecisionView> =
        orchestratorOnly("queryRoute")

    override suspend fun cancelRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<Unit> =
        OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "routing request cancel requires the control-plane Orchestrator",
            ),
        )

    override fun lastPlanningResult(): com.omnillm.runtime.orchestrator.PlanningResult? = null

    override fun lastSubmitResult(): com.omnillm.runtime.orchestrator.SubmitResult? = null

    // ------------------------------------------------------------------

    private fun orchestratorOnly(op: String): OmniResult.Err {
        val err = OmniError.CAPABILITY_UNSUPPORTED(
            message = "$op requires the control-plane Orchestrator (Admin binder is projection-only)",
        )
        synchronized(lock) {
            lastErrorCode = err.code.code
            lastErrorMessage = err.message
        }
        return OmniResult.err(err) as OmniResult.Err
    }

    private fun capabilityState(capability: CapabilityId): CapabilityState {
        val modelRevisionId = firstInstalledModelRevisionId() ?: return CapabilityState.UNKNOWN
        return try {
            when (admin.getInferenceCapabilityState(capability.id, modelRevisionId)?.uppercase()) {
                "SUPPORTED" -> CapabilityState.SUPPORTED
                "CONDITIONAL" -> CapabilityState.CONDITIONAL
                "UNSUPPORTED" -> CapabilityState.UNSUPPORTED
                else -> CapabilityState.UNKNOWN
            }
        } catch (_: RemoteException) {
            CapabilityState.UNKNOWN
        }
    }

    private fun firstInstalledModelRevisionId(): String? =
        try {
            admin.snapshot.models.orEmpty()
                .firstOrNull { m -> m != null && !m.modelRevisionId.isNullOrBlank() }
                ?.modelRevisionId
        } catch (_: RemoteException) {
            null
        }

    private fun preferenceFromSettings(): RoutingPreferenceView? =
        try {
            val values = admin.settings.values.orEmpty()
                .firstOrNull { it?.key == "runtime.fallbackPolicy" && !it.stringValue.isNullOrBlank() }
            val raw = values?.stringValue?.trim()
            val policy = com.omnillm.core.canonical.generated.FallbackPolicy.entries
                .firstOrNull { it.name == raw }
                ?: com.omnillm.core.canonical.generated.FallbackPolicy.NONE
            RoutingPreferenceView(
                fallbackPolicy = policy,
                minimumPlacementClass = MINIMUM_PLACEMENT_CLASS,
            )
        } catch (_: RemoteException) {
            null
        }

    private fun patchFallbackPolicySetting(name: String): OmniResult<Unit> {
        return try {
            val current = admin.settings
            val patch = ai.omnillm.api.OmniSettingsPatch()
            patch.command = OmniCommandRequest().apply {
                commandId = UUID.randomUUID().toString()
                idempotencyKey = "routing-pref-${UUID.randomUUID()}"
                canonicalInputDigest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "runtime.fallbackPolicy=$name",
                )
                hasExpectedVersion = true
                expectedVersion = current.resourceVersion
            }
            patch.changes = arrayOf(
                ai.omnillm.api.OmniSettingEntry().apply {
                    key = "runtime.fallbackPolicy"
                    valueType = "enum"
                    stringValue = name
                },
            )
            val result = admin.applySettings(patch)
            if (result.error != null) {
                OmniResult.err(aidlErrorToDomain(result.error))
            } else {
                OmniResult.ok(Unit)
            }
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "settings patch remote failure"))
        }
    }

    companion object {
        /** Same catalog label the routing screen already renders (engines:api not re-exported). */
        const val MINIMUM_PLACEMENT_CLASS: String = "PRIVILEGED_TRUSTED"
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

// ── Content report ──────────────────────────────────────────────────────────

/**
 * [ContentReportApi] projection over the Admin binder (C-06).
 *
 * Wired through the non-exported review/submit/receipt AIDL surface
 * ([IOmniAdmin.reviewContentReport] → beginLocalReview + ConsentGrant,
 * [IOmniAdmin.submitContentReport], [IOmniAdmin.getContentReportReceipt]).
 * Proposal creation / store listing have no AIDL surface — they fail closed
 * with the reason surfaced so the UI never claims a report that was not
 * durably created.
 */
class AdminContentReportApi(
    private val admin: IOmniAdmin,
) : ContentReportApi {

    private val lastError = ConcurrentHashMap<String, OmniError?>()
    private val knownReports = ConcurrentHashMap<String, ContentReportInfoView>()

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<ContentReportSnapshot> {
        requireLocalUi(principal)
        return OmniResult.ok(
            ContentReportSnapshot(
                reports = knownReports.values.sortedBy { it.reportId },
                activeReview = null,
                lastError = lastError["__snapshot__"],
                endpointConfigured = false,
                networkAvailable = false,
                dataStreamKind = ContentReportPolicy.DATA_STREAM_KIND,
                isTelemetryStream = false,
            ),
        )
    }

    override suspend fun createProposal(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        profileAuthenticated: Boolean,
        accessProfileId: String,
        spec: CreateProposalSpec,
    ): OmniResult<ContentReportInfoView> {
        requireLocalUi(principal)
        return failClosed(
            "report proposal creation requires the control-plane ContentReportStore " +
                "(Admin binder exposes review / submit / receipt only)",
        )
    }

    override suspend fun getReport(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView> {
        requireLocalUi(principal)
        knownReports[reportId]?.let { return OmniResult.ok(it) }
        return failClosed("report store query is control-plane only (not on Admin binder)")
    }

    override suspend fun beginLocalReview(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        spec: BeginReviewSpec,
    ): OmniResult<ContentReportReviewView> =
        failClosed(
            "payload preview requires the control-plane report store; " +
                "Admin binder review issues a ConsentGrant directly",
        )

    override suspend fun grantConsent(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        spec: GrantConsentSpec,
    ): OmniResult<GrantIssueResult> {
        requireLocalUi(principal)
        if (surface != ContentReportPolicy.CallerSurface.LOCAL_TRUSTED_UI) {
            return failClosed("consent grant is LOCAL_TRUSTED_UI only")
        }
        if (spec.command.commandId.isBlank() || spec.command.idempotencyKey.isBlank()) {
            return failClosed("grant command identity required")
        }
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = "a".repeat(64)
            }
            val grant = admin.reviewContentReport(
                spec.reportId,
                spec.canonicalPayloadDigest.lowercase(),
                spec.warningPolicyVersion,
                spec.localUserProfileId,
                cmd,
            )
            val domain = ConsentGrant(
                grantId = grant.grantId.orEmpty().ifBlank { "grant-${UUID.randomUUID()}" },
                principalId = grant.principalId.orEmpty().ifBlank { LocalUiPrincipal.ID.value },
                reportId = grant.reportId.orEmpty().ifBlank { spec.reportId },
                canonicalPayloadDigest = grant.canonicalPayloadDigest.orEmpty()
                    .ifBlank { spec.canonicalPayloadDigest.lowercase() },
                warningPolicyVersion = grant.warningPolicyVersion.orEmpty()
                    .ifBlank { spec.warningPolicyVersion },
                localUserProfileId = grant.localUserProfileId.orEmpty().ifBlank { spec.localUserProfileId },
                issuedAtEpochMs = grant.issuedAtEpochMillis,
                expiresAtEpochMs = grant.expiresAtEpochMillis.takeIf { it > 0L }
                    ?: (System.currentTimeMillis() + ContentReportPolicy.DEFAULT_GRANT_TTL_MS),
                nonce = grant.nonce.orEmpty().ifBlank { UUID.randomUUID().toString() },
            )
            val report = ContentReportInfoView(
                reportId = domain.reportId,
                state = ContentReportState.CONSENT_GRANTED,
                category = null,
                expiresAtEpochMs = domain.expiresAtEpochMs,
                resourceVersion = 1L,
                receiptId = null,
                cancelPending = false,
                hasEncryptedPayload = true,
                error = null,
                actions = ContentReportStateProjection.actionsFor(ContentReportState.CONSENT_GRANTED),
                labelKey = ContentReportStateProjection.labelKey(ContentReportState.CONSENT_GRANTED),
                dataStreamKind = ContentReportPolicy.DATA_STREAM_KIND,
            )
            knownReports[domain.reportId] = report
            lastError.remove("__snapshot__")
            OmniResult.ok(GrantIssueResult(grant = domain, report = report))
        } catch (e: RemoteException) {
            failClosed(e.message ?: "reviewContentReport remote failure")
        }
    }

    override suspend fun submitReport(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        spec: SubmitReportSpec,
    ): OmniResult<SubmitResult> {
        requireLocalUi(principal)
        if (surface != ContentReportPolicy.CallerSurface.LOCAL_TRUSTED_UI) {
            return failClosed("report submit is LOCAL_TRUSTED_UI only")
        }
        return try {
            val cmd = OmniCommandRequest().apply {
                commandId = spec.command.commandId
                idempotencyKey = spec.command.idempotencyKey
                canonicalInputDigest = "a".repeat(64)
            }
            val result = admin.submitContentReport(spec.reportId, spec.consentGrantId, cmd)
            if (result.error != null) {
                val err = aidlErrorToDomain(result.error)
                lastError["__snapshot__"] = err
                return OmniResult.err(err)
            }
            val state = ContentReportState.SUBMITTED
            val report = ContentReportInfoView(
                reportId = spec.reportId,
                state = state,
                category = null,
                expiresAtEpochMs = System.currentTimeMillis() + ContentReportPolicy.DEFAULT_TTL_MS,
                resourceVersion = result.resourceVersion,
                receiptId = null,
                cancelPending = false,
                hasEncryptedPayload = false,
                error = null,
                actions = ContentReportStateProjection.actionsFor(state),
                labelKey = ContentReportStateProjection.labelKey(state),
                dataStreamKind = ContentReportPolicy.DATA_STREAM_KIND,
            )
            knownReports[spec.reportId] = report
            lastError.remove("__snapshot__")
            OmniResult.ok(SubmitResult(report = report, jobId = null, queuedOffline = false))
        } catch (e: RemoteException) {
            failClosed(e.message ?: "submitContentReport remote failure")
        }
    }

    override suspend fun cancelReport(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: CancelReportSpec,
    ): OmniResult<ContentReportInfoView> =
        failClosed("report cancel requires the control-plane ContentReportStore")

    override suspend fun discardReport(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: DiscardReportSpec,
    ): OmniResult<ContentReportInfoView> =
        failClosed("report discard requires the control-plane ContentReportStore")

    override suspend fun getReceipt(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ReceiptView> {
        requireLocalUi(principal)
        return try {
            val receipt = admin.getContentReportReceipt(reportId)
            val domain = ContentReportReceipt(
                receiptId = receipt.receiptId.orEmpty(),
                reportId = receipt.reportId.orEmpty().ifBlank { reportId },
                acceptedAt = receipt.acceptedAtEpochMillis.takeIf { it > 0L }
                    ?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    .orEmpty(),
                statusUrl = receipt.statusUrl.orEmpty(),
            )
            val state = knownReports[reportId]?.state ?: ContentReportState.SUBMITTED
            OmniResult.ok(
                ReceiptView(
                    receipt = domain,
                    reportState = state,
                    isModerationOutcome = ContentReportStateProjection.receiptIsModerationOutcome(),
                ),
            )
        } catch (e: RemoteException) {
            failClosed(e.message ?: "getContentReportReceipt remote failure")
        }
    }

    override suspend fun reconcile(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView> =
        failClosed("reconcile requires the control-plane ContentReportStore")

    override suspend fun retry(
        principal: PrincipalId,
        surface: ContentReportPolicy.CallerSurface,
        reportId: String,
        command: ContentReportCommandIdentity,
    ): OmniResult<SubmitResult> =
        failClosed("retry requires the control-plane ContentReportStore")

    override fun planPayload(spec: CreateProposalSpec): OmniResult<PayloadPlan> {
        val category = ContentReportPolicy.requireKnownCategory(spec.category)
            ?: return OmniResult.err(ContentReportPolicy.unknownCategoryError(spec.category))
        val payload = try {
            ContentReportPayload(
                reportId = spec.reportId,
                category = category,
                createdAt = spec.createdAt,
                appBuild = spec.appBuild,
                modelRevisionId = spec.modelRevisionId.lowercase(),
                engineBuildId = spec.engineBuildId,
                backend = spec.backend,
                localPolicyVersion = spec.localPolicyVersion,
                outputDigest = spec.outputDigest.lowercase(),
                userLocale = spec.userLocale,
                description = spec.description,
                promptExcerpt = spec.promptExcerpt,
                outputExcerpt = spec.outputExcerpt,
                diagnosticSummary = spec.diagnosticSummary,
            )
        } catch (ex: IllegalArgumentException) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = ex.message ?: "invalid content report payload fields"),
            )
        }
        if (ContentReportPolicy.payloadContainsForbiddenSecretMaterial(payload)) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "payload must not embed long-lived secrets or pairing material",
                ),
            )
        }
        return OmniResult.ok(
            PayloadPlan(
                payload = payload,
                digestHex = payload.canonicalDigestHex(),
                fields = payload.previewFields(),
            ),
        )
    }

    override suspend fun onRemoteAccepted(
        reportId: String,
        receiptId: String,
        acceptedAt: String,
        statusUrl: String,
    ): OmniResult<ContentReportInfoView> =
        failClosed("remote-accept callbacks are control-plane only")

    override suspend fun onRemoteRetryableFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView> =
        failClosed("remote-failure callbacks are control-plane only")

    override suspend fun onRemoteFinalFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView> =
        failClosed("remote-failure callbacks are control-plane only")

    override suspend fun expireDue(nowMs: Long): Int = 0

    private fun failClosed(message: String): OmniResult.Err {
        val err = OmniError.CAPABILITY_UNSUPPORTED(message = message)
        lastError["__snapshot__"] = err
        return OmniResult.err(err) as OmniResult.Err
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────────

private fun requireLocalUi(principal: PrincipalId) {
    require(principal.value == LocalUiPrincipal.ID.value) {
        "Admin projections accept LOCAL_UI principal only (got ${principal.value})"
    }
}

private fun aidlErrorToDomain(err: ai.omnillm.api.OmniError): OmniError {
    val code = com.omnillm.core.errors.generated.OmniErrorCode.fromCode(err.code.orEmpty())
        ?: com.omnillm.core.errors.generated.OmniErrorCode.INTERNAL
    return OmniError.of(code, err.message ?: code.code)
}
