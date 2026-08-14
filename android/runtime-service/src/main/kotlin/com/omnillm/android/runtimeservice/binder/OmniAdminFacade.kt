package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.IJobObserver
import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniAdminSnapshot
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniConsentGrant
import ai.omnillm.api.OmniContentReportReceipt
import ai.omnillm.api.OmniJobInfo
import ai.omnillm.api.OmniJobSpec
import ai.omnillm.api.OmniSettingsPatch
import ai.omnillm.api.OmniSettingsSnapshot
import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.admin.AdminJobEventBatch
import com.omnillm.interfaces.admin.AdminJobEventSink
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.policy.SettingValue
import kotlinx.coroutines.runBlocking

/**
 * Non-exported [IOmniAdmin] for the same-app UI process only
 * (ANDROID-SERVICE 禮1, access-control-catalog LOCAL_UI / LOCAL_ADMIN, INV-001).
 *
 * Never returned from the exported RuntimeBindingService.
 * All durable mutations return [CommandResult] (no void success claims).
 * Stream ACK / closeSubscription are delivery-control, not domain mutations.
 */
class OmniAdminFacade(
    private val context: Context,
) : IOmniAdmin.Stub() {

    private val deathRecipients = java.util.concurrent.ConcurrentHashMap<String, IBinder.DeathRecipient>()

    override fun getSnapshot(): OmniAdminSnapshot {
        val principal = assertLocalUi()
        val api = requireApi()
        val domain = api.getSnapshot(principal)
        // Project ModelHub installations into Admin snapshot.models (live Admin API).
        val models = runBlocking {
            val plane = RuntimeControlPlane.get()
            if (plane != null) {
                when (val listed = plane.modelHubApi.listInstalled(principal)) {
                    is OmniResult.Ok -> listed.value.map { card ->
                        AdminAidlMapper.toAidlModelInfo(
                            modelRevisionId = card.modelRevisionId,
                            displayName = card.displayName,
                            installationState = card.installationState,
                        )
                    }.toTypedArray()
                    is OmniResult.Err -> emptyArray()
                }
            } else {
                emptyArray()
            }
        }
        return AdminAidlMapper.toAidlSnapshot(domain, models)
    }

    override fun getSettings(): OmniSettingsSnapshot {
        val principal = assertLocalUi()
        return AdminAidlMapper.toAidlSettings(requireApi().getSettings(principal))
    }

    override fun applySettings(patch: OmniSettingsPatch?): CommandResult {
        val principal = assertLocalUi()
        val command = AdminAidlMapper.toDomainCommand(patch?.command)
            ?: return failed(
                patch?.command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on settings patch"),
            )
        val changes = linkedMapOf<String, SettingValue>()
        for (entry in patch?.changes.orEmpty()) {
            if (entry == null || entry.key.isNullOrBlank()) {
                return failed(command.commandId, OmniError.INVALID_REQUEST(message = "blank setting key"))
            }
            val value = AdminAidlMapper.fromSettingEntry(entry)
                ?: return failed(
                    command.commandId,
                    OmniError.INVALID_REQUEST(
                        message = "unknown setting valueType",
                        details = mapOf("valueType" to entry.valueType.orEmpty()),
                    ),
                )
            changes[entry.key] = value
        }
        ensureRuntimeAcceptingOrAllowStoppedRead()
        return AdminAidlMapper.toAidlCommandResult(
            requireApi().applySettings(principal, command, changes),
        )
    }

    override fun startJob(spec: OmniJobSpec?): OmniJobInfo {
        val principal = assertLocalUi()
        ensureRuntimeAccepting()
        val domainSpec = AdminAidlMapper.toDomainJobSpec(spec)
            ?: throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "invalid OmniJobSpec (kind/parameters/command)",
                ).message,
            )
        // Route fixture pin DOWNLOAD / local SAF IMPORT through ModelHub + AcquisitionPipeline.
        val plane = RuntimeControlPlane.get()
        if (plane != null && domainSpec.kind == "DOWNLOAD") {
            val params = domainSpec.parameters as? com.omnillm.runtime.job.JobParameters.Download
            if (params != null) {
                val routed = routeFixtureDownload(plane, principal, domainSpec, params)
                if (routed != null) return routed
            }
        }
        // IMPORT with contentFd is handled by importLocalFile(); plain startJob IMPORT
        // without FD remains unsupported here (fail closed).
        return when (val result = requireApi().startJob(principal, domainSpec)) {
            is OmniResult.Ok -> AdminAidlMapper.toAidlJobInfo(result.value)
            is OmniResult.Err -> throw RemoteException(
                AdminAidlMapper.toAidlError(result.error).message
                    ?: result.error.code.code,
            )
        }
    }

    override fun getJob(jobId: String?): OmniJobInfo {
        val principal = assertLocalUi()
        return when (val result = requireApi().getJob(principal, jobId.orEmpty())) {
            is OmniResult.Ok -> AdminAidlMapper.toAidlJobInfo(result.value)
            is OmniResult.Err -> throw RemoteException(
                AdminAidlMapper.toAidlError(result.error).message ?: result.error.code.code,
            )
        }
    }

    override fun cancelJob(jobId: String?, command: OmniCommandRequest?): CommandResult {
        val principal = assertLocalUi()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: return failed(
                command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on cancelJob"),
            )
        return AdminAidlMapper.toAidlCommandResult(
            requireApi().cancelJob(principal, jobId.orEmpty(), domainCommand),
        )
    }

    override fun queryCommand(commandId: String?): CommandResult {
        val principal = assertLocalUi()
        return AdminAidlMapper.toAidlCommandResult(
            requireApi().queryCommand(principal, commandId.orEmpty()),
        )
    }

    override fun observeJobs(cursor: String?, credit: Int, observer: IJobObserver?): String {
        val principal = assertLocalUi()
        if (observer == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "observer required").message,
            )
        }
        val sink = object : AdminJobEventSink {
            override fun onEvents(batch: AdminJobEventBatch) {
                try {
                    observer.onEvents(AdminAidlMapper.toAidlJobEventBatch(batch))
                } catch (_: RemoteException) {
                    // Binder death path; subscription cleanup via DeathRecipient.
                }
            }

            override fun onRejected(error: OmniError) {
                try {
                    observer.onRejected(AdminAidlMapper.toAidlError(error))
                } catch (_: RemoteException) {
                    // ignore
                }
            }
        }
        return when (val sub = requireApi().observeJobs(principal, cursor, credit, sink)) {
            is OmniResult.Ok -> {
                linkObserverDeath(sub.value, observer.asBinder())
                sub.value
            }
            is OmniResult.Err -> {
                try {
                    observer.onRejected(AdminAidlMapper.toAidlError(sub.error))
                } catch (_: RemoteException) {
                    // ignore
                }
                throw RemoteException(
                    AdminAidlMapper.toAidlError(sub.error).message ?: sub.error.code.code,
                )
            }
        }
    }

    override fun ackJobEvents(subscriptionId: String?, streamEpoch: Long, eventToExclusive: Long) {
        val principal = assertLocalUi()
        // Delivery control ??not a durable domain mutation (no CommandResult on AIDL).
        val result = requireApi().ackJobEvents(
            principal,
            subscriptionId.orEmpty(),
            streamEpoch,
            eventToExclusive,
        )
        if (result is OmniResult.Err) {
            // Fail closed silently for unknown sub after death; otherwise surface via RemoteException
            // only for clear client bugs (epoch mismatch).
            if (result.error.code != OmniErrorCode.NOT_FOUND) {
                throw RemoteException(
                    AdminAidlMapper.toAidlError(result.error).message ?: result.error.code.code,
                )
            }
        }
    }

    override fun closeSubscription(subscriptionId: String?) {
        val principal = assertLocalUi()
        val id = subscriptionId.orEmpty()
        unlinkDeath(id)
        requireApi().closeSubscription(principal, id)
    }

    override fun reviewContentReport(
        reportId: String?,
        canonicalPayloadDigest: String?,
        warningPolicyVersion: String?,
        localUserProfileId: String?,
        command: OmniCommandRequest?,
    ): OmniConsentGrant {
        val principal = assertLocalUi()
        ensureRuntimeAccepting()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "invalid OmniCommandRequest on reviewContentReport",
                ).message,
            )
        if (reportId.isNullOrBlank() ||
            canonicalPayloadDigest.isNullOrBlank() ||
            warningPolicyVersion.isNullOrBlank() ||
            localUserProfileId.isNullOrBlank()
        ) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "reportId / digest / warningPolicyVersion / localUserProfileId required",
                ).message,
            )
        }
        val api = requireContentReportApi()
        val cmd = ContentReportCommandIdentity(
            commandId = domainCommand.commandId,
            idempotencyKey = domainCommand.idempotencyKey,
        )
        return runBlocking {
            // DRAFT ??REVIEWING if needed, then issue one-time ConsentGrant.
            when (
                val review = api.beginLocalReview(
                    principal = principal,
                    surface = ContentReportPolicy.CallerSurface.LOCAL_TRUSTED_UI,
                    spec = BeginReviewSpec(reportId = reportId, command = cmd),
                )
            ) {
                is OmniResult.Err -> {
                    // Already REVIEWING is recoverable; other errors fail closed.
                    if (review.error.code != OmniErrorCode.STATE_CONFLICT) {
                        throw RemoteException(
                            AdminAidlMapper.toAidlError(review.error).message
                                ?: review.error.code.code,
                        )
                    }
                }
                is OmniResult.Ok -> Unit
            }
            when (
                val grant = api.grantConsent(
                    principal = principal,
                    surface = ContentReportPolicy.CallerSurface.LOCAL_TRUSTED_UI,
                    spec = GrantConsentSpec(
                        reportId = reportId,
                        command = cmd,
                        canonicalPayloadDigest = canonicalPayloadDigest,
                        warningPolicyVersion = warningPolicyVersion,
                        localUserProfileId = localUserProfileId,
                    ),
                )
            ) {
                is OmniResult.Ok -> ContentReportAidlMapper.toAidlConsentGrant(grant.value.grant)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(grant.error).message ?: grant.error.code.code,
                )
            }
        }
    }

    override fun submitContentReport(
        reportId: String?,
        consentGrantId: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val principal = assertLocalUi()
        ensureRuntimeAccepting()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: return failed(
                command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on submitContentReport"),
            )
        if (reportId.isNullOrBlank() || consentGrantId.isNullOrBlank()) {
            return failed(
                domainCommand.commandId,
                OmniError.INVALID_REQUEST(message = "reportId and consentGrantId required"),
            )
        }
        val api = requireContentReportApi()
        return runBlocking {
            when (
                val result = api.submitReport(
                    principal = principal,
                    surface = ContentReportPolicy.CallerSurface.LOCAL_TRUSTED_UI,
                    spec = SubmitReportSpec(
                        reportId = reportId,
                        consentGrantId = consentGrantId,
                        command = ContentReportCommandIdentity(
                            commandId = domainCommand.commandId,
                            idempotencyKey = domainCommand.idempotencyKey,
                        ),
                    ),
                )
            ) {
                is OmniResult.Ok -> AdminAidlMapper.toAidlCommandResult(
                    AdminCommandResult.succeeded(
                        commandId = domainCommand.commandId,
                        resourceVersion = result.value.report.resourceVersion,
                        affectedResourceId = result.value.report.reportId,
                        resultSchemaId = "ContentReportSubmitResult",
                        resultCanonicalJson = ContentReportAidlMapper.submitResultJson(result.value),
                    ),
                )
                is OmniResult.Err -> failed(domainCommand.commandId, result.error)
            }
        }
    }

    override fun getContentReportReceipt(reportId: String?): OmniContentReportReceipt {
        val principal = assertLocalUi()
        if (reportId.isNullOrBlank()) {
            throw RemoteException(BinderErrors.notFound("content report receipt not found").message)
        }
        val api = requireContentReportApi()
        return runBlocking {
            when (val result = api.getReceipt(principal, reportId)) {
                is OmniResult.Ok -> ContentReportAidlMapper.toAidlReceipt(result.value.receipt)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(result.error).message ?: result.error.code.code,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // LOCAL_UI inference proxies (FEAT-PLAYGROUND / FEAT-SERVER) ??SW-FEAT-04/05
    // ------------------------------------------------------------------

    override fun executePlaygroundChat(
        modelRevisionId: String?,
        userMessage: String?,
        requestId: String?,
        idempotencyKey: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val principal = assertLocalUi()
        ensureRuntimeAccepting()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: return failed(
                command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on playground chat"),
            )
        val model = modelRevisionId?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "modelRevisionId required"))
        val message = userMessage.orEmpty()
        if (message.isBlank()) {
            return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "userMessage required"))
        }
        val reqId = requestId?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "requestId required"))
        val idem = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "idempotencyKey required"))
        val plane = RuntimeControlPlane.require()
        plane.ensureEnginePacksAttached()
        // D23f: full-content canonical digest (mirrors OmniRuntimeFacade.chatDigest /
        // COR-13) — the old message.length digest collided for same-length
        // different-content replays.
        val digest = AdminChatDigest.digest(reqId, idem, model, message)
        val revisionHex = model.lowercase().let {
            if (it.matches(Regex("^[0-9a-f]{64}$"))) it
            else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|$model")
        }
        return runBlocking {
            when (
                val result = plane.playgroundApi.startChat(
                    principal = principal,
                    spec = com.omnillm.features.playground.api.ChatRequestSpec(
                        identity = com.omnillm.features.playground.api.InferenceIdentity(
                            requestId = reqId,
                            idempotencyKey = idem,
                            canonicalInputDigest = digest,
                        ),
                        modelRevisionId = revisionHex,
                        messages = listOf(
                            com.omnillm.features.playground.api.ChatMessage(
                                role = "user",
                                content = message,
                            ),
                        ),
                        stream = false,
                        sourceSession = com.omnillm.features.playground.api.SourceSessionRef.None,
                    ),
                )
            ) {
                is OmniResult.Ok -> {
                    val strip = result.value
                    AdminAidlMapper.toAidlCommandResult(
                        AdminCommandResult.succeeded(
                            commandId = domainCommand.commandId,
                            resourceVersion = 0L,
                            affectedResourceId = strip.requestId,
                            resultSchemaId = "PlaygroundChatResult",
                            resultCanonicalJson = playgroundStripJson(strip),
                        ),
                    )
                }
                is OmniResult.Err -> failed(domainCommand.commandId, result.error)
            }
        }
    }

    override fun queryPlaygroundRequest(requestId: String?): CommandResult {
        val principal = assertLocalUi()
        val reqId = requestId?.takeIf { it.isNotBlank() }
            ?: return failed("", OmniError.INVALID_REQUEST(message = "requestId required"))
        val plane = RuntimeControlPlane.require()
        return runBlocking {
            when (val result = plane.playgroundApi.queryRequest(principal, reqId)) {
                is OmniResult.Ok -> AdminAidlMapper.toAidlCommandResult(
                    AdminCommandResult.succeeded(
                        commandId = "",
                        resourceVersion = 0L,
                        affectedResourceId = reqId,
                        resultSchemaId = "PlaygroundChatResult",
                        resultCanonicalJson = playgroundStripJson(result.value),
                    ),
                )
                is OmniResult.Err -> failed("", result.error)
            }
        }
    }

    override fun cancelPlaygroundRequest(
        requestId: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val principal = assertLocalUi()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: return failed(
                command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on playground cancel"),
            )
        val reqId = requestId?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "requestId required"))
        val plane = RuntimeControlPlane.require()
        val cancelDigest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
            "admin-playground-cancel|$reqId|${domainCommand.commandId}|${domainCommand.idempotencyKey}",
        )
        return runBlocking {
            when (
                val result = plane.playgroundApi.cancelRequest(
                    principal = principal,
                    spec = com.omnillm.features.playground.api.CancelInferenceSpec(
                        requestId = reqId,
                        commandId = domainCommand.commandId,
                        idempotencyKey = domainCommand.idempotencyKey,
                        canonicalInputDigest = cancelDigest,
                    ),
                )
            ) {
                is OmniResult.Ok -> AdminAidlMapper.toAidlCommandResult(
                    AdminCommandResult.succeeded(
                        commandId = domainCommand.commandId,
                        resourceVersion = 0L,
                        affectedResourceId = reqId,
                        resultSchemaId = "PlaygroundCancelResult",
                        // D18: codec-built JSON — complete escaping.
                        resultCanonicalJson = AdminResultJson.playgroundCancel(
                            requestId = reqId,
                            phase = result.value.phase,
                            requestState = result.value.requestState,
                            isTerminal = result.value.isTerminal,
                        ),
                    ),
                )
                is OmniResult.Err -> failed(domainCommand.commandId, result.error)
            }
        }
    }

    override fun executeServerSmoke(
        modelRevisionId: String?,
        requestId: String?,
        idempotencyKey: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val principal = assertLocalUi()
        ensureRuntimeAccepting()
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: return failed(
                command?.commandId,
                OmniError.INVALID_REQUEST(message = "invalid OmniCommandRequest on server smoke"),
            )
        val model = modelRevisionId?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "modelRevisionId required"))
        val reqId = requestId?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "requestId required"))
        val idem = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: return failed(domainCommand.commandId, OmniError.INVALID_REQUEST(message = "idempotencyKey required"))
        val plane = RuntimeControlPlane.require()
        plane.ensureEnginePacksAttached()
        val revisionHex = model.lowercase().let {
            if (it.matches(Regex("^[0-9a-f]{64}$"))) it
            else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|$model")
        }
        val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
            "admin-server-smoke|$reqId|$idem|$revisionHex",
        )
        val claim = com.omnillm.features.server.api.InferenceClaimSpec(
            requestId = reqId,
            idempotencyKey = idem,
            operationKind = "CHAT",
            canonicalRequestDigestHex = digest,
            requiredCapabilities = setOf(com.omnillm.core.canonical.generated.CapabilityId.TEXT_GENERATION.id),
            modelRevisionIdHex = revisionHex,
            deadlineMonotonic = Long.MAX_VALUE / 8,
            runtimeEpoch = plane.identity.runtimeEpoch,
        )
        return runBlocking {
            when (val result = plane.developerServerApi.runSmokeInference(principal, claim)) {
                is OmniResult.Ok -> {
                    val smoke = result.value
                    AdminAidlMapper.toAidlCommandResult(
                        AdminCommandResult.succeeded(
                            commandId = domainCommand.commandId,
                            resourceVersion = 0L,
                            affectedResourceId = smoke.requestId ?: reqId,
                            resultSchemaId = "ServerSmokeResult",
                            // D18: codec-built JSON — complete escaping (the old
                            // string concat mutated errorMessage with replace()).
                            resultCanonicalJson = AdminResultJson.serverSmoke(
                                result = smoke,
                                fallbackRequestId = reqId,
                            ),
                        ),
                    )
                }
                is OmniResult.Err -> failed(domainCommand.commandId, result.error)
            }
        }
    }

    override fun getInferenceCapabilityState(
        capabilityId: String?,
        modelRevisionId: String?,
    ): String {
        assertLocalUi()
        val capRaw = capabilityId?.takeIf { it.isNotBlank() } ?: return "UNKNOWN"
        val model = modelRevisionId.orEmpty()
        val plane = RuntimeControlPlane.get() ?: return "UNKNOWN"
        plane.ensureEnginePacksAttached()
        val binding = try {
            plane.engineExecute
        } catch (_: Exception) {
            return "UNKNOWN"
        }
        val cap = try {
            com.omnillm.core.canonical.generated.CapabilityId.requireFromId(capRaw)
        } catch (_: Exception) {
            return "UNKNOWN"
        }
        val cand = with(com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts) {
            // ARC-06: probe routes through real installation resolution; fails
            // closed (UNKNOWN) when the revision is not actually installed.
            binding.probeCandidate(
                model,
                kotlinx.coroutines.runBlocking { plane.modelManager.listInstallations() },
            )
        } ?: return "UNKNOWN"
        return binding.resolveCapability(cap, cand).name
    }

    private fun playgroundStripJson(strip: com.omnillm.features.playground.api.RequestStripUi): String =
        // D18: codec-built JSON — complete escaping (the old hand-rolled
        // replace() chain leaked quotes/backslashes/control chars).
        AdminResultJson.playgroundStrip(strip)

    // ------------------------------------------------------------------

    private fun assertLocalUi(): com.omnillm.core.contracts.PrincipalId {
        val observed = PrincipalObservation.observe(context)
        if (!observed.isLocalAdminEligible) {
            throw SecurityException(
                BinderErrors.forbidden(
                    "IOmniAdmin requires LOCAL_UI same-app UID " +
                        "(callingUid=${observed.callingUid})",
                ).message,
            )
        }
        // Catalog principal LOCAL_UI (not caller package name).
        check(observed.localUiPrincipalId.value == LocalUiPrincipal.ID.value)
        return observed.localUiPrincipalId
    }

    private fun requireApi() =
        RuntimeControlPlane.get()?.adminApi
            ?: throw RemoteException(BinderErrors.internal("control plane / Admin API not attached").message)

    private fun requireContentReportApi() =
        RuntimeControlPlane.get()?.contentReportApi
            ?: throw RemoteException(
                BinderErrors.internal("control plane / ContentReport API not attached").message,
            )

    /**
     * LOCAL_UI SAF / Downloads import: read-only PFD ??quarantine ??READY.
     * Trust channel remains LOCAL_IMPORT (never elevates authenticity).
     */
    override fun importLocalFile(
        contentFd: android.os.ParcelFileDescriptor?,
        displayName: String?,
        expectedSha256: String?,
        expectedBytes: Long,
        modelRevisionId: String?,
        artifactPackageId: String?,
        installationId: String?,
        jobId: String?,
        command: OmniCommandRequest?,
    ): OmniJobInfo {
        assertLocalUi()
        ensureRuntimeAccepting()
        val plane = RuntimeControlPlane.get()
            ?: throw RemoteException(BinderErrors.internal("control plane not attached").message)
        val fd = contentFd
            ?: throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "contentFd required").message,
            )
        val sha = expectedSha256?.lowercase()?.trim().orEmpty()
        if (!sha.matches(Regex("^[0-9a-f]{64}$"))) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "expectedSha256 must be 64-hex").message,
            )
        }
        if (expectedBytes <= 0L) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "expectedBytes must be > 0").message,
            )
        }
        val rev = modelRevisionId?.lowercase()?.trim().orEmpty()
        val pkg = artifactPackageId?.lowercase()?.trim().orEmpty()
        val installId = installationId?.trim().orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
        val jid = jobId?.trim().orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
        val name = displayName?.trim().orEmpty().ifBlank { "Imported model" }
        if (!rev.matches(Regex("^[0-9a-f]{64}$")) || !pkg.matches(Regex("^[0-9a-f]{64}$"))) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "modelRevisionId/artifactPackageId must be 64-hex",
                ).message,
            )
        }
        val domainCommand = AdminAidlMapper.toDomainCommand(command)
            ?: throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "invalid OmniCommandRequest").message,
            )
        val mhCommand = com.omnillm.features.modelhub.api.ModelHubCommandIdentity(
            commandId = domainCommand.commandId,
            idempotencyKey = domainCommand.idempotencyKey,
            canonicalInputDigest = domainCommand.canonicalInputDigest,
        )
        val pipeline = com.omnillm.features.modelhub.ModelhubModule.createAcquisitionPipeline(
            api = plane.modelHubApi,
            modelStore = plane.modelStore,
        )
        // Dup so runtime owns a stable FD independent of binder lifetime.
        val dup = android.os.ParcelFileDescriptor.dup(fd.fileDescriptor)
        return try {
            runBlocking {
                val reliableSource = object : com.omnillm.features.modelhub.acquisition.ArtifactByteSource {
                    override fun open(
                        role: String,
                        cancel: java.util.concurrent.atomic.AtomicBoolean,
                    ): OmniResult<java.io.InputStream> {
                        if (cancel.get()) {
                            return OmniResult.err(OmniError.CANCELLED(message = "import open cancelled"))
                        }
                        if (role != com.omnillm.features.modelhub.catalog.FixtureArtifact.ROLE_WEIGHTS) {
                            return OmniResult.err(
                                OmniError.INVALID_REQUEST(
                                    message = "unknown import role",
                                    details = mapOf("role" to role),
                                ),
                            )
                        }
                        return try {
                            try {
                                android.system.Os.lseek(
                                    dup.fileDescriptor,
                                    0,
                                    android.system.OsConstants.SEEK_SET,
                                )
                            } catch (_: Exception) {
                                // non-seekable: attempt open as-is
                            }
                            OmniResult.ok(java.io.FileInputStream(dup.fileDescriptor))
                        } catch (e: Exception) {
                            OmniResult.err(
                                OmniError.INTERNAL(
                                    message = "import stream open failed: ${e.javaClass.simpleName}",
                                ),
                            )
                        }
                    }
                }
                when (
                    val executed = pipeline.executeSafImport(
                        installationId = installId,
                        jobId = jid,
                        modelRevisionId = rev,
                        artifactPackageId = pkg,
                        assetId = "pfd-import-$jid",
                        expectedSha256 = sha,
                        expectedBytes = expectedBytes,
                        displayName = name,
                        command = mhCommand,
                        source = reliableSource,
                        expectedFormat = "gguf",
                    )
                ) {
                    is OmniResult.Ok -> {
                        when (val job = plane.adminApi.getJob(assertLocalUi(), jid)) {
                            is OmniResult.Ok -> AdminAidlMapper.toAidlJobInfo(job.value)
                            is OmniResult.Err -> {
                                val info = OmniJobInfo()
                                info.jobId = jid
                                info.state = executed.value.job.state
                                info.resourceVersion = executed.value.job.resourceVersion
                                info.progress = 1.0
                                info
                            }
                        }
                    }
                    is OmniResult.Err -> throw RemoteException(
                        AdminAidlMapper.toAidlError(executed.error).message
                            ?: executed.error.code.code,
                    )
                }
            }
        } finally {
            try {
                dup.close()
            } catch (_: Exception) {
            }
            try {
                fd.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Catalog pin download: full software E2E via [AcquisitionPipeline]
     * (HTTPS policy ??quarantine ??digest verify ??atomic promote ??READY).
     * Offline fixture bytes run synchronously on the binder thread path.
     * Real HTTPS downloads (M1) run through [OkHttpArtifactByteSource] when the
     * job carries expected digest/size; identity is content-derived (same as SAF import).
     * Returns null when the URL cannot be executed here (caller uses plain job create).
     */
    private fun routeFixtureDownload(
        plane: RuntimeControlPlane,
        principal: com.omnillm.core.contracts.PrincipalId,
        domainSpec: com.omnillm.interfaces.admin.AdminJobSpec,
        params: com.omnillm.runtime.job.JobParameters.Download,
    ): OmniJobInfo? {
        val catalog = com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog.DEFAULT
        val entry = catalog.findByPinnedUrl(params.sourceUrl)
        val installationId = java.util.UUID.randomUUID().toString()
        val command = com.omnillm.features.modelhub.api.ModelHubCommandIdentity(
            commandId = domainSpec.command.commandId,
            idempotencyKey = domainSpec.command.idempotencyKey,
            canonicalInputDigest = domainSpec.command.canonicalInputDigest,
        )
        val pipeline = com.omnillm.features.modelhub.ModelhubModule.createAcquisitionPipeline(
            api = plane.modelHubApi,
            modelStore = plane.modelStore,
        )

        // Real HTTPS download (M1): content-derived identity from expected digest.
        if (entry == null) {
            val sha = params.expectedSha256?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            val bytes = params.expectedBytes?.takeIf { it > 0L }
            if (sha == null || bytes == null) {
                return null
            }
            val blob = com.omnillm.core.canonical.generated.BlobId.parse(sha)
            val pkgEntry = com.omnillm.core.canonical.ArtifactPackageEntry(
                role = com.omnillm.features.modelhub.catalog.FixtureArtifact.ROLE_WEIGHTS,
                blobId = blob,
                byteLength = bytes,
                shardIndex = 0,
            )
            val pkg = com.omnillm.core.canonical.ArtifactPackageCanonicalizer.artifactPackageId(
                listOf(pkgEntry),
            )
            val rev = com.omnillm.core.canonical.IdentityHashing.modelRevisionIdOfCanonicalJson(
                """{"artifactPackageId":"${pkg.hex}","format":"gguf","schemaVersion":1}""",
            )
            val urlPolicy = com.omnillm.runtime.policy.download.DownloadUrlPolicy.Policy.DEFAULT
            val source = com.omnillm.features.modelhub.acquisition.OkHttpArtifactByteSource(
                url = params.sourceUrl,
                urlPolicy = urlPolicy,
            )
            return runBlocking {
                when (
                    val executed = pipeline.executePinnedDownload(
                        installationId = installationId,
                        jobId = domainSpec.jobId,
                        modelRevisionId = rev.hex,
                        artifactPackageId = pkg.hex,
                        sourceUrl = params.sourceUrl,
                        expectedSha256 = sha,
                        expectedBytes = bytes,
                        displayName = params.targetName?.ifBlank { null } ?: "Downloaded GGUF",
                        command = command,
                        sourceOverride = source,
                        role = com.omnillm.features.modelhub.catalog.FixtureArtifact.ROLE_WEIGHTS,
                    )
                ) {
                    is OmniResult.Ok -> jobInfoFrom(plane, principal, domainSpec.jobId, executed.value)
                    is OmniResult.Err -> throw RemoteException(
                        AdminAidlMapper.toAidlError(executed.error).message
                            ?: executed.error.code.code,
                    )
                }
            }
        }

        return runBlocking {
            when (
                val executed = pipeline.executePinnedDownload(
                    installationId = installationId,
                    jobId = domainSpec.jobId,
                    modelRevisionId = entry.modelRevisionId,
                    artifactPackageId = entry.artifactPackageId,
                    sourceUrl = params.sourceUrl,
                    expectedSha256 = params.expectedSha256
                        ?: com.omnillm.features.modelhub.catalog.FixtureArtifact.blobIdHex(),
                    expectedBytes = params.expectedBytes
                        ?: com.omnillm.features.modelhub.catalog.FixtureArtifact.BYTE_LENGTH,
                    displayName = entry.displayName,
                    command = command,
                )
            ) {
                is OmniResult.Ok -> jobInfoFrom(plane, principal, domainSpec.jobId, executed.value)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(executed.error).message
                        ?: executed.error.code.code,
                )
            }
        }
    }

    private suspend fun jobInfoFrom(
        plane: RuntimeControlPlane,
        principal: com.omnillm.core.contracts.PrincipalId,
        jobId: String,
        executed: com.omnillm.features.modelhub.acquisition.AcquisitionPipeline.ExecuteResult,
    ): OmniJobInfo =
        when (val job = plane.adminApi.getJob(principal, jobId)) {
            is OmniResult.Ok -> AdminAidlMapper.toAidlJobInfo(job.value)
            is OmniResult.Err -> {
                val info = OmniJobInfo()
                info.jobId = jobId
                info.state = executed.job.state
                info.resourceVersion = executed.job.resourceVersion
                info.progress = 1.0
                info
            }
        }

    private fun ensureRuntimeAccepting() {
        val plane = RuntimeControlPlane.get()
            ?: throw RemoteException(BinderErrors.internal("control plane not attached").message)
        if (!plane.acceptsWork()) {
            throw RemoteException(
                BinderErrors.stateConflict(
                    "runtime state=${plane.runtimeState} not accepting admin mutations",
                ).message,
            )
        }
    }

    /**
     * Settings writes still go through command path; allow get/apply when plane is up
     * even if not yet READY only for apply after ensureStarted from bind.
     */
    private fun ensureRuntimeAcceptingOrAllowStoppedRead() {
        // applySettings is a durable mutation ??require accepting work.
        ensureRuntimeAccepting()
    }

    private fun failed(commandId: String?, error: OmniError): CommandResult =
        AdminAidlMapper.toAidlCommandResult(
            AdminCommandResult.failed(commandId.orEmpty(), error),
        )

    private fun linkObserverDeath(subscriptionId: String, binder: IBinder) {
        val recipient = IBinder.DeathRecipient {
            deathRecipients.remove(subscriptionId)
            RuntimeControlPlane.get()?.adminApi?.closeSubscription(
                LocalUiPrincipal.ID,
                subscriptionId,
            )
        }
        try {
            binder.linkToDeath(recipient, 0)
            deathRecipients[subscriptionId] = recipient
        } catch (_: RemoteException) {
            // Already dead ??close immediately.
            RuntimeControlPlane.get()?.adminApi?.closeSubscription(
                LocalUiPrincipal.ID,
                subscriptionId,
            )
        }
    }

    private fun unlinkDeath(subscriptionId: String) {
        deathRecipients.remove(subscriptionId)
        // Binder token not retained; DeathRecipient cleanup on close is best-effort.
    }
}
