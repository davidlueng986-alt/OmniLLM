package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.IOmniRuntime
import ai.omnillm.api.IOmniStreamCallback
import ai.omnillm.api.IStreamSession
import ai.omnillm.api.OmniAssetCreateRequest
import ai.omnillm.api.OmniAssetInfo
import ai.omnillm.api.OmniAssetUploadRequest
import ai.omnillm.api.OmniChatRequest
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniContentReportInfo
import ai.omnillm.api.OmniContentReportProposalRequest
import ai.omnillm.api.OmniContentReportReceipt
import ai.omnillm.api.OmniEmbeddingRequest
import ai.omnillm.api.OmniEvent
import ai.omnillm.api.OmniModelPage
import ai.omnillm.api.OmniRequestState
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
import com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.TransportDeliveryGuarantee
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.contentreport.api.CancelReportSpec
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.CreateProposalSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.interfaces.aidl.AidlAuthority
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.requestregistry.ClaimOutcome
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Restricted exported [IOmniRuntime] facade (ANDROID-SERVICE / ANDROID-BINDER / ADR-011).
 *
 * Principal = observed calling UID + [ClientRegistration] (never self-reported package).
 * Stream path: chat → Orchestrator Plan→Reserve→Commit→Execute (sole claimer) →
 * [IStreamSession] credit/ACK delivery; embed remains honest UNKNOWN.
 * Large lists paginated; assets via PFD → [AssetHandleBroker] (same semantics as HTTP).
 *
 * Does **not** expose Admin, secret broker, or arbitrary file APIs.
 * INV-001: facade runs only in `:runtime` (control plane). Never loads native from UI.
 */
class OmniRuntimeFacade(
    private val context: Context,
    private val registration: ClientRegistration,
    private val registrations: ClientRegistrationStore,
    private val sessions: StreamSessionRegistry,
    private val assets: AssetHandleBroker,
    private val streamExecutor: Executor = DEFAULT_STREAM_EXECUTOR,
) : IOmniRuntime.Stub() {

    override fun getProtocolMajor(): Int = AidlAuthority.SCHEMA_VERSION

    override fun getProtocolMinor(): Int = 0

    override fun chat(request: OmniChatRequest?, callback: IOmniStreamCallback?): IStreamSession {
        val reg = requireRegistration(AccessScope.inference_create)
        rejectIfNotAccepting("chat")
        if (callback == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "callback required").message,
            )
        }
        if (request == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "request required").message,
            )
        }
        // Orchestrator is the sole claimer (ADR-004/005). Do not pre-claim in startStream
        // or submit would return Existing without Plan→Execute (SW-ENG-07).
        return startOrchestratedChat(request, callback, reg)
    }

    override fun embed(request: OmniEmbeddingRequest?, callback: IOmniStreamCallback?): IStreamSession {
        val reg = requireRegistration(AccessScope.inference_create)
        rejectIfNotAccepting("embed")
        if (callback == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "callback required").message,
            )
        }
        if (request == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "request required").message,
            )
        }
        // Embedding stays UNKNOWN/UNQUALIFIED for the exploratory llama path (honest).
        // Claim-or-return still durable; terminal is fail-closed capability, not a TODO.
        return startStream(
            operationKind = OP_EMBEDDING,
            requestIdRaw = request.requestId,
            idempotencyKeyRaw = request.idempotencyKey,
            model = request.model,
            canonicalDigest = embedDigest(request),
            callback = callback,
            registration = reg,
            buildAcceptedEvents = { session ->
                listOf(
                    metaEvent("accepted", model = request.model),
                    terminalFailedEvent(
                        code = OmniErrorCode.CAPABILITY_UNKNOWN,
                        message = "embedding remains UNKNOWN/unqualified (no silent elevate)",
                        details = mapOf(
                            "requestId" to session.requestIdValue,
                            "operation" to OP_EMBEDDING,
                            "capability" to CapabilityId.EMBEDDING.id,
                        ),
                    ),
                )
            },
        )
    }

    /**
     * Chat via plane [com.omnillm.runtime.orchestrator.Orchestrator] (PRCE).
     * Fail-closed when engine unbound or exploratory flag off; CONDITIONAL only
     * under explicit policy — never invents SUPPORTED (INV-018 / HARD RULE 4).
     */
    private fun startOrchestratedChat(
        request: OmniChatRequest,
        callback: IOmniStreamCallback,
        registration: ClientRegistration,
    ): IStreamSession {
        val plane = RuntimeControlPlane.require()
        // Bind llama-cpp when READY/DEGRADED so exploratory path can execute (not permanent stub).
        plane.ensureEnginePacksAttached()
        val requestId = try {
            RequestId.parse(request.requestId.orEmpty())
        } catch (_: IllegalArgumentException) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "requestId must be a client-generated UUID",
                ).message,
            )
        }
        val idem = try {
            IdempotencyKey.parse(request.idempotencyKey.orEmpty())
        } catch (_: IllegalArgumentException) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "idempotencyKey required",
                ).message,
            )
        }
        val digest = chatDigest(request)

        // COR-20: reuse is principal-scoped — never hand another principal's
        // live session for the same requestId. Registry entries are always
        // StreamSessionFacade in production.
        sessions.getForPrincipal(requestId.value, registration.principalId.value)
            ?.takeUnless { it.isClosed() }
            ?.let { return it as StreamSessionFacade }

        val session = StreamSessionFacade(
            requestId = requestId.value,
            principalId = registration.principalId,
            registrationHandle = registration.registrationHandle,
            boundUid = registration.callingUid,
            callback = callback,
            executor = streamExecutor,
            modelRevisionId = request.model?.takeIf { it.isNotBlank() },
            onClosed = { sessions.remove(it) },
        )
        sessions.put(session)
        session.grantCredit(eventCredit = 16L, byteCredit = 32L * 1024L)

        // COR-15: native generation must never run on the binder transaction
        // thread. Dispatch orchestration (submit → pumpOnce → engine.start) to
        // the dedicated stream executor; events are delivered asynchronously via
        // the session's delivery engine. The binder call returns immediately.
        streamExecutor.execute {
            val events = runBlocking {
                runCatching {
                    buildChatOrchestrationEvents(
                        plane = plane,
                        registration = registration,
                        request = request,
                        requestId = requestId,
                        idempotencyKey = idem,
                        canonicalDigest = digest,
                    )
                }.getOrElse { t ->
                    listOf(
                        metaEvent("accepted", model = request.model),
                        terminalFailedEvent(
                            code = OmniErrorCode.INTERNAL,
                            message = t.message ?: "chat orchestration failed",
                            details = mapOf(
                                "requestId" to requestId.value,
                                "operation" to OP_CHAT,
                            ),
                        ),
                    )
                }
            }
            if (!session.isClosed()) {
                session.enqueue(events)
            }
            // Durable terminal recorded even when the observer already closed.
            val terminal = events.lastOrNull { it.terminal }
            if (terminal != null && terminal.terminalState != null) {
                val existing = plane.requestRegistry.queryRequestTerminal(requestId)
                if (existing == null && plane.requestRegistry.queryRequest(requestId) != null) {
                    plane.requestRegistry.recordTerminal(
                        requestId = requestId,
                        terminalState = mapTerminalState(terminal.terminalState!!),
                        terminalSeq = System.nanoTime(),
                        errorCode = terminal.error?.code,
                    )
                }
            }
        }
        return session
    }

    private suspend fun buildChatOrchestrationEvents(
        plane: RuntimeControlPlane,
        registration: ClientRegistration,
        request: OmniChatRequest,
        requestId: RequestId,
        idempotencyKey: IdempotencyKey,
        canonicalDigest: Sha256Digest,
    ): List<OmniEvent> {
        val binding = try {
            plane.engineExecute
        } catch (_: Exception) {
            return listOf(
                metaEvent("accepted", model = request.model),
                terminalFailedEvent(
                    code = OmniErrorCode.CAPABILITY_UNSUPPORTED,
                    message = "engine execute binding not available on control plane",
                    details = mapOf("requestId" to requestId.value, "operation" to OP_CHAT),
                ),
            )
        }
        if (!binding.isEngineBound()) {
            return listOf(
                metaEvent("accepted", model = request.model),
                terminalFailedEvent(
                    code = OmniErrorCode.CAPABILITY_UNSUPPORTED,
                    message = "chat engine not attached (fail closed — native missing or unbound)",
                    details = mapOf("requestId" to requestId.value, "operation" to OP_CHAT),
                ),
            )
        }
        if (!binding.isExploratoryExecuteEnabled()) {
            return listOf(
                metaEvent("accepted", model = request.model),
                terminalFailedEvent(
                    code = OmniErrorCode.CAPABILITY_UNSUPPORTED,
                    message = "experimental generate disabled; enable " +
                        "${EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE} (LOCAL_ADMIN). " +
                        "Capability remains UNKNOWN without evidence.",
                    details = mapOf(
                        "requestId" to requestId.value,
                        "setting" to EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE,
                    ),
                ),
            )
        }

        val modelRaw = request.model?.takeIf { it.isNotBlank() }
            ?: return listOf(
                metaEvent("accepted", model = request.model),
                terminalFailedEvent(
                    code = OmniErrorCode.INVALID_REQUEST,
                    message = "model (modelRevisionId) required",
                    details = mapOf("requestId" to requestId.value),
                ),
            )
        val revision = try {
            val hex = modelRaw.lowercase().let {
                if (it.matches(Regex("^[0-9a-f]{64}$"))) it else IdentityHashing.sha256Hex("model|$it")
            }
            ModelRevisionId.parse(hex)
        } catch (_: Exception) {
            return listOf(
                metaEvent("accepted", model = request.model),
                terminalFailedEvent(
                    code = OmniErrorCode.INVALID_REQUEST,
                    message = "invalid modelRevisionId",
                    details = mapOf("requestId" to requestId.value, "model" to modelRaw),
                ),
            )
        }

        val installation = ControlPlaneFeaturePorts.resolveInstallation(plane.modelManager, revision)
        val device = DeviceExecutionFingerprint.parse("device-fp-aidl-runtime")
        val candidate = when (
            val c = ControlPlaneFeaturePorts.buildCandidate(binding, revision, installation, device)
        ) {
            is OmniResult.Err -> return listOf(
                metaEvent("accepted", model = request.model),
                terminalFromOmniError(c.error, requestId),
            )
            is OmniResult.Ok -> c.value
        }

        val orchRequest = OrchestrationRequest(
            requestId = requestId,
            principalId = registration.principalId,
            idempotencyKey = idempotencyKey,
            operationKind = OP_CHAT,
            canonicalRequestDigest = canonicalDigest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate),
            routing = ControlPlaneFeaturePorts.exploratoryRouting(),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = plane.identity.runtimeEpoch,
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 8,
        )

        return when (val submitted = plane.orchestrator.submit(orchRequest)) {
            is OmniResult.Err -> listOf(
                metaEvent("accepted", model = request.model),
                terminalFromOmniError(submitted.error, requestId),
            )
            is OmniResult.Ok -> {
                // Existing claim (reply-loss): do not re-execute; surface durable state.
                if (submitted.value.claim == com.omnillm.runtime.orchestrator.ClaimKind.EXISTING) {
                    val term = plane.requestRegistry.queryRequestTerminal(requestId)
                    if (term != null) {
                        val code = term.errorCode?.let { OmniErrorCode.fromCode(it) }
                            ?: OmniErrorCode.INTERNAL
                        return listOf(
                            metaEvent("existing", model = request.model),
                            terminalFailedEvent(
                                code = code,
                                message = "durable terminal ${term.terminalState}",
                                details = mapOf("requestId" to requestId.value, "claim" to "EXISTING"),
                            ),
                        )
                    }
                    return listOf(metaEvent("existing", model = request.model))
                }
                val pump = plane.orchestrator.pumpOnce()
                val view = plane.orchestrator.query(requestId)
                val routing = when {
                    pump is OmniResult.Ok -> pump.value.actualRouting
                    else -> submitted.value.actualRouting
                }
                when {
                    pump is OmniResult.Err -> listOf(
                        metaEvent("accepted", model = request.model),
                        terminalFromOmniError(pump.error, requestId),
                    )
                    pump is OmniResult.Ok && pump.value.errorCode != null -> listOf(
                        metaEvent("accepted", model = request.model),
                        terminalFailedEvent(
                            code = OmniErrorCode.fromCode(pump.value.errorCode!!)
                                ?: OmniErrorCode.INTERNAL,
                            message = pump.value.errorCode!!,
                            details = mapOf(
                                "requestId" to requestId.value,
                                "state" to pump.value.state,
                            ),
                        ),
                    )
                    pump is OmniResult.Ok &&
                        (pump.value.state == "COMPLETED" || pump.value.state == "STREAMING") -> {
                        val completed = pump.value.state == "COMPLETED" ||
                            view?.terminalState == "COMPLETED"
                        buildList {
                            add(metaEvent("accepted", model = routing?.modelRevisionId?.hex ?: request.model))
                            // Synthetic shim may complete without token deltas; surface honest degraded note.
                            add(
                                OmniEvent().apply {
                                    kind = "metadata"
                                    textDelta = null
                                    hasTokenCount = false
                                    tokenCount = 0L
                                    hasProgress = false
                                    progress = 0.0
                                    actualModelRevisionId = routing?.modelRevisionId?.hex
                                    engineBuildId = routing?.engineBuildId?.value
                                        ?: candidate.engineBuildId.value
                                    backend = routing?.backend ?: "cpu"
                                    terminalState = null
                                    error = null
                                    extensionSchemaId = null
                                    extensionCanonicalJson =
                                        """{"degraded":true,"reasons":["CONDITIONAL exploratory execute","engine cells UNQUALIFIED"]}"""
                                    terminal = false
                                },
                            )
                            if (completed) {
                                add(
                                    OmniEvent().apply {
                                        kind = "completed"
                                        textDelta = null
                                        hasTokenCount = false
                                        tokenCount = 0L
                                        hasProgress = false
                                        progress = 1.0
                                        actualModelRevisionId = routing?.modelRevisionId?.hex
                                        engineBuildId = routing?.engineBuildId?.value
                                            ?: candidate.engineBuildId.value
                                        backend = routing?.backend ?: "cpu"
                                        terminalState = "COMPLETED"
                                        error = null
                                        extensionSchemaId = null
                                        extensionCanonicalJson = null
                                        terminal = true
                                    },
                                )
                            }
                        }
                    }
                    else -> listOf(
                        metaEvent("accepted", model = request.model),
                        terminalFailedEvent(
                            code = OmniErrorCode.ADMISSION_REJECTED,
                            message = "chat orchestration did not reach streaming/completed " +
                                "(state=${pump?.let { (it as? OmniResult.Ok)?.value?.state } ?: view?.state ?: submitted.value.state})",
                            details = mapOf("requestId" to requestId.value),
                        ),
                    )
                }
            }
        }
    }

    private fun terminalFromOmniError(error: OmniError, requestId: RequestId): OmniEvent =
        terminalFailedEvent(
            code = error.code,
            message = error.message ?: error.code.code,
            details = mapOf("requestId" to requestId.value) + error.details,
        )

    override fun queryRequest(requestId: String?): OmniRequestState {
        val reg = requireRegistration(AccessScope.inference_read_own)
        val state = OmniRequestState()
        state.requestId = requestId.orEmpty()
        val plane = RuntimeControlPlane.require()
        val reqId = try {
            RequestId.parse(requestId.orEmpty())
        } catch (_: IllegalArgumentException) {
            state.state = "FAILED"
            state.resourceVersion = 0L
            state.terminalError = BinderErrors.omniError(
                OmniErrorCode.INVALID_REQUEST,
                "requestId must be a UUID",
            )
            return state
        }
        val row = plane.requestRegistry.queryRequest(reqId)
        if (row == null) {
            state.state = "FAILED"
            state.resourceVersion = 0L
            state.terminalError = BinderErrors.notFound("request not found")
            return state
        }
        if (row.principalId != reg.principalId.value) {
            state.state = "FAILED"
            state.resourceVersion = 0L
            state.terminalError = BinderErrors.forbidden("not owner of request")
            return state
        }
        state.state = row.state
        state.resourceVersion = row.resourceVersion
        state.actualModelRevisionId = row.revisionId
        state.engineBuildId = null
        state.backend = null
        val term = plane.requestRegistry.queryRequestTerminal(reqId)
        if (term?.errorCode != null) {
            val code = OmniErrorCode.fromCode(term.errorCode!!) ?: OmniErrorCode.INTERNAL
            state.terminalError = BinderErrors.omniError(code, "terminal ${term.terminalState}")
        } else {
            state.terminalError = null
        }
        return state
    }

    override fun cancelRequest(requestId: String?, command: OmniCommandRequest?): CommandResult {
        val reg = requireRegistration(AccessScope.inference_cancel)
        val plane = RuntimeControlPlane.require()
        if (command == null) {
            return BinderErrors.failedCommand(
                commandId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "command required",
            )
        }
        val reqIdRaw = requestId.orEmpty()
        val reqId = try {
            RequestId.parse(reqIdRaw)
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = command.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "requestId must be a UUID",
            )
        }
        // Prefer live stream session cancel (same durable path).
        // Registry entries are always StreamSessionFacade in production.
        (sessions.get(reqIdRaw) as? StreamSessionFacade)?.cancel(command)?.let { return it }

        val commandId = try {
            CommandId.parse(command.commandId.orEmpty())
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = command.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId must be a UUID",
            )
        }
        val idem = try {
            IdempotencyKey.parse(command.idempotencyKey.orEmpty())
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = command.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "invalid idempotencyKey",
            )
        }
        val digest = try {
            Sha256Digest.parse(
                command.canonicalInputDigest?.takeIf { it.isNotBlank() }?.lowercase()
                    ?: StreamSessionFacade.cancelInputDigest(reqIdRaw).hex,
            )
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = command.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "canonicalInputDigest must be sha256 hex",
            )
        }

        when (
            val claim = plane.commandLedger.claim(
                principal = reg.principalId,
                operationKind = StreamSessionFacade.OP_CANCEL_REQUEST,
                idempotencyKey = idem,
                canonicalHash = digest,
                commandId = commandId,
                expectedVersion = if (command.hasExpectedVersion) command.expectedVersion else null,
            )
        ) {
            is ClaimOutcome.Conflict ->
                return BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = OmniErrorCode.IDEMPOTENCY_CONFLICT,
                    message = claim.error.message ?: "idempotency conflict",
                )
            is ClaimOutcome.Existing -> {
                val row = claim.value
                return CommandResult().apply {
                    this.commandId = row.commandId
                    this.state = row.state
                    this.resourceVersion = row.resourceVersion
                    this.affectedResourceId = row.affectedResourceId
                    this.resultSchemaId = null
                    this.resultCanonicalJson = null
                    this.error = row.errorCode?.let {
                        val code = OmniErrorCode.fromCode(it) ?: OmniErrorCode.INTERNAL
                        BinderErrors.omniError(code, "prior command result")
                    }
                }
            }
            is ClaimOutcome.New -> Unit
        }

        val existing = plane.requestRegistry.queryRequest(reqId)
            ?: run {
                plane.commandLedger.recordResult(
                    commandId = commandId,
                    state = "FAILED",
                    errorCode = OmniErrorCode.NOT_FOUND.code,
                )
                return BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = OmniErrorCode.NOT_FOUND,
                    message = "request not found",
                )
            }
        if (existing.principalId != reg.principalId.value) {
            plane.commandLedger.recordResult(
                commandId = commandId,
                state = "FAILED",
                errorCode = OmniErrorCode.FORBIDDEN.code,
            )
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.FORBIDDEN,
                message = "not owner of request",
            )
        }
        if (plane.requestRegistry.queryRequestTerminal(reqId) == null) {
            plane.requestRegistry.recordTerminal(
                requestId = reqId,
                terminalState = "CANCELLED",
                terminalSeq = System.nanoTime(),
                errorCode = OmniErrorCode.CANCELLED.code,
            )
        }
        val recorded = plane.commandLedger.recordResult(
            commandId = commandId,
            state = "SUCCEEDED",
            affectedResourceId = reqIdRaw,
        )
        val version = when (recorded) {
            is com.omnillm.core.canonical.generated.OmniResult.Ok -> recorded.value.resourceVersion
            else -> 0L
        }
        return CommandResult().apply {
            this.commandId = command.commandId
            this.state = "SUCCEEDED"
            this.resourceVersion = version
            this.affectedResourceId = reqIdRaw
            this.resultSchemaId = null
            this.resultCanonicalJson = null
            this.error = null
        }
    }

    override fun queryCommand(commandId: String?): CommandResult {
        val reg = requireRegistration(AccessScope.commands_read_own)
        val plane = RuntimeControlPlane.require()
        val id = try {
            CommandId.parse(commandId.orEmpty())
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId must be a UUID",
            )
        }
        val row = plane.commandLedger.queryCommand(id)
            ?: return BinderErrors.failedCommand(
                commandId = commandId.orEmpty(),
                code = OmniErrorCode.NOT_FOUND,
                message = "command not found",
            )
        if (row.principalId != reg.principalId.value) {
            return BinderErrors.failedCommand(
                commandId = commandId.orEmpty(),
                code = OmniErrorCode.FORBIDDEN,
                message = "not owner of command",
            )
        }
        return CommandResult().apply {
            this.commandId = row.commandId
            this.state = row.state
            this.resourceVersion = row.resourceVersion
            this.affectedResourceId = row.affectedResourceId
            this.resultSchemaId = null
            this.resultCanonicalJson = row.resultJson
            this.error = row.errorCode?.let {
                val code = OmniErrorCode.fromCode(it) ?: OmniErrorCode.INTERNAL
                BinderErrors.omniError(code, "command error")
            }
        }
    }

    override fun listModels(pageToken: String?, pageSize: Int): OmniModelPage {
        requireRegistration(AccessScope.models_read)
        // COR-02: never let an internal projection failure (or principal-gate
        // regression) escape as an unhandled exception on the binder thread.
        // Map to a clean, empty page — fail closed, no invented codes.
        val plane = RuntimeControlPlane.get()
        if (plane == null) return emptyModelPage()
        return try {
            buildModelPage(
                pageToken = pageToken,
                pageSize = pageSize,
                snapshotVersion = plane.identity.runtimeEpoch,
            ) { plane.modelHubApi.listInstalled(registration.principalId) }
        } catch (_: Exception) {
            emptyModelPage()
        }
    }

    /**
     * Pure page projection (COR-02/TST-02): one [listInstalled] call, bounded
     * page, opaque index cursor. Companion function so JVM regression tests can
     * inject fakes without Android Binder/Context (never mints new error codes).
     */
    override fun createAsset(request: OmniAssetCreateRequest?): OmniAssetInfo {
        val reg = requireRegistration(AccessScope.assets_create)
        rejectIfNotAccepting("createAsset")
        if (request == null) {
            throw RemoteException(
                BinderErrors.omniError(OmniErrorCode.INVALID_REQUEST, "request required").message,
            )
        }
        return assets.create(reg.principalId, request)
    }

    override fun uploadAssetContent(
        request: OmniAssetUploadRequest?,
        content: ParcelFileDescriptor?,
    ): CommandResult {
        val reg = requireRegistration(AccessScope.assets_create)
        rejectIfNotAccepting("uploadAssetContent")
        if (request == null) {
            content?.close()
            return BinderErrors.failedCommand(
                commandId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "request required",
            )
        }
        return assets.upload(reg.principalId, request, content)
    }

    override fun commitAsset(assetId: String?, command: OmniCommandRequest?): OmniAssetInfo {
        val reg = requireRegistration(AccessScope.assets_create)
        rejectIfNotAccepting("commitAsset")
        return assets.commit(reg.principalId, assetId, command)
    }

    override fun getAsset(assetId: String?): OmniAssetInfo {
        val reg = requireRegistration(AccessScope.assets_read_own)
        return assets.get(reg.principalId, assetId)
    }

    override fun deleteAsset(assetId: String?, command: OmniCommandRequest?): CommandResult {
        val reg = requireRegistration(AccessScope.assets_delete_own)
        return assets.delete(reg.principalId, assetId, command)
    }

    override fun createContentReportProposal(
        request: OmniContentReportProposalRequest?,
    ): OmniContentReportInfo {
        val reg = requireRegistration(AccessScope.content_reports_propose)
        rejectIfNotAccepting("createContentReportProposal")
        // Report stream ≠ telemetry (SEC-PRIVACY / FEAT-AI-CONTENT-REPORT).
        check(ContentReportPolicy.DATA_STREAM_KIND != ContentReportPolicy.TELEMETRY_STREAM_KIND)
        if (request == null || request.command == null) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "OmniContentReportProposalRequest.command required",
                    transport = TransportDeliveryGuarantee.AIDL_UNARY,
                ).message,
            )
        }
        val cmd = request.command!!
        if (cmd.commandId.isNullOrBlank() || cmd.idempotencyKey.isNullOrBlank()) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "commandId and idempotencyKey required",
                    transport = TransportDeliveryGuarantee.AIDL_UNARY,
                ).message,
            )
        }
        val plane = RuntimeControlPlane.require()
        val api = plane.contentReportApi
        val createdAt = if (request.createdAtEpochMillis > 0L) {
            Instant.ofEpochMilli(request.createdAtEpochMillis).toString()
        } else {
            Instant.now().toString()
        }
        val spec = CreateProposalSpec(
            command = ContentReportCommandIdentity(
                commandId = cmd.commandId,
                idempotencyKey = cmd.idempotencyKey,
            ),
            reportId = request.reportId.orEmpty(),
            category = request.category.orEmpty(),
            createdAt = createdAt,
            appBuild = request.appBuild.orEmpty(),
            modelRevisionId = request.modelRevisionId.orEmpty(),
            engineBuildId = request.engineBuildId.orEmpty(),
            backend = request.backend.orEmpty(),
            localPolicyVersion = request.localPolicyVersion.orEmpty(),
            outputDigest = request.outputDigest.orEmpty(),
            userLocale = request.userLocale.orEmpty(),
            description = request.description,
            promptExcerpt = request.promptExcerpt,
            outputExcerpt = request.outputExcerpt,
            diagnosticSummary = request.diagnosticSummary,
            userConfirmed = false, // CR-N001: exported AIDL never asserts consent
        )
        return runBlocking {
            when (
                val r = api.createProposal(
                    principal = reg.principalId,
                    surface = CallerSurface.EXPORTED_AIDL,
                    profileAuthenticated = true,
                    accessProfileId = "APP_CLIENT",
                    spec = spec,
                )
            ) {
                is OmniResult.Ok -> ContentReportAidlMapper.toAidlInfo(r.value)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(r.error).message ?: r.error.code.code,
                )
            }
        }
    }

    override fun getContentReport(reportId: String?): OmniContentReportInfo {
        val reg = requireRegistration(AccessScope.content_reports_read_own)
        val plane = RuntimeControlPlane.require()
        return runBlocking {
            when (val r = plane.contentReportApi.getReport(reg.principalId, reportId.orEmpty())) {
                is OmniResult.Ok -> ContentReportAidlMapper.toAidlInfo(r.value)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(r.error).message ?: r.error.code.code,
                )
            }
        }
    }

    override fun getContentReportReceipt(reportId: String?): OmniContentReportReceipt {
        val reg = requireRegistration(AccessScope.content_reports_read_own)
        val plane = RuntimeControlPlane.require()
        return runBlocking {
            when (val r = plane.contentReportApi.getReceipt(reg.principalId, reportId.orEmpty())) {
                is OmniResult.Ok -> ContentReportAidlMapper.toAidlReceipt(r.value.receipt)
                is OmniResult.Err -> throw RemoteException(
                    AdminAidlMapper.toAidlError(r.error).message ?: r.error.code.code,
                )
            }
        }
    }

    override fun cancelContentReport(reportId: String?, command: OmniCommandRequest?): CommandResult {
        val reg = requireRegistration(AccessScope.content_reports_manage_own)
        if (command == null || command.commandId.isNullOrBlank() || command.idempotencyKey.isNullOrBlank()) {
            return BinderErrors.failedCommand(
                commandId = command?.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId and idempotencyKey required",
            )
        }
        val plane = RuntimeControlPlane.require()
        return runBlocking {
            when (
                val r = plane.contentReportApi.cancelReport(
                    principal = reg.principalId,
                    surface = CallerSurface.EXPORTED_AIDL,
                    accessProfileId = "APP_CLIENT",
                    authenticated = true,
                    spec = CancelReportSpec(
                        reportId = reportId.orEmpty(),
                        command = ContentReportCommandIdentity(
                            commandId = command.commandId,
                            idempotencyKey = command.idempotencyKey,
                        ),
                    ),
                )
            ) {
                is OmniResult.Ok -> {
                    val state = if (r.value.state.name == "CANCELLING") "UNCERTAIN" else "SUCCEEDED"
                    CommandResult().apply {
                        this.commandId = command.commandId
                        this.state = state
                        this.resourceVersion = r.value.resourceVersion
                        this.affectedResourceId = reportId
                        this.resultSchemaId = null
                        this.resultCanonicalJson = null
                        this.error = null
                    }
                }
                is OmniResult.Err -> BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = r.error.code,
                    message = r.error.message ?: r.error.code.code,
                )
            }
        }
    }

    override fun discardContentReport(reportId: String?, command: OmniCommandRequest?): CommandResult {
        val reg = requireRegistration(AccessScope.content_reports_manage_own)
        if (command == null || command.commandId.isNullOrBlank() || command.idempotencyKey.isNullOrBlank()) {
            return BinderErrors.failedCommand(
                commandId = command?.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId and idempotencyKey required",
            )
        }
        val plane = RuntimeControlPlane.require()
        return runBlocking {
            when (
                val r = plane.contentReportApi.discardReport(
                    principal = reg.principalId,
                    surface = CallerSurface.EXPORTED_AIDL,
                    accessProfileId = "APP_CLIENT",
                    authenticated = true,
                    spec = DiscardReportSpec(
                        reportId = reportId.orEmpty(),
                        command = ContentReportCommandIdentity(
                            commandId = command.commandId,
                            idempotencyKey = command.idempotencyKey,
                        ),
                    ),
                )
            ) {
                is OmniResult.Ok -> CommandResult().apply {
                    this.commandId = command.commandId
                    this.state = "SUCCEEDED"
                    this.resourceVersion = r.value.resourceVersion
                    this.affectedResourceId = reportId
                    this.resultSchemaId = null
                    this.resultCanonicalJson = null
                    this.error = null
                }
                is OmniResult.Err -> BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = r.error.code,
                    message = r.error.message ?: r.error.code.code,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Stream claim + session
    // ------------------------------------------------------------------

    private fun startStream(
        operationKind: String,
        requestIdRaw: String?,
        idempotencyKeyRaw: String?,
        model: String?,
        canonicalDigest: Sha256Digest,
        callback: IOmniStreamCallback,
        registration: ClientRegistration,
        buildAcceptedEvents: (StreamSessionFacade) -> List<OmniEvent>,
    ): IStreamSession {
        val plane = RuntimeControlPlane.require()
        val requestId = try {
            RequestId.parse(requestIdRaw.orEmpty())
        } catch (_: IllegalArgumentException) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "requestId must be a client-generated UUID",
                ).message,
            )
        }
        val idem = try {
            IdempotencyKey.parse(idempotencyKeyRaw.orEmpty())
        } catch (_: IllegalArgumentException) {
            throw RemoteException(
                BinderErrors.omniError(
                    OmniErrorCode.INVALID_REQUEST,
                    "idempotencyKey required",
                ).message,
            )
        }

        val claim = plane.requestRegistry.claim(
            principal = registration.principalId,
            operationKind = operationKind,
            idempotencyKey = idem,
            canonicalHash = canonicalDigest,
            requestId = requestId,
            revisionId = model?.takeIf { it.isNotBlank() },
        )

        when (claim) {
            is ClaimOutcome.Conflict -> {
                throw RemoteException(
                    BinderErrors.omniError(
                        OmniErrorCode.IDEMPOTENCY_CONFLICT,
                        claim.error.message ?: "idempotency conflict",
                    ).message,
                )
            }
            is ClaimOutcome.Existing, is ClaimOutcome.New -> Unit
        }

        // RECEIVED → CLAIMED for new work (REQ-001). Full plan/reserve/execute remains TODO.
        if (claim is ClaimOutcome.New) {
            plane.requestRegistry.updateState(requestId, "CLAIMED")
        }

        // Reuse live session if still open for this request (duplicate open).
        // COR-20: reuse is principal-scoped — never another principal's session.
        sessions.getForPrincipal(requestId.value, registration.principalId.value)
            ?.takeUnless { it.isClosed() }
            ?.let { return it as StreamSessionFacade }

        val session = StreamSessionFacade(
            requestId = requestId.value,
            principalId = registration.principalId,
            registrationHandle = registration.registrationHandle,
            boundUid = registration.callingUid,
            callback = callback,
            executor = streamExecutor,
            modelRevisionId = model?.takeIf { it.isNotBlank() },
            onClosed = { sessions.remove(it) },
        )
        sessions.put(session)

        // Initial credit is granted inside StreamDeliveryEngine; extra window for batch.
        session.grantCredit(eventCredit = 16L, byteCredit = 32L * 1024L)

        when (claim) {
            is ClaimOutcome.Existing -> {
                val term = plane.requestRegistry.queryRequestTerminal(requestId)
                if (term != null) {
                    val code = term.errorCode?.let { OmniErrorCode.fromCode(it) }
                    session.enqueueTerminal(
                        terminalState = term.terminalState,
                        error = code?.let {
                            BinderErrors.omniError(it, "durable terminal ${term.terminalState}")
                        },
                    )
                } else {
                    // Reply-loss reconnect: session is queryable; no automatic re-stream of tokens.
                    session.enqueue(
                        listOf(metaEvent("existing", model = model)),
                    )
                }
            }
            is ClaimOutcome.New -> {
                val events = buildAcceptedEvents(session)
                session.enqueue(events)
                // Record durable terminal if scaffold emitted FAILED.
                val terminal = events.lastOrNull { it.terminal }
                if (terminal != null && terminal.terminalState != null) {
                    val errCode = terminal.error?.code
                    plane.requestRegistry.recordTerminal(
                        requestId = requestId,
                        terminalState = mapTerminalState(terminal.terminalState!!),
                        terminalSeq = System.nanoTime(),
                        errorCode = errCode,
                    )
                }
            }
            is ClaimOutcome.Conflict -> error("unreachable")
        }
        return session
    }

    private fun mapTerminalState(streamTerminal: String): String = when (streamTerminal) {
        "CANCELLED" -> "CANCELLED"
        "COMPLETED", "completed" -> "COMPLETED"
        else -> "FAILED"
    }

    private fun requireRegistration(scope: AccessScope): ClientRegistration {
        val observed = PrincipalObservation.observe(context)
        if (observed.callingUid != registration.callingUid) {
            throw SecurityException("principal UID drift on IOmniRuntime")
        }
        val live = registrations.resolveActive(registration.registrationHandle, observed)
            ?: throw RemoteException(
                BinderErrors.pairingRequired("registration inactive or revoked").message,
            )
        if (!registrations.requireScope(live, scope)) {
            throw RemoteException(
                BinderErrors.forbidden("missing scope ${scope.id}").message,
            )
        }
        return live
    }

    private fun rejectIfNotAccepting(op: String) {
        val plane = RuntimeControlPlane.get()
            ?: throw RemoteException(BinderErrors.internal("control plane not attached").message)
        if (!plane.acceptsWork()) {
            throw RemoteException(
                BinderErrors.stateConflict(
                    "runtime state=${plane.runtimeState} rejects $op",
                ).message,
            )
        }
    }

    companion object {
        const val OP_CHAT: String = "CHAT"
        const val OP_EMBEDDING: String = "EMBEDDING"
        const val MAX_MODEL_PAGE_SIZE: Int = 50

        private val DEFAULT_STREAM_EXECUTOR: Executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "omnillm-aidl-stream").apply { isDaemon = true }
            }

        /**
         * Pure page projection (COR-02/TST-02): one [listInstalled] call, bounded
         * page, opaque index cursor. Companion function so JVM regression tests
         * can inject fakes without Android Binder/Context. Never mints new error
         * codes — a failed projection yields a clean, empty page (fail closed).
         */
        internal fun buildModelPage(
            pageToken: String?,
            pageSize: Int,
            snapshotVersion: Long,
            listInstalled: suspend () -> com.omnillm.core.canonical.generated.OmniResult<
                List<com.omnillm.features.modelhub.api.ModelCard>
                >,
        ): OmniModelPage {
            val limit = pageSize.coerceIn(1, MAX_MODEL_PAGE_SIZE)
            val page = OmniModelPage()
            page.nextPageToken = null
            page.snapshotVersion = snapshotVersion
            val listed = try {
                runBlocking { listInstalled() }
            } catch (_: Exception) {
                page.items = emptyArray()
                return page
            }
            when (listed) {
                is com.omnillm.core.canonical.generated.OmniResult.Err -> {
                    page.items = emptyArray()
                    return page
                }
                is com.omnillm.core.canonical.generated.OmniResult.Ok -> Unit
            }
            val cards = listed.value
            val offset = offsetFromPageToken(pageToken)
            val items = cards
                .drop(offset)
                .take(limit)
                .map { card ->
                    AdminAidlMapper.toAidlModelInfo(
                        modelRevisionId = card.modelRevisionId,
                        displayName = card.displayName,
                        installationState = card.installationState,
                    )
                }.toTypedArray()
            page.items = items
            val nextIndex = offset + items.size
            page.nextPageToken = if (nextIndex < cards.size) nextIndex.toString() else null
            return page
        }

        internal fun emptyModelPage(): OmniModelPage = OmniModelPage().apply {
            nextPageToken = null
            items = emptyArray()
            snapshotVersion = 0L
        }

        private fun offsetFromPageToken(pageToken: String?): Int {
            if (pageToken.isNullOrBlank()) return 0
            return pageToken.toIntOrNull()?.coerceAtLeast(0) ?: 0
        }

        // COR-13: digests hash FULL message content + asset ids, so two requests
        // with identical lengths but different content yield different digests
        // (idempotency conflict on replay — never silent content substitution).
        fun chatDigest(request: OmniChatRequest): Sha256Digest {
            val msgs = request.messages?.joinToString("\u001f") { m ->
                val role = m.role.orEmpty()
                val content = m.content.orEmpty()
                val assetIds = m.assetIds?.joinToString(",").orEmpty()
                "$role\u001e$content\u001e$assetIds"
            }.orEmpty()
            val payload =
                """{"op":"CHAT","requestId":"${request.requestId}","model":"${request.model}","stream":${request.stream},"msgs":[$msgs]}"""
            return Sha256Digest.parse(IdentityHashing.sha256Hex(payload))
        }

        fun embedDigest(request: OmniEmbeddingRequest): Sha256Digest {
            // COR-13: hash the full input list — never lengths only.
            val inputs = request.inputs?.joinToString("\u001f").orEmpty()
            val payload =
                """{"op":"EMBEDDING","requestId":"${request.requestId}","model":"${request.model}","inputs":[$inputs]}"""
            return Sha256Digest.parse(IdentityHashing.sha256Hex(payload))
        }

        fun metaEvent(kind: String, model: String?): OmniEvent = OmniEvent().apply {
            this.kind = kind
            textDelta = null
            hasTokenCount = false
            tokenCount = 0L
            hasProgress = false
            progress = 0.0
            actualModelRevisionId = model
            engineBuildId = null
            backend = null
            terminalState = null
            error = null
            extensionSchemaId = null
            extensionCanonicalJson = null
            terminal = false
        }

        fun terminalFailedEvent(
            code: OmniErrorCode,
            message: String,
            details: Map<String, String> = emptyMap(),
        ): OmniEvent = OmniEvent().apply {
            kind = "error"
            textDelta = null
            hasTokenCount = false
            tokenCount = 0L
            hasProgress = false
            progress = 0.0
            actualModelRevisionId = null
            engineBuildId = null
            backend = null
            terminalState = "FAILED"
            error = BinderErrors.omniError(code, message, details)
            extensionSchemaId = null
            extensionCanonicalJson = null
            terminal = true
        }
    }
}
