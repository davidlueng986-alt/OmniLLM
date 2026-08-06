package com.omnillm.companion

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Supervisor binder + runtimeEpoch fencing for companion (ARCH-TRUST-TOPOLOGY §3,
 * SEC-EXTERNAL-SANDBOX §6).
 *
 * On supervisor death:
 * 1. Stop accepting new work
 * 2. Fence outputs
 * 3. Clear request-scoped data
 * 4. Self-terminate process
 */
class CompanionSupervisorFence(
    private val onSupervisorDied: () -> Unit,
) {
    private val accepting = AtomicBoolean(false)
    private val fenced = AtomicBoolean(false)
    private val runtimeEpoch = AtomicLong(-1L)
    private val bootId = AtomicReference<String?>(null)
    private val runtimeInstanceId = AtomicReference<String?>(null)
    private val supervisor = AtomicReference<IBinder?>(null)

    private val deathRecipient = IBinder.DeathRecipient {
        fenceAndNotify()
    }

    val isAccepting: Boolean get() = accepting.get() && !fenced.get()
    val isFenced: Boolean get() = fenced.get()
    val currentRuntimeEpoch: Long get() = runtimeEpoch.get()
    val currentBootId: String? get() = bootId.get()
    val currentRuntimeInstanceId: String? get() = runtimeInstanceId.get()

    fun attach(
        supervisorBinder: IBinder,
        epoch: Long,
        issuerBootId: String,
        instanceId: String,
    ): CompanionFenceAttachResult {
        require(epoch >= 0L)
        require(issuerBootId.isNotEmpty())
        require(instanceId.isNotEmpty())
        if (fenced.get()) return CompanionFenceAttachResult.AlreadyFenced
        val previous = supervisor.getAndSet(supervisorBinder)
        previous?.let { unlinkQuietly(it) }
        return try {
            supervisorBinder.linkToDeath(deathRecipient, 0)
            runtimeEpoch.set(epoch)
            bootId.set(issuerBootId)
            runtimeInstanceId.set(instanceId)
            accepting.set(true)
            CompanionFenceAttachResult.Attached
        } catch (_: RemoteException) {
            fenceAndNotify()
            CompanionFenceAttachResult.SupervisorAlreadyDead
        }
    }

    fun validateEpoch(commandRuntimeEpoch: Long, commandBootId: String): Boolean {
        if (fenced.get() || !accepting.get()) return false
        val expectedEpoch = runtimeEpoch.get()
        val expectedBoot = bootId.get()
        if (expectedBoot == null || expectedEpoch < 0L) return false
        if (commandBootId != expectedBoot) return false
        if (commandRuntimeEpoch != expectedEpoch) return false
        return true
    }

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

sealed class CompanionFenceAttachResult {
    data object Attached : CompanionFenceAttachResult()
    data object AlreadyFenced : CompanionFenceAttachResult()
    data object SupervisorAlreadyDead : CompanionFenceAttachResult()
}
