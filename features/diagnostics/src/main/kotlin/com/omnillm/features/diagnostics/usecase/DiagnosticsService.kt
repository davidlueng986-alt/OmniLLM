package com.omnillm.features.diagnostics.usecase

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.diagnostics.DiagnosticsModule
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
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.features.diagnostics.domain.ExportCategoryPreview
import com.omnillm.features.diagnostics.export.DiagnosticBundleBuilder
import com.omnillm.features.diagnostics.ports.CapabilityAvailabilityPort
import com.omnillm.features.diagnostics.ports.DiagnosticSourcePort
import com.omnillm.features.diagnostics.ports.parseExportCategory
import com.omnillm.features.diagnostics.projection.DiagnosticStateProjection
import com.omnillm.features.diagnostics.projection.MetricEvidenceProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.observability.ObservabilityFacade
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * FEAT-DIAGNOSTICS control-plane use-case facade.
 *
 * - Redacted export via [DiagnosticBundleBuilder] + allowlist
 * - Job claim-or-return for DIAGNOSTIC_EXPORT (ADR-004/005)
 * - Capability negotiation fail-closed (INV-018)
 * - Client-generated jobId / bundleId / requestId / idempotencyKey
 * - No direct DB writes (ADR-010 / INV-001)
 */
class DiagnosticsService(
    private val jobManager: JobManager,
    private val observability: ObservabilityFacade,
    private val sources: DiagnosticSourcePort,
    private val capabilityAvailability: CapabilityAvailabilityPort,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : DiagnosticsApi {

    private val snapshotSeq = AtomicLong(0L)
    private val lock = Any()

    /** bundleId → snapshot */
    private val bundles = linkedMapOf<String, DiagnosticBundleSnapshot>()

    /** jobId → bundleId */
    private val jobToBundle = linkedMapOf<String, String>()

    private var lastPlan: DiagnosticExportPlan? = null
    private var lastError: OmniError? = null
    private var activeJobId: String? = null

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<DiagnosticsSnapshot> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()
        return OmniResult.ok(buildSnapshotLocked())
    }

    override fun planExport(
        principal: PrincipalId,
        includeDetail: Boolean,
        selectedCategories: List<String>,
    ): OmniResult<DiagnosticExportPlan> {
        requireLocalUi(principal)
        if (!allowsExport()) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "diagnostics.export scope required",
                    details = mapOf("scope" to AccessScope.diagnostics_export.id),
                ),
            )
        }

        val cap = negotiateRequired()
        if (cap != null) return OmniResult.err(cap)

        val selected = resolveCategories(selectedCategories)
        if (selected is OmniResult.Err) return selected

        val cats = (selected as OmniResult.Ok).value
        val previews = cats.map { category ->
            ExportCategoryPreview(
                category = category,
                sensitivity = CategorySensitivity.forCategory(category),
                estimatedBytes = DiagnosticBundleBuilder.estimateCategoryBytes(category, includeDetail),
                includedByDefault = category in DEFAULT_CATEGORIES,
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
        if (!allowsExport()) return forbiddenExport()

        val cap = negotiateRequired()
        if (cap != null) {
            lastError = cap
            return OmniResult.err(cap)
        }

        val categoriesResult = resolveCategories(spec.categories)
        if (categoriesResult is OmniResult.Err) {
            lastError = categoriesResult.error
            return categoriesResult
        }
        val categories = (categoriesResult as OmniResult.Ok).value

        val jobId = try {
            JobId(spec.jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }

        val identity = JobIdentity(
            jobId = jobId,
            principalId = principal,
            kind = JobKind.DIAGNOSTIC_EXPORT,
            idempotencyKey = IdempotencyKey.parse(spec.command.idempotencyKey),
            canonicalSpecDigest = spec.command.canonicalInputDigest.lowercase(),
        )
        val params = JobParameters.DiagnosticExport(
            includeDetail = spec.includeDetail,
            categories = categories.map { it.name },
            ttlSeconds = spec.ttlSeconds,
        )

        val claim = when (val created = jobManager.create(identity, params)) {
            is OmniResult.Ok -> created.value
            is OmniResult.Err -> {
                lastError = created.error
                return created
            }
        }

        synchronized(lock) {
            jobToBundle[spec.jobId] = spec.bundleId
            activeJobId = spec.jobId
            lastError = null
            // Draft placeholder — never READY until seal.
            if (claim.createdNew || !bundles.containsKey(spec.bundleId)) {
                val now = clockMs()
                val ttl = spec.ttlSeconds ?: DEFAULT_TTL_SECONDS
                bundles[spec.bundleId] = DiagnosticBundleSnapshot(
                    bundleId = spec.bundleId,
                    jobId = spec.jobId,
                    ownerPrincipalClass = principal.value,
                    state = DiagnosticBundleStates.COLLECTING,
                    schemaVersion = DiagnosticAllowlist.SCHEMA_VERSION,
                    createdAtEpochMs = now,
                    expiresAtEpochMs = now + ttl * 1000L,
                    categoriesIncluded = categories.map { it.name },
                    files = emptyList(),
                    manifestDigest = null,
                    reason = null,
                    estimatedBytes = categories.sumOf {
                        DiagnosticBundleBuilder.estimateCategoryBytes(it, spec.includeDetail)
                    },
                    shareIrreversibleDisclosed = spec.shareIrreversibleAcknowledged,
                )
            }
        }

        if (claim.createdNew) {
            jobManager.start(jobId)
            jobManager.updateProgress(
                jobId,
                JobProgress(currentPhase = "COLLECTING"),
            )
        }

        val record = jobManager.query(jobId).getOrNull() ?: claim.record
        return OmniResult.ok(
            DiagnosticStateProjection.projectJobHandle(
                record = record,
                bundleId = spec.bundleId,
                createdNew = claim.createdNew,
            ),
        )
    }

    override suspend fun cancelExport(
        principal: PrincipalId,
        spec: CancelExportSpec,
    ): OmniResult<DiagnosticJobHandle> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()

        val jobId = try {
            JobId(spec.jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }

        val cancelled = when (val c = jobManager.cancel(jobId, requestOnly = spec.requestOnly)) {
            is OmniResult.Ok -> c.value
            is OmniResult.Err -> {
                lastError = c.error
                return c
            }
        }

        synchronized(lock) {
            val bundleId = jobToBundle[spec.jobId]
            if (bundleId != null && !spec.requestOnly) {
                val existing = bundles[bundleId]
                if (existing != null && !DiagnosticBundleStates.isTerminal(existing.state)) {
                    // Never mark partial as READY.
                    bundles[bundleId] = existing.copy(
                        state = DiagnosticBundleStates.CANCELLED,
                        files = emptyList(),
                        manifestDigest = null,
                        error = OmniError.CANCELLED(message = "diagnostic export cancelled"),
                    )
                }
            }
            if (!spec.requestOnly && activeJobId == spec.jobId) {
                activeJobId = null
            }
            lastError = null
        }

        return OmniResult.ok(
            DiagnosticStateProjection.projectJobHandle(
                record = cancelled,
                bundleId = jobToBundle[spec.jobId],
                createdNew = false,
            ),
        )
    }

    override suspend fun queryExportJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<DiagnosticJobHandle> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()
        val id = try {
            JobId(jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }
        return when (val q = jobManager.query(id)) {
            is OmniResult.Ok -> OmniResult.ok(
                DiagnosticStateProjection.projectJobHandle(
                    record = q.value,
                    bundleId = jobToBundle[jobId],
                ),
            )
            is OmniResult.Err -> q
        }
    }

    override suspend fun getBundle(
        principal: PrincipalId,
        bundleId: String,
    ): OmniResult<DiagnosticBundleSnapshot> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()
        val bundle = synchronized(lock) { bundles[bundleId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "diagnostic bundle not found",
                    details = mapOf("bundleId" to bundleId),
                ),
            )
        return OmniResult.ok(maybeExpire(bundle))
    }

    override suspend fun listBundles(
        principal: PrincipalId,
    ): OmniResult<List<DiagnosticBundleSnapshot>> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()
        val list = synchronized(lock) {
            bundles.values
                .map { maybeExpire(it) }
                .filter { it.state != DiagnosticBundleStates.DELETED }
                .toList()
        }
        return OmniResult.ok(list)
    }

    override suspend fun collectAndSeal(
        jobId: String,
        forceFailClosed: Boolean,
    ): OmniResult<DiagnosticBundleSnapshot> {
        val id = try {
            JobId(jobId)
        } catch (_: IllegalArgumentException) {
            return invalidId("jobId")
        }
        val record = when (val q = jobManager.query(id)) {
            is OmniResult.Ok -> q.value
            is OmniResult.Err -> return q
        }
        if (record.kind != JobKind.DIAGNOSTIC_EXPORT) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "job is not DIAGNOSTIC_EXPORT",
                    details = mapOf("kind" to record.kind.name),
                ),
            )
        }
        if (record.state == "CANCELLED") {
            return OmniResult.err(
                OmniError.CANCELLED(message = "export cancelled before seal"),
            )
        }
        if (record.isTerminal && record.state != "SUCCEEDED") {
            return OmniResult.err(
                record.error
                    ?: OmniError.STATE_CONFLICT(message = "job terminal: ${record.state}"),
            )
        }
        if (record.cancelRequested) {
            return OmniResult.err(
                OmniError.CANCELLED(message = "cancel requested; refusing seal"),
            )
        }

        val params = record.parameters as? JobParameters.DiagnosticExport
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "missing DiagnosticExport parameters"),
            )

        val categoriesResult = resolveCategories(params.categories)
        if (categoriesResult is OmniResult.Err) {
            jobManager.fail(id, categoriesResult.error)
            return categoriesResult
        }
        val categories = (categoriesResult as OmniResult.Ok).value

        if (forceFailClosed) {
            val err = OmniError.INTERNAL(
                message = "diagnostic collection failed closed",
            )
            jobManager.fail(id, err)
            synchronized(lock) {
                val bid = jobToBundle[jobId]
                if (bid != null) {
                    bundles[bid]?.let { b ->
                        bundles[bid] = b.copy(
                            state = DiagnosticBundleStates.FAILED,
                            files = emptyList(),
                            manifestDigest = null,
                            error = err,
                        )
                    }
                }
            }
            return OmniResult.err(err)
        }

        // Intermediate progress — still not READY.
        jobManager.updateProgress(id, JobProgress(currentPhase = "SEALING"))
        val bundleId = synchronized(lock) {
            jobToBundle[jobId]
                ?: return@synchronized null
        } ?: return OmniResult.err(
            OmniError.NOT_FOUND(message = "no bundle linked to job", details = mapOf("jobId" to jobId)),
        )

        val existing = synchronized(lock) { bundles[bundleId] }
        val now = clockMs()
        val sealed = DiagnosticBundleBuilder.build(
            bundleId = bundleId,
            jobId = jobId,
            ownerPrincipalClass = existing?.ownerPrincipalClass ?: LocalUiPrincipal.ID.value,
            categories = categories,
            sources = sources,
            includeDetail = params.includeDetail,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            ttlSeconds = params.ttlSeconds ?: DEFAULT_TTL_SECONDS,
            seal = true,
            shareIrreversibleDisclosed = existing?.shareIrreversibleDisclosed ?: false,
        )

        // Complete job only after seal so partial never becomes READY with SUCCEEDED.
        when (record.state) {
            "QUEUED" -> {
                when (val started = jobManager.start(id)) {
                    is OmniResult.Err -> return started
                    is OmniResult.Ok -> Unit
                }
                when (val done = jobManager.succeed(id)) {
                    is OmniResult.Err -> return done
                    is OmniResult.Ok -> Unit
                }
            }
            "RUNNING" -> {
                when (val done = jobManager.succeed(id)) {
                    is OmniResult.Err -> return done
                    is OmniResult.Ok -> Unit
                }
            }
            "SUCCEEDED" -> Unit
            else -> {
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "cannot seal from job state ${record.state}",
                        details = mapOf("state" to record.state),
                    ),
                )
            }
        }

        synchronized(lock) {
            bundles[bundleId] = sealed
            lastError = null
            if (activeJobId == jobId) activeJobId = null
        }
        return OmniResult.ok(sealed)
    }

    override suspend fun deleteBundle(
        principal: PrincipalId,
        spec: DeleteBundleSpec,
    ): OmniResult<DiagnosticBundleSnapshot> {
        requireLocalUi(principal)
        if (!allowsExport()) return forbiddenExport()
        val existing = synchronized(lock) { bundles[spec.bundleId] }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "diagnostic bundle not found",
                    details = mapOf("bundleId" to spec.bundleId),
                ),
            )
        // FEAT-DIAGNOSTICS §4: drain share stream first — no active share in this module.
        val deleted = existing.copy(
            state = DiagnosticBundleStates.DELETED,
            files = emptyList(),
            manifestDigest = null,
        )
        synchronized(lock) {
            bundles[spec.bundleId] = deleted
        }
        return OmniResult.ok(deleted)
    }

    override fun newClientInferenceIdentity(idempotencyKey: String?): ClientInferenceIdentity {
        val requestId = RequestId.parse(UUID.randomUUID().toString()).value
        val key = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: "diag-inf-${UUID.randomUUID()}"
        // Validate via IdempotencyKey parse (max 128 bytes).
        IdempotencyKey.parse(key)
        return ClientInferenceIdentity(
            requestId = requestId,
            idempotencyKey = key,
        )
    }

    override fun listEvidencedMetrics(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> {
        requireLocalUi(principal)
        val fromSources = sources.metricSamples()
        val fromFacade = observability.metricSummary().samples
        val merged = (fromSources + fromFacade).distinctBy { it.name to it.sampledAtEpochMs }
        return OmniResult.ok(MetricEvidenceProjection.projectAll(merged))
    }

    override fun exportRedactionAllowlist(
        principal: PrincipalId,
    ): OmniResult<RedactionAllowlistExport> {
        requireLocalUi(principal)
        if (!allowsExport()) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "diagnostics.export scope required",
                    details = mapOf("scope" to AccessScope.diagnostics_export.id),
                ),
            )
        }
        return OmniResult.ok(buildRedactionAllowlistExport())
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    internal fun buildRedactionAllowlistExport(): RedactionAllowlistExport {
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

    private fun buildSnapshotLocked(): DiagnosticsSnapshot {
        val job: JobRecord?
        val bundleId: String?
        val plan: DiagnosticExportPlan?
        val error: OmniError?
        val bundleList: List<DiagnosticBundleSnapshot>
        synchronized(lock) {
            val jid = activeJobId
            job = jid?.let { jobManager.query(JobId(it)).getOrNull() }
            bundleId = jid?.let { jobToBundle[it] }
            plan = lastPlan
            error = lastError
            bundleList = bundles.values.map { maybeExpire(it) }.toList()
        }
        val health = sources.serviceHealth()
        val metrics = MetricEvidenceProjection.projectAll(
            sources.metricSamples() + observability.metricSummary().samples,
        )
        return DiagnosticStateProjection.projectSnapshot(
            snapshotVersion = snapshotSeq.incrementAndGet(),
            bundles = bundleList,
            activeJob = job,
            bundleIdForJob = bundleId,
            plan = plan,
            metrics = metrics,
            health = health,
            reason = bundleList.lastOrNull { it.reason != null }?.reason,
            lastError = error,
            hasPreview = plan != null,
            redactionAllowlist = buildRedactionAllowlistExport(),
        )
    }

    private fun maybeExpire(bundle: DiagnosticBundleSnapshot): DiagnosticBundleSnapshot {
        val now = clockMs()
        if (bundle.state == DiagnosticBundleStates.READY && now >= bundle.expiresAtEpochMs) {
            val expired = bundle.copy(
                state = DiagnosticBundleStates.EXPIRED,
                files = emptyList(),
                manifestDigest = null,
            )
            synchronized(lock) {
                bundles[bundle.bundleId] = expired
            }
            return expired
        }
        return bundle
    }

    /**
     * Required capabilities for FEAT-DIAGNOSTICS. UNSUPPORTED ⇒ CAPABILITY_UNSUPPORTED;
     * UNKNOWN / TEMPORARILY_UNAVAILABLE ⇒ CAPABILITY_UNKNOWN (fail closed, INV-018).
     */
    private fun negotiateRequired(): OmniError? {
        for (cap in DiagnosticsModule.REQUIRED_CAPABILITIES) {
            when (val state = capabilityAvailability.resolve(cap)) {
                CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
                CapabilityState.UNSUPPORTED -> {
                    return OmniError.CAPABILITY_UNSUPPORTED(
                        message = "required capability unsupported",
                        details = mapOf(
                            "capabilityId" to cap.id,
                            "capabilityState" to state.name,
                        ),
                    )
                }
                CapabilityState.UNKNOWN, CapabilityState.TEMPORARILY_UNAVAILABLE -> {
                    return OmniError.CAPABILITY_UNKNOWN(
                        message = "required capability not available",
                        details = mapOf(
                            "capabilityId" to cap.id,
                            "capabilityState" to state.name,
                        ),
                    )
                }
            }
        }
        return null
    }

    private fun resolveCategories(
        names: List<String>,
    ): OmniResult<List<DiagnosticAllowlist.Category>> {
        if (names.isEmpty()) {
            return OmniResult.ok(DEFAULT_CATEGORIES.toList())
        }
        val out = ArrayList<DiagnosticAllowlist.Category>(names.size)
        for (name in names) {
            val cat = parseExportCategory(name)
                ?: return OmniResult.err(
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "unknown diagnostic export category",
                        details = mapOf("category" to name),
                    ),
                )
            out += cat
        }
        return OmniResult.ok(out.distinct())
    }

    private fun allowsExport(): Boolean =
        LocalUiPrincipal.allows(AccessScope.diagnostics_export)

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "DiagnosticsApi accepts LOCAL_UI principal only (INV-001)"
        }
    }

    private fun forbiddenExport(): OmniResult.Err =
        OmniResult.err(
            OmniError.FORBIDDEN(
                message = "diagnostics.export scope required",
                details = mapOf("scope" to AccessScope.diagnostics_export.id),
            ),
        ) as OmniResult.Err

    private fun invalidId(field: String): OmniResult.Err =
        OmniResult.err(
            OmniError.INVALID_REQUEST(
                message = "invalid $field",
                details = mapOf("field" to field),
            ),
        ) as OmniResult.Err

    companion object {
        const val DEFAULT_TTL_SECONDS: Int = 86_400

        val DEFAULT_CATEGORIES: Set<DiagnosticAllowlist.Category> = setOf(
            DiagnosticAllowlist.Category.MANIFEST,
            DiagnosticAllowlist.Category.RUNTIME_VERSIONS,
            DiagnosticAllowlist.Category.CONFIGURATION,
            DiagnosticAllowlist.Category.CAPABILITY_SNAPSHOT,
            DiagnosticAllowlist.Category.MODEL_ENGINE_IDS,
            DiagnosticAllowlist.Category.REQUEST_JOB_STATE,
            DiagnosticAllowlist.Category.RESOURCE_SNAPSHOT,
            DiagnosticAllowlist.Category.ERROR_CHAIN,
            DiagnosticAllowlist.Category.EVIDENCE_LABELS,
            DiagnosticAllowlist.Category.REPRODUCTION_HINTS,
            DiagnosticAllowlist.Category.FILE_DIGESTS,
            DiagnosticAllowlist.Category.INTEGRITY_RESULTS,
        )
    }
}
