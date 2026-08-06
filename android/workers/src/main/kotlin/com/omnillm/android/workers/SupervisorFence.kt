package com.omnillm.android.workers

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Supervisor binder + runtimeEpoch fencing (ARCH-TRUST-TOPOLOGY §3).
 *
 * On supervisor death:
 * 1. Stop accepting new commands
 * 2. Cancel cooperative operations
 * 3. Emit terminal / journal frame within short deadline
 * 4. Close session/model handles
 * 5. Exit process
 *
 * All plan/lease/commit/operation/callback validation must check [runtimeEpoch]
 * and principal revocation epoch; stale epochs fail closed.
 */
class SupervisorFence(
    private val onSupervisorDied: () -> Unit,
) {
    private val accepting = AtomicBoolean(false)
    private val fenced = AtomicBoolean(false)
    private val runtimeEpoch = AtomicLong(-1L)
    private val bootId = AtomicReference<String?>(null)
    private val supervisor = AtomicReference<IBinder?>(null)
    private val deathRecipient = IBinder.DeathRecipient {
        fenceAndNotify()
    }

    val isAccepting: Boolean get() = accepting.get() && !fenced.get()
    val isFenced: Boolean get() = fenced.get()
    val currentRuntimeEpoch: Long get() = runtimeEpoch.get()
    val currentBootId: String? get() = bootId.get()

    /**
     * Attach the unique supervisor binder for this worker instance.
     * Replaces any previous link; fails if already fenced.
     */
    fun attach(
        supervisorBinder: IBinder,
        epoch: Long,
        issuerBootId: String,
    ): FenceAttachResult {
        require(epoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(issuerBootId.isNotEmpty()) { "bootId must be non-empty" }
        if (fenced.get()) {
            return FenceAttachResult.AlreadyFenced
        }
        val previous = supervisor.getAndSet(supervisorBinder)
        previous?.let { unlinkQuietly(it) }
        return try {
            supervisorBinder.linkToDeath(deathRecipient, 0)
            runtimeEpoch.set(epoch)
            bootId.set(issuerBootId)
            accepting.set(true)
            FenceAttachResult.Attached
        } catch (_: RemoteException) {
            fenceAndNotify()
            FenceAttachResult.SupervisorAlreadyDead
        }
    }

    /**
     * Validate an inbound command's epoch fencing.
     * Unknown / stale epoch ⇒ fail closed (INV-018 / ARCH-TRUST-TOPOLOGY §3).
     */
    fun validateEpoch(commandRuntimeEpoch: Long, commandBootId: String): EpochCheck {
        if (fenced.get() || !accepting.get()) {
            return EpochCheck.Fenced
        }
        val expectedEpoch = runtimeEpoch.get()
        val expectedBoot = bootId.get()
        if (expectedBoot == null || expectedEpoch < 0L) {
            return EpochCheck.NotAttached
        }
        if (commandBootId != expectedBoot) {
            return EpochCheck.BootMismatch
        }
        if (commandRuntimeEpoch != expectedEpoch) {
            return EpochCheck.EpochMismatch
        }
        return EpochCheck.Ok
    }

    /** Cooperative stop accepting without process exit (e.g. runtime drain). */
    fun stopAccepting() {
        accepting.set(false)
    }

    fun detach() {
        accepting.set(false)
        val binder = supervisor.getAndSet(null)
        binder?.let { unlinkQuietly(it) }
    }

    private fun fenceAndNotify() {
        if (!fenced.compareAndSet(false, true)) return
        accepting.set(false)
        val binder = supervisor.getAndSet(null)
        binder?.let { unlinkQuietly(it) }
        onSupervisorDied()
    }

    private fun unlinkQuietly(binder: IBinder) {
        try {
            binder.unlinkToDeath(deathRecipient, 0)
        } catch (_: Exception) {
            // already unlinked or dead
        }
    }
}

sealed class FenceAttachResult {
    data object Attached : FenceAttachResult()
    data object AlreadyFenced : FenceAttachResult()
    data object SupervisorAlreadyDead : FenceAttachResult()
}

sealed class EpochCheck {
    data object Ok : EpochCheck()
    data object Fenced : EpochCheck()
    data object NotAttached : EpochCheck()
    data object BootMismatch : EpochCheck()
    data object EpochMismatch : EpochCheck()

    val isOk: Boolean get() = this is Ok
}
