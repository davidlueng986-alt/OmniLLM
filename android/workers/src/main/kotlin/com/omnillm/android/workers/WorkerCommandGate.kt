package com.omnillm.android.workers

/**
 * Pure fail-closed gate for worker commands (no I/O).
 * Unknown placement / stale epoch / unattached supervisor ⇒ reject.
 */
object WorkerCommandGate {

    fun gate(
        command: WorkerCommand,
        fence: SupervisorFenceView,
        claimedNonces: Set<String>,
    ): WorkerCommandResult? {
        // Attach is the only command allowed before fence is accepting.
        if (command is WorkerCommand.AttachSupervisor) {
            if (!WorkerPlacement.isAllowed(command.placementClass)) {
                return WorkerCommandResult.Rejected(
                    errorCode = "TRUST_PLACEMENT_REQUIRED",
                    message = "placement ${command.placementClass} not allowed in same-UID worker",
                )
            }
            return null // proceed to attach
        }

        if (fence.isFenced) {
            return WorkerCommandResult.Rejected(
                errorCode = "WORKER_DIED",
                message = "worker fenced after supervisor death",
            )
        }
        if (!fence.isAccepting) {
            return WorkerCommandResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "worker not accepting commands",
            )
        }

        val epochCheck = fence.validateEpoch(command.runtimeEpoch, command.bootId)
        if (!epochCheck.isOk) {
            return WorkerCommandResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "epoch fence failed: $epochCheck",
            )
        }

        when (command) {
            is WorkerCommand.CommitLoad -> {
                if (!WorkerPlacement.isAllowed(command.placementClass)) {
                    return WorkerCommandResult.Rejected(
                        errorCode = "TRUST_PLACEMENT_REQUIRED",
                        message = "placement ${command.placementClass} not allowed in same-UID worker",
                    )
                }
                if (command.oneShotNonce in claimedNonces) {
                    // Reply-loss path: runtime must query journal, not re-execute.
                    return WorkerCommandResult.Rejected(
                        errorCode = "IDEMPOTENCY_CONFLICT",
                        message = "one-shot nonce already claimed; query journal",
                    )
                }
                if (command.contentFdTokens.isEmpty()) {
                    return WorkerCommandResult.Rejected(
                        errorCode = "INVALID_REQUEST",
                        message = "contentFdTokens required for commitLoad",
                    )
                }
            }
            is WorkerCommand.Cancel,
            is WorkerCommand.QueryJournal,
            is WorkerCommand.DrainAndExit,
            is WorkerCommand.AttachSupervisor,
            -> Unit
        }
        return null
    }
}

/**
 * Read-only view of [SupervisorFence] for pure tests without Android binder.
 */
interface SupervisorFenceView {
    val isAccepting: Boolean
    val isFenced: Boolean
    fun validateEpoch(commandRuntimeEpoch: Long, commandBootId: String): EpochCheck
}

/** In-memory fence view for unit tests / pure gates. */
class FakeSupervisorFenceView(
    override var isAccepting: Boolean = true,
    override var isFenced: Boolean = false,
    var expectedEpoch: Long = 1L,
    var expectedBootId: String = "boot-1",
) : SupervisorFenceView {
    override fun validateEpoch(commandRuntimeEpoch: Long, commandBootId: String): EpochCheck {
        if (isFenced || !isAccepting) return EpochCheck.Fenced
        if (commandBootId != expectedBootId) return EpochCheck.BootMismatch
        if (commandRuntimeEpoch != expectedEpoch) return EpochCheck.EpochMismatch
        return EpochCheck.Ok
    }
}
