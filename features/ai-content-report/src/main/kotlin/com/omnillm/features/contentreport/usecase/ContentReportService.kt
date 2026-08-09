package com.omnillm.features.contentreport.usecase

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.ContentReportCategory
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.contentreport.ContentReportModule
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
import com.omnillm.features.contentreport.domain.ConsentGrantState
import com.omnillm.features.contentreport.domain.ContentReportPayload
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.domain.ContentReportRecord
import com.omnillm.features.contentreport.ports.ContentReportFeaturePorts
import com.omnillm.features.contentreport.projection.ContentReportStateProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import java.util.UUID

/**
 * FEAT-AI-CONTENT-REPORT control-plane use-case facade.
 *
 * - CONTENT_REPORT FSM via [ContentReportFsm] (catalog edges only)
 * - ConsentGrant one-time, principal/report/digest-bound (CR-N001..N006)
 * - Atomic grant consume + freeze + queue/submit (RPT-003/004)
 * - Reply-loss reconcile by reportId (ADR-004/005)
 * - Report stream ≠ telemetry
 * - No silent cross-revision rewrite
 * - No domain DB writes from this pack (ADR-010) — store port is control-plane
 */
class ContentReportService(
    private val ports: ContentReportFeaturePorts,
) : ContentReportApi {

    private val store get() = ports.store
    private val endpoint get() = ports.endpoint
    private val clockMs get() = ports.clockMs
    private val lock = Any()

    @Volatile
    private var lastError: OmniError? = null

    @Volatile
    private var activeReviewReportId: String? = null

    override suspend fun getSnapshot(principal: PrincipalId): OmniResult<ContentReportSnapshot> {
        val reports = store.listByPrincipal(principal.value).map {
            ContentReportStateProjection.toInfoView(maybeExpire(it))
        }
        val review = activeReviewReportId?.let { id ->
            store.getReport(id)?.takeIf { it.principalId == principal.value }?.let { buildReview(it) }
        }
        return OmniResult.ok(
            ContentReportSnapshot(
                reports = reports,
                activeReview = review,
                lastError = lastError,
                endpointConfigured = ports.endpointConfig.isConfigured(),
                networkAvailable = endpoint.isNetworkAvailable() && endpoint.isAvailable(),
                dataStreamKind = ContentReportPolicy.DATA_STREAM_KIND,
                isTelemetryStream = false,
            ),
        )
    }

    override fun planPayload(spec: CreateProposalSpec): OmniResult<PayloadPlan> {
        val category = ContentReportPolicy.requireKnownCategory(spec.category)
            ?: return OmniResult.err(ContentReportPolicy.unknownCategoryError(spec.category))
        val built = try {
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
                OmniError.INVALID_REQUEST(
                    message = ex.message ?: "invalid content report payload fields",
                ),
            )
        }
        if (ContentReportPolicy.payloadContainsForbiddenSecretMaterial(built)) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "payload must not embed long-lived secrets or pairing material",
                ),
            )
        }
        return OmniResult.ok(
            PayloadPlan(
                payload = built,
                digestHex = built.canonicalDigestHex(),
                fields = built.previewFields(),
            ),
        )
    }

    override suspend fun createProposal(
        principal: PrincipalId,
        surface: CallerSurface,
        profileAuthenticated: Boolean,
        accessProfileId: String,
        spec: CreateProposalSpec,
    ): OmniResult<ContentReportInfoView> {
        ContentReportPolicy.requireAuthenticatedLan(surface, profileAuthenticated)?.let {
            lastError = it
            return OmniResult.err(it)
        }
        ContentReportPolicy.rejectExternalUserConfirmed(surface, spec.userConfirmed)?.let {
            lastError = it
            return OmniResult.err(it)
        }

        val profile = AccessProfile.fromId(accessProfileId)
            ?: return forbidden("unknown access profile (fail closed)", "profile" to accessProfileId)
        if (!ContentReportPolicy.mayPropose(profile) &&
            surface != CallerSurface.LOCAL_TRUSTED_UI
        ) {
            return forbidden(
                "content-reports.propose required",
                "scope" to AccessScope.content_reports_propose.id,
            )
        }

        val cap = negotiateRequired()
        if (cap != null) {
            lastError = cap
            return OmniResult.err(cap)
        }

        val existing = store.findByPrincipalAndIdempotency(
            principal.value,
            spec.command.idempotencyKey,
        )
        if (existing != null) {
            if (existing.reportId != spec.reportId) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(
                        message = "idempotency key already bound to another reportId",
                        details = mapOf(
                            "existingReportId" to existing.reportId,
                            "requestedReportId" to spec.reportId,
                        ),
                    ),
                )
            }
            return OmniResult.ok(ContentReportStateProjection.toInfoView(existing))
        }

        val byId = store.getReport(spec.reportId)
        if (byId != null) {
            return OmniResult.ok(ContentReportStateProjection.toInfoView(byId))
        }

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
                OmniError.INVALID_REQUEST(
                    message = ex.message ?: "invalid content report proposal fields",
                    details = mapOf("category" to category.name),
                ),
            )
        }
        if (ContentReportPolicy.payloadContainsForbiddenSecretMaterial(payload)) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "payload must not embed long-lived secrets or pairing material",
                ),
            )
        }

        val now = clockMs()
        val record = ContentReportRecord(
            reportId = spec.reportId,
            principalId = principal.value,
            proposalCommandId = spec.command.commandId,
            idempotencyKey = spec.command.idempotencyKey,
            state = ContentReportState.DRAFT,
            category = payload.category,
            payload = payload,
            canonicalPayloadDigest = null,
            frozenPayload = null,
            activeGrantId = null,
            cancelPending = false,
            expiresAtEpochMs = now + ContentReportPolicy.DEFAULT_TTL_MS,
            resourceVersion = 0L,
            receiptId = null,
            receiptAcceptedAt = null,
            receiptStatusUrl = null,
            error = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            hasEncryptedPayload = true,
        )
        // COR-23g: atomic claim-or-insert keyed by reportId — concurrent
        // proposals for the same reportId resolve to exactly one winner; the
        // loser reconciles against the durable record (reply-loss) instead of
        // last-writer-wins overwriting. Serialized in-process too (the durable
        // store adds its own transactional check-then-insert).
        val claimed = synchronized(lock) {
            store.putReportIfAbsent(record)
        }
        if (!claimed) {
            val winner = store.getReport(spec.reportId)
                ?: return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "concurrent proposal for reportId lost the claim race",
                        details = mapOf("reportId" to spec.reportId),
                    ),
                )
            return OmniResult.ok(ContentReportStateProjection.toInfoView(winner))
        }
        lastError = null
        return OmniResult.ok(ContentReportStateProjection.toInfoView(record))
    }

    override suspend fun getReport(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to reportId)
        }
        return OmniResult.ok(ContentReportStateProjection.toInfoView(maybeExpire(record)))
    }

    override suspend fun beginLocalReview(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: BeginReviewSpec,
    ): OmniResult<ContentReportReviewView> {
        denyUnlessReviewSubmit(principal, surface)?.let {
            lastError = it
            return OmniResult.err(it)
        }
        val record = store.getReport(spec.reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to spec.reportId)
        }

        val next = applyEvent(
            record = record,
            event = "BEGIN_LOCAL_REVIEW",
            guards = GuardEvaluator.of("localTrustedReview" to true),
        ) ?: return stateConflict(record, "BEGIN_LOCAL_REVIEW")

        val updated = next.copy(
            // Payload stays for preview; digest computed at grant time.
            updatedAtEpochMs = clockMs(),
            resourceVersion = next.resourceVersion + 1,
        )
        store.putReport(updated)
        activeReviewReportId = updated.reportId
        lastError = null
        return OmniResult.ok(buildReview(updated))
    }

    override suspend fun grantConsent(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: GrantConsentSpec,
    ): OmniResult<GrantIssueResult> {
        denyUnlessReviewSubmit(principal, surface)?.let {
            lastError = it
            return OmniResult.err(it)
        }
        val record = store.getReport(spec.reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))

        val basePayload = record.payload
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "no payload available for review"),
            )

        // Optional user-selected excerpts only when explicitly provided.
        val reviewed = basePayload.copy(
            description = spec.includeDescription ?: basePayload.description,
            promptExcerpt = spec.includePromptExcerpt ?: basePayload.promptExcerpt,
            outputExcerpt = spec.includeOutputExcerpt ?: basePayload.outputExcerpt,
            diagnosticSummary = spec.includeDiagnosticSummary ?: basePayload.diagnosticSummary,
        )
        ContentReportPolicy.revisionBindingConflict(basePayload, reviewed)?.let {
            lastError = it
            return OmniResult.err(it)
        }

        val digest = reviewed.canonicalDigestHex()
        // CR-N003: digest substitution after review fails closed.
        if (digest != spec.canonicalPayloadDigest.lowercase()) {
            val err = OmniError.STATE_CONFLICT(
                message = "canonicalPayloadDigest mismatch (fail closed)",
                details = mapOf(
                    "fixture" to "CR-N003",
                    "expected" to digest,
                    "provided" to spec.canonicalPayloadDigest.lowercase(),
                ),
            )
            lastError = err
            return OmniResult.err(err)
        }

        val next = applyEvent(
            record = record.copy(payload = reviewed),
            event = "GRANT_CONSENT",
            guards = GuardEvaluator.of(
                "localTrustedReview" to true,
                "payloadMinimized" to true,
            ),
        ) ?: return stateConflict(record, "GRANT_CONSENT")

        val now = clockMs()
        val grant = ConsentGrant(
            grantId = UUID.randomUUID().toString(),
            principalId = principal.value,
            reportId = record.reportId,
            canonicalPayloadDigest = digest,
            warningPolicyVersion = spec.warningPolicyVersion,
            localUserProfileId = spec.localUserProfileId,
            issuedAtEpochMs = now,
            expiresAtEpochMs = now + ContentReportPolicy.DEFAULT_GRANT_TTL_MS,
            nonce = UUID.randomUUID().toString(),
            state = ConsentGrantState.ISSUED,
        )
        store.putGrant(grant)

        val updated = next.copy(
            payload = reviewed,
            canonicalPayloadDigest = digest,
            activeGrantId = grant.grantId,
            updatedAtEpochMs = now,
            resourceVersion = next.resourceVersion + 1,
            error = null,
        )
        store.putReport(updated)
        lastError = null
        return OmniResult.ok(
            GrantIssueResult(
                grant = grant,
                report = ContentReportStateProjection.toInfoView(updated),
            ),
        )
    }

    override suspend fun submitReport(
        principal: PrincipalId,
        surface: CallerSurface,
        spec: SubmitReportSpec,
    ): OmniResult<SubmitResult> {
        denyUnlessReviewSubmit(principal, surface)?.let {
            lastError = it
            return OmniResult.err(it)
        }
        val record = store.getReport(spec.reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))

        val grant = store.getGrant(spec.consentGrantId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "consent grant not found",
                    details = mapOf("grantId" to spec.consentGrantId),
                ),
            )

        validateGrantForSubmit(principal, record, grant)?.let {
            lastError = it
            return OmniResult.err(it)
        }

        val now = clockMs()
        val withinTtl = now < record.expiresAtEpochMs && grant.isConsumable(now)
        if (!withinTtl) {
            // CR-N005 / RPT-020 path
            if (grant.isExpired(now) || grant.state == ConsentGrantState.ISSUED && now >= grant.expiresAtEpochMs) {
                expireGrant(grant, now)
            }
            val err = OmniError.STATE_CONFLICT(
                message = "consent grant expired or report TTL exceeded",
                details = mapOf("fixture" to "CR-N005"),
            )
            lastError = err
            return OmniResult.err(err)
        }

        val online = spec.preferOnline &&
            endpoint.isNetworkAvailable() &&
            endpoint.isAvailable() &&
            ports.endpointConfig.isConfigured()

        val event = if (online) "SUBMIT_ONLINE" else "QUEUE_OFFLINE"
        val nextState = applyEvent(
            record = record,
            event = event,
            guards = GuardEvaluator.of(
                "consentGrantValidAndUnconsumed" to true,
                "networkAndEndpointAvailable" to online,
                "withinTtl" to true,
            ),
        ) ?: return stateConflict(record, event)

        // Atomic: consume grant + freeze payload.
        val consumed = grant.copy(
            state = ConsentGrantState.CONSUMED,
            consumedAtEpochMs = now,
        )
        store.putGrant(consumed)

        val frozen = record.payload
            ?: return OmniResult.err(OmniError.STATE_CONFLICT(message = "missing payload to freeze"))
        val digest = record.canonicalPayloadDigest ?: frozen.canonicalDigestHex()

        var updated = nextState.copy(
            frozenPayload = frozen,
            payload = frozen,
            canonicalPayloadDigest = digest,
            activeGrantId = grant.grantId,
            hasEncryptedPayload = true,
            updatedAtEpochMs = now,
            resourceVersion = nextState.resourceVersion + 1,
            error = null,
        )
        store.putReport(updated)

        val jobId = claimSubmitJob(principal, updated, spec.command)

        if (online && updated.state == ContentReportState.SUBMITTING) {
            updated = performSubmit(updated)
        }

        lastError = updated.error
        return OmniResult.ok(
            SubmitResult(
                report = ContentReportStateProjection.toInfoView(updated),
                jobId = jobId,
                queuedOffline = updated.state == ContentReportState.QUEUED_OFFLINE,
            ),
        )
    }

    override suspend fun cancelReport(
        principal: PrincipalId,
        surface: CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: CancelReportSpec,
    ): OmniResult<ContentReportInfoView> {
        ContentReportPolicy.requireAuthenticatedLan(surface, authenticated)?.let {
            return OmniResult.err(it)
        }
        val profile = AccessProfile.fromId(accessProfileId)
            ?: return forbidden("unknown access profile", "profile" to accessProfileId)
        if (!ContentReportPolicy.mayManageOwn(profile) &&
            surface != CallerSurface.LOCAL_TRUSTED_UI
        ) {
            return forbidden(
                "content-reports.manage-own required",
                "scope" to AccessScope.content_reports_manage_own.id,
            )
        }

        val record = store.getReport(spec.reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to spec.reportId)
        }

        return when (record.state) {
            ContentReportState.SUBMITTING -> cancelWhileSubmitting(record)
            ContentReportState.QUEUED_OFFLINE,
            ContentReportState.FAILED_RETRYABLE,
            ContentReportState.DRAFT,
            ContentReportState.REVIEWING,
            ContentReportState.CONSENT_GRANTED,
            -> discardInternal(record)
            ContentReportState.CANCELLING,
            ContentReportState.RECONCILING,
            -> reconcileInternal(record)
            else -> OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "cancel not applicable in state ${record.state.name}",
                    details = mapOf("state" to record.state.name),
                ),
            )
        }
    }

    override suspend fun discardReport(
        principal: PrincipalId,
        surface: CallerSurface,
        accessProfileId: String,
        authenticated: Boolean,
        spec: DiscardReportSpec,
    ): OmniResult<ContentReportInfoView> {
        ContentReportPolicy.requireAuthenticatedLan(surface, authenticated)?.let {
            return OmniResult.err(it)
        }
        val profile = AccessProfile.fromId(accessProfileId)
            ?: return forbidden("unknown access profile", "profile" to accessProfileId)
        if (!ContentReportPolicy.mayManageOwn(profile) &&
            surface != CallerSurface.LOCAL_TRUSTED_UI
        ) {
            return forbidden(
                "content-reports.manage-own required",
                "scope" to AccessScope.content_reports_manage_own.id,
            )
        }

        val record = store.getReport(spec.reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to spec.reportId)
        }

        if (ContentReportPolicy.discardDisguisesSubmitted(record.state)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "cannot discard already submitted report as unsent",
                    details = mapOf("state" to record.state.name),
                ),
            )
        }
        if (!ContentReportPolicy.mayDiscard(record.state)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "discard not allowed; reconcile remote outcome first",
                    details = mapOf("state" to record.state.name),
                ),
            )
        }
        return discardInternal(record)
    }

    override suspend fun getReceipt(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ReceiptView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to reportId)
        }
        val receipt = store.getReceipt(reportId)
            ?: record.receiptId?.let {
                ContentReportReceipt(
                    receiptId = it,
                    reportId = reportId,
                    acceptedAt = record.receiptAcceptedAt ?: "",
                    statusUrl = record.receiptStatusUrl ?: "",
                )
            }
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "receipt not found"))
        return OmniResult.ok(
            ReceiptView(
                receipt = receipt,
                reportState = record.state,
                isModerationOutcome = ContentReportStateProjection.receiptIsModerationOutcome(),
            ),
        )
    }

    override suspend fun reconcile(
        principal: PrincipalId,
        reportId: String,
    ): OmniResult<ContentReportInfoView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.principalId != principal.value &&
            !ContentReportPolicy.isLocalUiPrincipal(principal.value)
        ) {
            return forbidden("not owner of report", "reportId" to reportId)
        }
        return reconcileInternal(record)
    }

    override suspend fun retry(
        principal: PrincipalId,
        surface: CallerSurface,
        reportId: String,
        command: ContentReportCommandIdentity,
    ): OmniResult<SubmitResult> {
        denyUnlessReviewSubmit(principal, surface)?.let {
            return OmniResult.err(it)
        }
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.state != ContentReportState.FAILED_RETRYABLE &&
            record.state != ContentReportState.QUEUED_OFFLINE
        ) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "retry only from FAILED_RETRYABLE or QUEUED_OFFLINE",
                    details = mapOf("state" to record.state.name),
                ),
            )
        }
        val online = endpoint.isNetworkAvailable() && endpoint.isAvailable()
        val now = clockMs()
        val withinTtl = now < record.expiresAtEpochMs
        val event = if (record.state == ContentReportState.QUEUED_OFFLINE) {
            "NETWORK_AVAILABLE"
        } else {
            "RETRY"
        }
        val next = applyEvent(
            record = record,
            event = event,
            guards = GuardEvaluator.of(
                "retryAllowed" to true,
                "networkAndEndpointAvailable" to online,
                "withinTtl" to withinTtl,
            ),
        ) ?: return stateConflict(record, event)

        var updated = next.copy(
            updatedAtEpochMs = now,
            resourceVersion = next.resourceVersion + 1,
            error = null,
        )
        store.putReport(updated)
        val jobId = claimSubmitJob(principal, updated, command)
        if (online) {
            updated = performSubmit(updated)
        }
        return OmniResult.ok(
            SubmitResult(
                report = ContentReportStateProjection.toInfoView(updated),
                jobId = jobId,
                queuedOffline = updated.state == ContentReportState.QUEUED_OFFLINE,
            ),
        )
    }

    override suspend fun onRemoteAccepted(
        reportId: String,
        receiptId: String,
        acceptedAt: String,
        statusUrl: String,
    ): OmniResult<ContentReportInfoView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        return acceptInternal(record, receiptId, acceptedAt, statusUrl)
    }

    override suspend fun onRemoteRetryableFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        if (record.state != ContentReportState.SUBMITTING) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "not submitting", details = mapOf("state" to record.state.name)),
            )
        }
        val next = applyEvent(
            record = record,
            event = "RETRYABLE_FAILURE",
            guards = GuardEvaluator.of("withinTtl" to (clockMs() < record.expiresAtEpochMs)),
        ) ?: return stateConflict(record, "RETRYABLE_FAILURE")
        val err = OmniError.CONTENT_REPORT_UNAVAILABLE(message = message)
        val updated = next.copy(
            error = err,
            updatedAtEpochMs = clockMs(),
            resourceVersion = next.resourceVersion + 1,
        )
        store.putReport(updated)
        return OmniResult.ok(ContentReportStateProjection.toInfoView(updated))
    }

    override suspend fun onRemoteFinalFailure(
        reportId: String,
        message: String,
    ): OmniResult<ContentReportInfoView> {
        val record = store.getReport(reportId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "report not found"))
        val next = applyEvent(
            record = record,
            event = "FINAL_FAILURE",
            guards = GuardEvaluator.of("remoteOutcomeKnown" to true),
        ) ?: return stateConflict(record, "FINAL_FAILURE")
        val err = OmniError.CONTENT_REPORT_UNAVAILABLE(message = message)
        val updated = wipeSensitive(
            next.copy(
                error = err,
                updatedAtEpochMs = clockMs(),
                resourceVersion = next.resourceVersion + 1,
            ),
        )
        store.putReport(updated)
        return OmniResult.ok(ContentReportStateProjection.toInfoView(updated))
    }

    override suspend fun expireDue(nowMs: Long): Int {
        var count = 0
        for (record in store.listAll()) {
            if (record.isTerminal) continue
            if (nowMs < record.expiresAtEpochMs) continue
            // Only states with TTL_EXPIRED catalog edge (RPT-020).
            if (!ContentReportPolicy.mayDiscard(record.state) &&
                record.state != ContentReportState.CONSENT_GRANTED
            ) {
                continue
            }
            val next = applyEvent(
                record = record,
                event = "TTL_EXPIRED",
                guards = GuardEvaluator.ALWAYS_TRUE,
            ) ?: continue
            val wiped = wipeSensitive(
                next.copy(
                    updatedAtEpochMs = nowMs,
                    resourceVersion = next.resourceVersion + 1,
                ),
            )
            store.putReport(wiped)
            // Expire any ISSUED grants for this report.
            for (g in store.listGrantsForReport(record.reportId)) {
                if (g.state == ConsentGrantState.ISSUED) {
                    expireGrant(g, nowMs)
                }
            }
            count++
        }
        return count
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun denyUnlessReviewSubmit(
        principal: PrincipalId,
        surface: CallerSurface,
    ): OmniError? {
        val kind = when {
            ContentReportPolicy.isLocalUiPrincipal(principal.value) -> PrincipalKind.LOCAL_UI
            else -> PrincipalKind.ANDROID_APP
        }
        if (!ContentReportPolicy.mayReviewAndSubmit(surface, kind)) {
            return OmniError.FORBIDDEN(
                message = "content-reports.review-submit requires trusted local UI",
                details = mapOf(
                    "fixture" to "CR-N006",
                    "surface" to surface.name,
                    "scope" to AccessScope.content_reports_review_submit.id,
                ),
            )
        }
        return null
    }

    private fun validateGrantForSubmit(
        principal: PrincipalId,
        record: ContentReportRecord,
        grant: ConsentGrant,
    ): OmniError? {
        // CR-N004: grant from another principal
        if (grant.principalId != principal.value) {
            return OmniError.FORBIDDEN(
                message = "consent grant principal mismatch",
                details = mapOf(
                    "fixture" to "CR-N004",
                    "grantPrincipal" to grant.principalId,
                    "callerPrincipal" to principal.value,
                ),
            )
        }
        if (grant.reportId != record.reportId) {
            return OmniError.FORBIDDEN(
                message = "consent grant report mismatch",
                details = mapOf("fixture" to "CR-N004"),
            )
        }
        // CR-N002: already consumed
        if (grant.state == ConsentGrantState.CONSUMED) {
            return OmniError.STATE_CONFLICT(
                message = "consent grant already consumed",
                details = mapOf("fixture" to "CR-N002", "grantId" to grant.grantId),
            )
        }
        if (grant.state == ConsentGrantState.REVOKED || grant.state == ConsentGrantState.EXPIRED) {
            return OmniError.STATE_CONFLICT(
                message = "consent grant not consumable: ${grant.state.name}",
                details = mapOf("fixture" to "CR-N005", "state" to grant.state.name),
            )
        }
        val expectedDigest = record.canonicalPayloadDigest
            ?: record.payload?.canonicalDigestHex()
        if (expectedDigest != null &&
            grant.canonicalPayloadDigest != expectedDigest
        ) {
            return OmniError.STATE_CONFLICT(
                message = "grant digest does not match report payload",
                details = mapOf("fixture" to "CR-N003"),
            )
        }
        return null
    }

    private suspend fun cancelWhileSubmitting(
        record: ContentReportRecord,
    ): OmniResult<ContentReportInfoView> {
        val next = applyEvent(
            record = record,
            event = "CANCEL",
            guards = GuardEvaluator.ALWAYS_TRUE,
        ) ?: return stateConflict(record, "CANCEL")

        var updated = next.copy(
            cancelPending = true,
            updatedAtEpochMs = clockMs(),
            resourceVersion = next.resourceVersion + 1,
        )
        store.putReport(updated)

        // Request remote cancel; race with acceptance (CR-R001 / CR-R002).
        endpoint.requestCancel(record.reportId)
        val remote = endpoint.queryReceipt(record.reportId)
        when (remote) {
            is OmniResult.Ok -> {
                val receipt = remote.value
                updated = if (receipt != null) {
                    // Remote already accepted → SUBMITTED (CR-R002)
                    acceptUnlocked(
                        updated,
                        receipt.receiptId,
                        receipt.acceptedAt,
                        receipt.statusUrl,
                        fromCancelling = true,
                    )
                } else {
                    // Not accepted → DISCARDED (CR-R001)
                    discardFromCancelling(updated)
                }
            }
            is OmniResult.Err -> {
                // Outcome unknown → RECONCILING
                val recon = applyEvent(
                    record = updated,
                    event = "OUTCOME_UNKNOWN",
                    guards = GuardEvaluator.ALWAYS_TRUE,
                ) ?: updated
                updated = recon.copy(
                    cancelPending = true,
                    updatedAtEpochMs = clockMs(),
                    resourceVersion = recon.resourceVersion + 1,
                )
                store.putReport(updated)
            }
        }
        return OmniResult.ok(ContentReportStateProjection.toInfoView(updated))
    }

    private fun discardFromCancelling(record: ContentReportRecord): ContentReportRecord {
        val next = applyEvent(
            record = record,
            event = "REMOTE_NOT_ACCEPTED",
            guards = GuardEvaluator.of(
                "cancelPending" to true,
                "remoteOutcomeKnown" to true,
            ),
        ) ?: record.copy(state = ContentReportState.DISCARDED)
        val wiped = wipeSensitive(
            next.copy(
                cancelPending = true,
                updatedAtEpochMs = clockMs(),
                resourceVersion = next.resourceVersion + 1,
            ),
        )
        store.putReport(wiped)
        return wiped
    }

    private fun discardInternal(record: ContentReportRecord): OmniResult<ContentReportInfoView> {
        val next = applyEvent(
            record = record,
            event = "DISCARD",
            guards = GuardEvaluator.ALWAYS_TRUE,
        ) ?: return stateConflict(record, "DISCARD")
        val wiped = wipeSensitive(
            next.copy(
                updatedAtEpochMs = clockMs(),
                resourceVersion = next.resourceVersion + 1,
            ),
        )
        store.putReport(wiped)
        if (activeReviewReportId == wiped.reportId) activeReviewReportId = null
        return OmniResult.ok(ContentReportStateProjection.toInfoView(wiped))
    }

    private suspend fun reconcileInternal(
        record: ContentReportRecord,
    ): OmniResult<ContentReportInfoView> {
        var current = record
        if (current.state == ContentReportState.SUBMITTING) {
            val lost = applyEvent(
                record = current,
                event = "REPLY_LOST",
                guards = GuardEvaluator.ALWAYS_TRUE,
            )
            if (lost != null) {
                current = lost.copy(
                    updatedAtEpochMs = clockMs(),
                    resourceVersion = lost.resourceVersion + 1,
                )
                store.putReport(current)
            }
        }

        if (current.state != ContentReportState.RECONCILING &&
            current.state != ContentReportState.CANCELLING
        ) {
            // Force into reconciling path for query when already mid-flight is unknown.
            if (current.state == ContentReportState.SUBMITTING) {
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "still submitting; use reply-lost path"),
                )
            }
        }

        val remote = endpoint.queryReceipt(current.reportId)
        when (remote) {
            is OmniResult.Ok -> {
                val receipt = remote.value
                if (receipt != null) {
                    val accepted = acceptUnlocked(
                        current,
                        receipt.receiptId,
                        receipt.acceptedAt,
                        receipt.statusUrl,
                        fromCancelling = current.cancelPending,
                    )
                    return OmniResult.ok(ContentReportStateProjection.toInfoView(accepted))
                }
                // Not accepted
                val event = when {
                    current.cancelPending -> "NOT_ACCEPTED_CANCEL_PENDING"
                    clockMs() < current.expiresAtEpochMs -> "NOT_ACCEPTED_RETRYABLE"
                    else -> "FINAL_REJECTION"
                }
                val next = applyEvent(
                    record = current,
                    event = event,
                    guards = GuardEvaluator.of(
                        "remoteOutcomeKnown" to true,
                        "cancelPending" to current.cancelPending,
                        "withinTtl" to (clockMs() < current.expiresAtEpochMs),
                    ),
                ) ?: return stateConflict(current, event)
                val updated = if (ContentReportPolicy.retentionWipesPayloadOn(next.state)) {
                    wipeSensitive(next)
                } else {
                    next
                }.copy(
                    updatedAtEpochMs = clockMs(),
                    resourceVersion = next.resourceVersion + 1,
                )
                store.putReport(updated)
                return OmniResult.ok(ContentReportStateProjection.toInfoView(updated))
            }
            is OmniResult.Err -> {
                lastError = remote.error
                return OmniResult.err(remote.error)
            }
        }
    }

    private fun acceptInternal(
        record: ContentReportRecord,
        receiptId: String,
        acceptedAt: String,
        statusUrl: String,
    ): OmniResult<ContentReportInfoView> {
        val updated = acceptUnlocked(record, receiptId, acceptedAt, statusUrl, fromCancelling = false)
        return OmniResult.ok(ContentReportStateProjection.toInfoView(updated))
    }

    private fun acceptUnlocked(
        record: ContentReportRecord,
        receiptId: String,
        acceptedAt: String,
        statusUrl: String,
        fromCancelling: Boolean,
    ): ContentReportRecord {
        val event = when {
            fromCancelling && record.state == ContentReportState.CANCELLING ->
                "REMOTE_ALREADY_ACCEPTED"
            record.state == ContentReportState.RECONCILING -> "RECEIPT_FOUND"
            else -> "ACCEPTED"
        }
        val next = applyEvent(
            record = record,
            event = event,
            guards = GuardEvaluator.of("remoteOutcomeKnown" to true),
        ) ?: record.copy(state = ContentReportState.SUBMITTED)

        val receipt = ContentReportReceipt(
            receiptId = receiptId,
            reportId = record.reportId,
            acceptedAt = acceptedAt,
            statusUrl = statusUrl,
        )
        store.putReceipt(receipt)

        val wiped = wipeSensitive(
            next.copy(
                receiptId = receiptId,
                receiptAcceptedAt = acceptedAt,
                receiptStatusUrl = statusUrl,
                error = null,
                updatedAtEpochMs = clockMs(),
                resourceVersion = next.resourceVersion + 1,
            ),
        )
        store.putReport(wiped)
        return wiped
    }

    private suspend fun performSubmit(record: ContentReportRecord): ContentReportRecord {
        val frozen = record.frozenPayload ?: record.payload
            ?: return record
        val digest = record.canonicalPayloadDigest ?: frozen.canonicalDigestHex()
        return when (
            val result = endpoint.submit(
                reportId = record.reportId,
                canonicalPayloadDigest = digest,
                payloadCanonicalJson = frozen.toCanonicalJson(),
            )
        ) {
            is OmniResult.Ok -> acceptUnlocked(
                record,
                result.value.receiptId,
                result.value.acceptedAt,
                result.value.statusUrl,
                fromCancelling = false,
            )
            is OmniResult.Err -> {
                // Reply loss path: query remote
                if (result.error.code.code == "INTERNAL" ||
                    result.error.message?.contains("reply lost") == true
                ) {
                    val lost = applyEvent(
                        record = record,
                        event = "REPLY_LOST",
                        guards = GuardEvaluator.ALWAYS_TRUE,
                    ) ?: record.copy(state = ContentReportState.RECONCILING)
                    val mid = lost.copy(
                        updatedAtEpochMs = clockMs(),
                        resourceVersion = lost.resourceVersion + 1,
                        error = result.error,
                    )
                    store.putReport(mid)
                    when (val q = endpoint.queryReceipt(record.reportId)) {
                        is OmniResult.Ok -> {
                            val receipt = q.value
                            if (receipt != null) {
                                acceptUnlocked(
                                    mid,
                                    receipt.receiptId,
                                    receipt.acceptedAt,
                                    receipt.statusUrl,
                                    fromCancelling = false,
                                )
                            } else {
                                mid
                            }
                        }
                        is OmniResult.Err -> mid
                    }
                } else if (result.error.retryable) {
                    val next = applyEvent(
                        record = record,
                        event = "RETRYABLE_FAILURE",
                        guards = GuardEvaluator.of(
                            "withinTtl" to (clockMs() < record.expiresAtEpochMs),
                        ),
                    ) ?: record.copy(state = ContentReportState.FAILED_RETRYABLE)
                    val updated = next.copy(
                        error = result.error,
                        updatedAtEpochMs = clockMs(),
                        resourceVersion = next.resourceVersion + 1,
                    )
                    store.putReport(updated)
                    updated
                } else {
                    val next = applyEvent(
                        record = record,
                        event = "FINAL_FAILURE",
                        guards = GuardEvaluator.of("remoteOutcomeKnown" to true),
                    ) ?: record.copy(state = ContentReportState.FAILED_FINAL)
                    val updated = wipeSensitive(
                        next.copy(
                            error = result.error,
                            updatedAtEpochMs = clockMs(),
                            resourceVersion = next.resourceVersion + 1,
                        ),
                    )
                    store.putReport(updated)
                    updated
                }
            }
        }
    }

    private fun claimSubmitJob(
        principal: PrincipalId,
        record: ContentReportRecord,
        command: ContentReportCommandIdentity,
    ): String? {
        return try {
            val jobId = JobId(command.commandId)
            val identity = JobIdentity(
                jobId = jobId,
                principalId = principal,
                kind = JobKind.CONTENT_REPORT,
                idempotencyKey = IdempotencyKey.parse(command.idempotencyKey),
                canonicalSpecDigest = (record.canonicalPayloadDigest
                    ?: "0".repeat(64)).lowercase(),
            )
            val params = JobParameters.ContentReport(reportId = record.reportId)
            when (val claim = ports.jobManager.create(identity, params)) {
                is OmniResult.Ok -> {
                    if (claim.value.createdNew) {
                        ports.jobManager.start(jobId)
                        ports.jobManager.updateProgress(
                            jobId,
                            JobProgress(currentPhase = record.state.name),
                        )
                    }
                    jobId.value
                }
                is OmniResult.Err -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyEvent(
        record: ContentReportRecord,
        event: String,
        guards: GuardEvaluator,
    ): ContentReportRecord? {
        val to = ContentReportFsm.tryTransition(record.state, event, guards) ?: return null
        return record.copy(state = to)
    }

    private fun wipeSensitive(record: ContentReportRecord): ContentReportRecord =
        record.copy(
            payload = null,
            frozenPayload = null,
            hasEncryptedPayload = false,
        )

    private fun expireGrant(grant: ConsentGrant, nowMs: Long) {
        if (grant.state == ConsentGrantState.ISSUED) {
            store.putGrant(
                grant.copy(state = ConsentGrantState.EXPIRED, consumedAtEpochMs = null),
            )
        }
    }

    private fun maybeExpire(record: ContentReportRecord): ContentReportRecord {
        val now = clockMs()
        if (record.isTerminal) return record
        if (now < record.expiresAtEpochMs) return record
        if (!ContentReportPolicy.mayDiscard(record.state) &&
            record.state != ContentReportState.CONSENT_GRANTED &&
            record.state != ContentReportState.DRAFT &&
            record.state != ContentReportState.REVIEWING &&
            record.state != ContentReportState.QUEUED_OFFLINE &&
            record.state != ContentReportState.FAILED_RETRYABLE
        ) {
            return record
        }
        val next = applyEvent(
            record = record,
            event = "TTL_EXPIRED",
            guards = GuardEvaluator.ALWAYS_TRUE,
        ) ?: return record
        val wiped = wipeSensitive(
            next.copy(
                updatedAtEpochMs = now,
                resourceVersion = next.resourceVersion + 1,
            ),
        )
        store.putReport(wiped)
        return wiped
    }

    private fun buildReview(record: ContentReportRecord): ContentReportReviewView {
        val payload = record.payload
            ?: ContentReportPayload(
                reportId = record.reportId,
                category = record.category,
                createdAt = "1970-01-01T00:00:00Z",
                appBuild = "unknown",
                modelRevisionId = "0".repeat(64),
                engineBuildId = "unknown",
                backend = "unknown",
                localPolicyVersion = "unknown",
                outputDigest = "0".repeat(64),
                userLocale = "und",
            )
        return ContentReportReviewView(
            reportId = record.reportId,
            state = record.state,
            payloadPreview = payload.previewFields(),
            canonicalPayloadDigest = record.canonicalPayloadDigest
                ?: payload.canonicalDigestHex(),
            minimizedDefault = payload.isMinimizedDefault(),
            privacyNoticeKey = ports.endpointConfig.privacyDisclosureKey(),
            retentionNoticeKey = ports.endpointConfig.retentionNoticeKey(),
        )
    }

    private fun negotiateRequired(): OmniError? {
        for (cap in ContentReportModule.REQUIRED_CAPABILITIES) {
            when (val state = ports.capabilityAvailability.resolve(cap)) {
                CapabilityState.SUPPORTED,
                CapabilityState.CONDITIONAL,
                -> Unit
                CapabilityState.UNSUPPORTED ->
                    return OmniError.CAPABILITY_UNSUPPORTED(
                        message = "required capability unsupported",
                        details = mapOf("capabilityId" to cap.id),
                    )
                CapabilityState.UNKNOWN,
                CapabilityState.TEMPORARILY_UNAVAILABLE,
                ->
                    return OmniError.CAPABILITY_UNKNOWN(
                        message = "required capability not known (fail closed)",
                        details = mapOf(
                            "capabilityId" to cap.id,
                            "state" to state.name,
                        ),
                    )
            }
        }
        // CONTENT_REPORTING must be present.
        val reporting = ports.capabilityAvailability.resolve(CapabilityId.CONTENT_REPORTING)
        if (reporting != CapabilityState.SUPPORTED && reporting != CapabilityState.CONDITIONAL) {
            return OmniError.CONTENT_REPORT_UNAVAILABLE(
                message = "CONTENT_REPORTING capability not available",
                details = mapOf("state" to reporting.name),
            )
        }
        return null
    }

    private fun forbidden(message: String, vararg details: Pair<String, String>): OmniResult.Err =
        OmniResult.err(
            OmniError.FORBIDDEN(message = message, details = details.toMap()),
        ) as OmniResult.Err

    private fun stateConflict(
        record: ContentReportRecord,
        event: String,
    ): OmniResult.Err =
        OmniResult.err(
            OmniError.STATE_CONFLICT(
                message = "illegal CONTENT_REPORT transition",
                details = mapOf(
                    "state" to record.state.name,
                    "event" to event,
                    "reportId" to record.reportId,
                ),
            ),
        ) as OmniResult.Err
}
