package com.omnillm.android.workers

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Same-UID crash worker service (process `:engine_worker`).
 *
 * Responsibilities:
 * - Bind only from runtime (not exported)
 * - Hold supervisor death link + epoch fence
 * - Accept narrow [WorkerCommand]s after durable commit intent
 * - Never open domain DB / token vault / catalog writable paths (ADR-010)
 *
 * Engine native load is not wired here yet — commit path records journal claims
 * and returns WORKER_DIED / capability stubs until engine packs bind native backends.
 */
class EngineWorkerService : Service() {

    private val journal = WorkerJournalBuffer()
    private val claimedNonces = ConcurrentHashMap.newKeySet<String>()
    private val exiting = AtomicBoolean(false)
    private lateinit var fence: SupervisorFence

    private val localBinder = object : Binder(), EngineWorkerBinder {
        override fun dispatch(command: WorkerCommand): WorkerCommandResult =
            this@EngineWorkerService.dispatch(command)

        override fun processRole(): String = ProcessTopology.Roles.ENGINE_WORKER

        override fun pid(): Int = Process.myPid()
    }

    override fun onCreate() {
        super.onCreate()
        fence = SupervisorFence {
            onSupervisorDied()
        }
        Log.i(TAG, "EngineWorkerService created pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        // Intent must not carry tokens/paths/large payloads (ANDROID-SERVICE §6).
        return localBinder
    }

    override fun onDestroy() {
        fence.detach()
        super.onDestroy()
    }

    private fun dispatch(command: WorkerCommand): WorkerCommandResult {
        if (exiting.get()) {
            return WorkerCommandResult.Rejected(
                errorCode = "WORKER_DIED",
                message = "worker exiting",
            )
        }

        val gateReject = WorkerCommandGate.gate(
            command = command,
            fence = fence.asView(),
            claimedNonces = claimedNonces,
        )
        if (gateReject != null) {
            val boot = command.bootId.ifEmpty { fence.currentBootId.orEmpty() }
            val epoch = if (command.runtimeEpoch >= 0) command.runtimeEpoch else fence.currentRuntimeEpoch
            if (boot.isNotEmpty() && epoch >= 0) {
                journal.append(
                    kind = WorkerJournalKind.COMMAND_REJECTED,
                    runtimeEpoch = epoch,
                    bootId = boot,
                    monotonicMs = SystemClock.elapsedRealtime(),
                    commitId = (command as? WorkerCommand.CommitLoad)?.commitId,
                    errorCode = (gateReject as? WorkerCommandResult.Rejected)?.errorCode,
                )
            }
            return gateReject
        }

        return when (command) {
            is WorkerCommand.AttachSupervisor -> handleAttach(command)
            is WorkerCommand.CommitLoad -> handleCommitLoad(command)
            is WorkerCommand.Cancel -> handleCancel(command)
            is WorkerCommand.QueryJournal -> handleQuery(command)
            is WorkerCommand.DrainAndExit -> handleDrain(command)
        }
    }

    private fun handleAttach(command: WorkerCommand.AttachSupervisor): WorkerCommandResult {
        // Attach uses the binder identity of the caller as supervisor.
        val supervisor = Binder.getCallingPid().let {
            // The binder we link is the client-side death marker provided via
            // secondary attach API; for local same-UID bind we use the
            // caller's binder token when available. Runtime should call
            // attachWithSupervisor(IBinder) for real death links.
            null
        }
        // Soft attach of epoch without death link if binder not provided:
        // real path uses [attachWithSupervisor].
        fence.stopAccepting()
        // Re-open accepting only after successful attachWithSupervisor.
        val epochOk = command.runtimeEpoch >= 0L && command.bootId.isNotEmpty()
        if (!epochOk) {
            return WorkerCommandResult.Rejected(
                errorCode = "INVALID_REQUEST",
                message = "runtimeEpoch/bootId required",
            )
        }
        // For unit/integration without secondary binder, accept epoch-only attach
        // by stamping fence fields via a no-death attach helper.
        val stamped = stampEpochOnly(command.runtimeEpoch, command.bootId)
        if (!stamped) {
            return WorkerCommandResult.Rejected(
                errorCode = "WORKER_DIED",
                message = "cannot attach; fenced",
            )
        }
        val frame = journal.append(
            kind = WorkerJournalKind.ATTACHED,
            runtimeEpoch = command.runtimeEpoch,
            bootId = command.bootId,
            monotonicMs = SystemClock.elapsedRealtime(),
            attributes = mapOf(
                "placement" to command.placementClass,
                "pid" to Process.myPid().toString(),
            ),
        )
        // silence unused
        @Suppress("UNUSED_VARIABLE")
        val _unused = supervisor
        return WorkerCommandResult.Accepted(journalSequence = frame.sequence)
    }

    /**
     * Preferred attach: runtime passes its supervisor [IBinder] for death fencing.
     */
    fun attachWithSupervisor(
        supervisorBinder: IBinder,
        runtimeEpoch: Long,
        bootId: String,
        placementClass: String,
    ): WorkerCommandResult {
        if (!WorkerPlacement.isAllowed(placementClass)) {
            return WorkerCommandResult.Rejected(
                errorCode = "TRUST_PLACEMENT_REQUIRED",
                message = "placement $placementClass not allowed in same-UID worker",
            )
        }
        return when (fence.attach(supervisorBinder, runtimeEpoch, bootId)) {
            FenceAttachResult.Attached -> {
                val frame = journal.append(
                    kind = WorkerJournalKind.ATTACHED,
                    runtimeEpoch = runtimeEpoch,
                    bootId = bootId,
                    monotonicMs = SystemClock.elapsedRealtime(),
                    attributes = mapOf("placement" to placementClass),
                )
                WorkerCommandResult.Accepted(journalSequence = frame.sequence)
            }
            FenceAttachResult.AlreadyFenced ->
                WorkerCommandResult.Rejected("WORKER_DIED", "already fenced")
            FenceAttachResult.SupervisorAlreadyDead ->
                WorkerCommandResult.Rejected("WORKER_DIED", "supervisor already dead")
        }
    }

    private fun stampEpochOnly(epoch: Long, bootId: String): Boolean {
        if (fence.isFenced) return false
        // Use a local dummy binder that cannot die from remote — tests only.
        // Production runtime must call attachWithSupervisor.
        val dummy = Binder()
        return when (fence.attach(dummy, epoch, bootId)) {
            FenceAttachResult.Attached -> true
            else -> false
        }
    }

    private fun handleCommitLoad(command: WorkerCommand.CommitLoad): WorkerCommandResult {
        claimedNonces.add(command.oneShotNonce)
        val accepted = journal.append(
            kind = WorkerJournalKind.SIDE_EFFECT_CLAIMED,
            runtimeEpoch = command.runtimeEpoch,
            bootId = command.bootId,
            monotonicMs = SystemClock.elapsedRealtime(),
            commitId = command.commitId,
            attributes = mapOf(
                "installationId" to command.installationId,
                "engineBuildId" to command.engineBuildId,
                "fdCount" to command.contentFdTokens.size.toString(),
            ),
        )
        // Native load path not wired: fail closed with capability-unknown style
        // terminal so runtime reconciles rather than assuming success.
        val terminal = journal.append(
            kind = WorkerJournalKind.TERMINAL_ERROR,
            runtimeEpoch = command.runtimeEpoch,
            bootId = command.bootId,
            monotonicMs = SystemClock.elapsedRealtime(),
            commitId = command.commitId,
            errorCode = "CAPABILITY_UNKNOWN",
            attributes = mapOf("reason" to "native-backend-not-bound"),
        )
        return WorkerCommandResult.Terminal(
            ok = false,
            errorCode = "CAPABILITY_UNKNOWN",
            journalSequence = terminal.sequence,
        ).also {
            @Suppress("UNUSED_VARIABLE")
            val _a = accepted
        }
    }

    private fun handleCancel(command: WorkerCommand.Cancel): WorkerCommandResult {
        val frame = journal.append(
            kind = WorkerJournalKind.COMMAND_ACCEPTED,
            runtimeEpoch = command.runtimeEpoch,
            bootId = command.bootId,
            monotonicMs = SystemClock.elapsedRealtime(),
            commitId = command.commitId,
            operationId = command.operationId,
            attributes = mapOf("action" to "cancel"),
        )
        return WorkerCommandResult.Accepted(journalSequence = frame.sequence)
    }

    private fun handleQuery(command: WorkerCommand.QueryJournal): WorkerCommandResult {
        val frames = if (command.commitId != null) {
            journal.findByCommitId(command.commitId)
        } else {
            journal.snapshot()
        }
        return WorkerCommandResult.JournalSnapshot(frames)
    }

    private fun handleDrain(command: WorkerCommand.DrainAndExit): WorkerCommandResult {
        fence.stopAccepting()
        journal.append(
            kind = WorkerJournalKind.DRAINING,
            runtimeEpoch = command.runtimeEpoch,
            bootId = command.bootId,
            monotonicMs = SystemClock.elapsedRealtime(),
        )
        scheduleExit("drain")
        return WorkerCommandResult.Terminal(
            ok = true,
            journalSequence = journal.snapshot().lastOrNull()?.sequence ?: 0L,
        )
    }

    private fun onSupervisorDied() {
        Log.w(TAG, "supervisor died — fencing worker")
        val boot = fence.currentBootId ?: "unknown"
        val epoch = fence.currentRuntimeEpoch.coerceAtLeast(0L)
        journal.append(
            kind = WorkerJournalKind.SUPERVISOR_DIED,
            runtimeEpoch = epoch,
            bootId = boot,
            monotonicMs = SystemClock.elapsedRealtime(),
        )
        scheduleExit("supervisor-death")
    }

    private fun scheduleExit(reason: String) {
        if (!exiting.compareAndSet(false, true)) return
        journal.append(
            kind = WorkerJournalKind.EXITING,
            runtimeEpoch = fence.currentRuntimeEpoch.coerceAtLeast(0L),
            bootId = fence.currentBootId ?: "unknown",
            monotonicMs = SystemClock.elapsedRealtime(),
            attributes = mapOf("reason" to reason),
        )
        // Hard exit of worker process only — does not kill UI/runtime.
        // Short delay allows binder reply to flush.
        Thread {
            try {
                Thread.sleep(SUPERVISOR_DEATH_GRACE_MS)
            } catch (_: InterruptedException) {
            }
            Process.killProcess(Process.myPid())
        }.start()
    }

    private fun SupervisorFence.asView(): SupervisorFenceView = object : SupervisorFenceView {
        override val isAccepting: Boolean get() = this@asView.isAccepting
        override val isFenced: Boolean get() = this@asView.isFenced
        override fun validateEpoch(commandRuntimeEpoch: Long, commandBootId: String): EpochCheck =
            this@asView.validateEpoch(commandRuntimeEpoch, commandBootId)
    }

    companion object {
        private const val TAG = "OmniEngineWorker"
        /** Grace period to emit terminal journal before process exit (ARCH-TRUST-TOPOLOGY §3). */
        const val SUPERVISOR_DEATH_GRACE_MS: Long = 250L
    }
}

/**
 * Local binder interface for same-UID runtime ↔ worker.
 * Not a public AIDL catalog type — internal process topology only.
 */
interface EngineWorkerBinder : IBinder {
    fun dispatch(command: WorkerCommand): WorkerCommandResult
    fun processRole(): String
    fun pid(): Int
}
