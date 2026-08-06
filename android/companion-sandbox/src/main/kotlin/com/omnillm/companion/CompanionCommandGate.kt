package com.omnillm.companion

/**
 * Pure fail-closed gate for companion commands (no I/O).
 * Unknown placement/path tokens / stale epoch / unattached supervisor ⇒ reject.
 */
object CompanionCommandGate {

    fun gate(
        command: CompanionCommand,
        session: CompanionSessionView,
        claimedNonces: Set<String>,
        nowMonotonicMs: Long,
    ): CompanionCommandResult? {
        if (session.isFenced || session.isExiting) {
            return CompanionCommandResult.Rejected(
                errorCode = "WORKER_DIED",
                message = "companion fenced after supervisor death",
            )
        }

        if (command is CompanionCommand.Handshake) {
            if (!session.isAcceptingHandshake) {
                return CompanionCommandResult.Rejected(
                    errorCode = "INVALID_REQUEST",
                    message = "handshake not accepted in current state",
                )
            }
            return when (
                val v = CompanionTicketValidator.validate(
                    ticket = command.ticket,
                    expectedRuntimeEpoch = command.runtimeEpoch,
                    expectedBootId = command.bootId,
                    expectedRuntimeInstanceId = command.runtimeInstanceId,
                    claimedNonces = claimedNonces,
                    nowMonotonicMs = nowMonotonicMs,
                )
            ) {
                is TicketValidationResult.Accepted -> null
                is TicketValidationResult.Rejected ->
                    CompanionCommandResult.Rejected(v.errorCode, v.message)
            }
        }

        if (!session.isBound) {
            return CompanionCommandResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "handshake required before command",
            )
        }

        val epochCheck = session.validateEpoch(command.runtimeEpoch, command.bootId)
        if (!epochCheck) {
            return CompanionCommandResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "epoch fence failed",
            )
        }

        when (command) {
            is CompanionCommand.RegisterReadOnlyFd -> {
                if (!CompanionFdPolicy.acceptMode(command.mode)) {
                    return CompanionCommandResult.Rejected(
                        errorCode = "INVALID_REQUEST",
                        message = "only READ_ONLY PFD mode accepted",
                    )
                }
                CompanionFdPolicy.rejectReasonForToken(command.token)?.let { reason ->
                    return CompanionCommandResult.Rejected(
                        errorCode = "INVALID_REQUEST",
                        message = reason,
                    )
                }
            }
            is CompanionCommand.Load -> {
                if (command.contentFdTokens.isEmpty()) {
                    return CompanionCommandResult.Rejected(
                        errorCode = "INVALID_REQUEST",
                        message = "contentFdTokens required",
                    )
                }
                for (token in command.contentFdTokens) {
                    CompanionFdPolicy.rejectReasonForToken(token)?.let { reason ->
                        return CompanionCommandResult.Rejected(
                            errorCode = "INVALID_REQUEST",
                            message = reason,
                        )
                    }
                }
            }
            is CompanionCommand.Cancel,
            is CompanionCommand.Query,
            is CompanionCommand.Close,
            is CompanionCommand.Handshake,
            -> Unit
        }
        return null
    }
}

/**
 * Read-only session view for pure tests without Android binder.
 */
interface CompanionSessionView {
    val isAcceptingHandshake: Boolean
    val isBound: Boolean
    val isFenced: Boolean
    val isExiting: Boolean
    fun validateEpoch(runtimeEpoch: Long, bootId: String): Boolean
}

class FakeCompanionSessionView(
    override var isAcceptingHandshake: Boolean = true,
    override var isBound: Boolean = false,
    override var isFenced: Boolean = false,
    override var isExiting: Boolean = false,
    var expectedEpoch: Long = 1L,
    var expectedBootId: String = "boot-1",
) : CompanionSessionView {
    override fun validateEpoch(runtimeEpoch: Long, bootId: String): Boolean {
        if (isFenced || isExiting) return false
        return runtimeEpoch == expectedEpoch && bootId == expectedBootId
    }
}
