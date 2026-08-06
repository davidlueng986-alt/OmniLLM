package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.IOmniStreamCallback
import ai.omnillm.api.IStreamSession
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniEvent
import ai.omnillm.api.OmniRequestState
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.requestregistry.ClaimOutcome
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIDL [IStreamSession] host (specs/aidl IStreamSession + ANDROID-BINDER §2/§4).
 *
 * Wire methods (formal YAML projection):
 * - [ackEvents] — application ACK half-open `[…, seqToExclusive)`
 * - [cancel] — durable cancel via CommandRequest claim-or-return
 * - [query] — durable request state (reply-loss path)
 * - [close] — closeObserver; detach callback, do not auto-cancel
 *
 * [grantCredit] is the ANDROID-BINDER credit API: formal AIDL wire omits it on
 * IStreamSession (YAML wins), so clients free window via ACK; same-process
 * runtime / SDK shims may still call [grantCredit] for explicit window growth.
 */
class StreamSessionFacade(
    private val requestId: String,
    private val principalId: PrincipalId,
    private val registrationHandle: String,
    private val boundUid: Int,
    callback: IOmniStreamCallback,
    executor: Executor,
    private val onClosed: (StreamSessionFacade) -> Unit = {},
    private val onObserverDeathCancel: Boolean = true,
) : IStreamSession.Stub() {

    private val credit = StreamCreditWindow(ownerId = requestId)
    private val cancelRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val delivery = StreamDeliveryEngine(
        requestId = requestId,
        credit = credit,
        callback = callback,
        executor = executor,
        onObserverDeath = {
            if (onObserverDeathCancel && !cancelRequested.get()) {
                // Policy: callback death cancels in-flight stream (ANDROID-BINDER §4).
                softCancelOnDeath()
            }
            markClosed()
        },
    )

    val requestIdValue: String get() = requestId

    fun isClosed(): Boolean = closed.get()

    fun creditSnapshot(): StreamCreditSnapshot = delivery.creditSnapshot()

    /**
     * Explicit credit grant (ANDROID-BINDER grantCredit(window)).
     * Not on formal IStreamSession AIDL — exposed for runtime/SDK same-process use.
     */
    fun grantCredit(eventCredit: Long, byteCredit: Long = 0L, epoch: Long? = null): GrantResult {
        rejectIfPrincipalDrift()
        return delivery.grantCredit(eventCredit, byteCredit, epoch)
    }

    fun enqueue(events: List<OmniEvent>) = delivery.enqueue(events)

    fun enqueueTerminal(
        terminalState: String,
        error: ai.omnillm.api.OmniError? = null,
        actualModelRevisionId: String? = null,
        engineBuildId: String? = null,
        backend: String? = null,
    ) = delivery.enqueueTerminal(
        terminalState = terminalState,
        error = error,
        actualModelRevisionId = actualModelRevisionId,
        engineBuildId = engineBuildId,
        backend = backend,
    )

    fun reject(error: ai.omnillm.api.OmniError) = delivery.reject(error)

    override fun ackEvents(streamEpoch: Long, seqToExclusive: Long) {
        rejectIfPrincipalDrift()
        if (closed.get()) return
        // Idempotent ACK; invalid beyond-sent is ignored at transport (no throw).
        delivery.ack(streamEpoch, seqToExclusive)
    }

    override fun cancel(command: OmniCommandRequest?): CommandResult {
        rejectIfPrincipalDrift()
        val plane = RuntimeControlPlane.get()
            ?: return BinderErrors.failedCommand(
                commandId = command?.commandId.orEmpty(),
                code = OmniErrorCode.INTERNAL,
                message = "control plane not attached",
            )
        val cmd = command
            ?: return BinderErrors.failedCommand(
                commandId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "cancel requires OmniCommandRequest",
            )
        if (cmd.commandId.isNullOrBlank() || cmd.idempotencyKey.isNullOrBlank()) {
            return BinderErrors.failedCommand(
                commandId = cmd.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId and idempotencyKey are required",
            )
        }
        if (cmd.canonicalInputDigest.isNullOrBlank()) {
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "canonicalInputDigest is required",
            )
        }

        val commandId = try {
            CommandId.parse(cmd.commandId)
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "commandId must be a UUID",
            )
        }
        val idem = try {
            IdempotencyKey.parse(cmd.idempotencyKey)
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "invalid idempotencyKey",
            )
        }
        val digest = try {
            Sha256Digest.parse(cmd.canonicalInputDigest.lowercase())
        } catch (_: IllegalArgumentException) {
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "canonicalInputDigest must be sha256 hex",
            )
        }

        // Claim cancel command before side effects (ADR-004/005).
        val claim = plane.commandLedger.claim(
            principal = principalId,
            operationKind = OP_CANCEL_REQUEST,
            idempotencyKey = idem,
            canonicalHash = digest,
            commandId = commandId,
            expectedVersion = if (cmd.hasExpectedVersion) cmd.expectedVersion else null,
        )
        when (claim) {
            is ClaimOutcome.Conflict ->
                return BinderErrors.failedCommand(
                    commandId = cmd.commandId,
                    code = OmniErrorCode.IDEMPOTENCY_CONFLICT,
                    message = claim.error.message ?: "idempotency conflict",
                )
            is ClaimOutcome.Existing -> {
                val row = claim.value
                return commandResultFromRow(
                    commandId = row.commandId,
                    state = row.state,
                    resourceVersion = row.resourceVersion,
                    errorCode = row.errorCode,
                    affectedResourceId = row.affectedResourceId,
                )
            }
            is ClaimOutcome.New -> {
                // Proceed to cancel request.
            }
        }

        cancelRequested.set(true)
        val reqId = try {
            RequestId.parse(requestId)
        } catch (_: IllegalArgumentException) {
            plane.commandLedger.recordResult(
                commandId = commandId,
                state = "FAILED",
                errorCode = OmniErrorCode.INVALID_REQUEST.code,
                affectedResourceId = requestId,
            )
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "requestId is not a UUID",
            )
        }

        val existing = plane.requestRegistry.queryRequest(reqId)
        if (existing == null) {
            plane.commandLedger.recordResult(
                commandId = commandId,
                state = "FAILED",
                errorCode = OmniErrorCode.NOT_FOUND.code,
                affectedResourceId = requestId,
            )
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.NOT_FOUND,
                message = "request not found",
                details = mapOf("requestId" to requestId),
            )
        }
        if (existing.principalId != principalId.value) {
            plane.commandLedger.recordResult(
                commandId = commandId,
                state = "FAILED",
                errorCode = OmniErrorCode.FORBIDDEN.code,
                affectedResourceId = requestId,
            )
            return BinderErrors.failedCommand(
                commandId = cmd.commandId,
                code = OmniErrorCode.FORBIDDEN,
                message = "not owner of request",
            )
        }

        // Soft cancel: mark durable terminal when not already terminal.
        val terminal = plane.requestRegistry.queryRequestTerminal(reqId)
        if (terminal == null) {
            plane.requestRegistry.recordTerminal(
                requestId = reqId,
                terminalState = "CANCELLED",
                terminalSeq = System.nanoTime(),
                errorCode = OmniErrorCode.CANCELLED.code,
            )
            enqueueTerminal(
                terminalState = "CANCELLED",
                error = BinderErrors.omniError(
                    OmniErrorCode.CANCELLED,
                    "cancelled by client",
                    mapOf("requestId" to requestId),
                ),
            )
        }

        val recorded = plane.commandLedger.recordResult(
            commandId = commandId,
            state = "SUCCEEDED",
            affectedResourceId = requestId,
        )
        val version = when (recorded) {
            is com.omnillm.core.canonical.generated.OmniResult.Ok -> recorded.value.resourceVersion
            else -> 0L
        }
        return CommandResult().apply {
            this.commandId = cmd.commandId
            this.state = "SUCCEEDED"
            this.resourceVersion = version
            this.affectedResourceId = requestId
            this.resultSchemaId = null
            this.resultCanonicalJson = null
            this.error = null
        }
    }

    override fun query(): OmniRequestState {
        rejectIfPrincipalDrift()
        return queryState()
    }

    override fun close() {
        // closeObserver — detach only; durable request remains queryable.
        rejectIfPrincipalDrift()
        delivery.closeObserver()
        markClosed()
    }

    fun queryState(): OmniRequestState {
        val state = OmniRequestState()
        state.requestId = requestId
        val plane = RuntimeControlPlane.get()
        if (plane == null) {
            state.state = "FAILED"
            state.resourceVersion = 0L
            state.terminalError = BinderErrors.internal("control plane not attached")
            return state
        }
        val reqId = try {
            RequestId.parse(requestId)
        } catch (_: IllegalArgumentException) {
            state.state = "FAILED"
            state.resourceVersion = 0L
            state.terminalError = BinderErrors.omniError(
                OmniErrorCode.INVALID_REQUEST,
                "requestId is not a UUID",
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
        if (row.principalId != principalId.value) {
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

    private fun softCancelOnDeath() {
        val plane = RuntimeControlPlane.get() ?: return
        val reqId = try {
            RequestId.parse(requestId)
        } catch (_: IllegalArgumentException) {
            return
        }
        if (plane.requestRegistry.queryRequestTerminal(reqId) != null) return
        plane.requestRegistry.recordTerminal(
            requestId = reqId,
            terminalState = "CANCELLED",
            terminalSeq = System.nanoTime(),
            errorCode = OmniErrorCode.STREAM_INTERRUPTED.code,
        )
    }

    private fun markClosed() {
        if (closed.compareAndSet(false, true)) {
            onClosed(this)
        }
    }

    private fun rejectIfPrincipalDrift() {
        // Binder identity for session control must match registration-bound UID.
        val uid = android.os.Binder.getCallingUid()
        // Local same-process calls (e.g. tests) may use our UID; allow host process.
        if (uid != boundUid && uid != android.os.Process.myUid()) {
            throw SecurityException("principal UID drift on IStreamSession")
        }
    }

    private fun commandResultFromRow(
        commandId: String,
        state: String,
        resourceVersion: Long,
        errorCode: String?,
        affectedResourceId: String?,
    ): CommandResult {
        val result = CommandResult()
        result.commandId = commandId
        result.state = state
        result.resourceVersion = resourceVersion
        result.affectedResourceId = affectedResourceId
        result.resultSchemaId = null
        result.resultCanonicalJson = null
        if (errorCode != null) {
            val code = OmniErrorCode.fromCode(errorCode) ?: OmniErrorCode.INTERNAL
            result.error = BinderErrors.omniError(code, "prior command result")
        } else {
            result.error = null
        }
        return result
    }

    companion object {
        const val OP_CANCEL_REQUEST: String = "CANCEL_REQUEST"

        /** Canonical digest helper for cancel payloads (client should compute; tests use this). */
        fun cancelInputDigest(requestId: String): Sha256Digest =
            Sha256Digest.parse(
                IdentityHashing.sha256Hex("""{"op":"CANCEL_REQUEST","requestId":"$requestId"}"""),
            )
    }
}
