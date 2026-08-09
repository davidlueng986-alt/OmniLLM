package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.ports.security.InMemoryRevocationEpochStore
import com.omnillm.core.ports.security.RevocationEpochStore
import com.omnillm.core.ports.security.RevocationRecord
import com.omnillm.core.ports.security.RevocationScope
import com.omnillm.core.ports.security.RevocationSubjectKind
import com.omnillm.core.ports.security.storageKey
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines

/**
 * Revocation-scope / record / store port types live in `:core:ports` (ARC-01);
 * SQLite adapters implement [RevocationEpochStore] in `:data:persistence`.
 */

/**
 * Hooks invoked during fencing (INV-017). Runtime wires cancel/queue/session
 * poison implementations; pure policy only sequences the fence.
 */
interface RevocationFenceHooks {
    /** Fail new operations that still carry [oldEpoch]. */
    fun rejectNewUse(scope: RevocationScope, oldEpoch: Long, newEpoch: Long)

    /** Cancel queued work, cancel/drain active work, poison owned sessions. */
    fun cancelAndDrain(scope: RevocationScope, epoch: Long)

    /** Rotate token/cert/broker capability where applicable. */
    fun rotateSecrets(scope: RevocationScope, epoch: Long)

    /** Append audit evidence (actor, target, reason). */
    fun audit(scope: RevocationScope, event: String, details: Map<String, String>)

    /** Whether any old-epoch capability remains usable. */
    fun hasOldCapability(scope: RevocationScope, oldEpoch: Long): Boolean

    companion object {
        /** No-op hooks for unit tests of epoch arithmetic / FSM only. */
        val NOOP: RevocationFenceHooks = object : RevocationFenceHooks {
            override fun rejectNewUse(scope: RevocationScope, oldEpoch: Long, newEpoch: Long) = Unit
            override fun cancelAndDrain(scope: RevocationScope, epoch: Long) = Unit
            override fun rotateSecrets(scope: RevocationScope, epoch: Long) = Unit
            override fun audit(scope: RevocationScope, event: String, details: Map<String, String>) = Unit
            override fun hasOldCapability(scope: RevocationScope, oldEpoch: Long): Boolean = false
        }
    }
}

/**
 * Per-subject revocation epoch controller.
 * Thread-safe for control-plane single-writer usage.
 * Production injects SQLite-backed [RevocationEpochStore] so fences survive restart.
 */
class RevocationEpochManager(
    private val hooks: RevocationFenceHooks = RevocationFenceHooks.NOOP,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val store: RevocationEpochStore = InMemoryRevocationEpochStore(),
) {
    private fun key(scope: RevocationScope): String = scope.storageKey()

    @Synchronized
    fun currentEpoch(scope: RevocationScope): Long =
        store.get(key(scope))?.epoch ?: 0L

    @Synchronized
    fun get(scope: RevocationScope): RevocationRecord? = store.get(key(scope))

    fun epochStore(): RevocationEpochStore = store

    /**
     * Ensure subject is tracked at epoch 0 / ACTIVE.
     */
    @Synchronized
    fun ensureActive(scope: RevocationScope): RevocationRecord {
        val k = key(scope)
        val existing = store.get(k)
        if (existing != null) return existing
        val created = RevocationRecord(
            scope = scope,
            epoch = 0L,
            state = StateMachines.REVOCATION.initial,
            reason = null,
            actorPrincipalId = null,
            updatedAtEpochMs = clock(),
        )
        store.upsert(created)
        return created
    }

    /**
     * Request revocation (ACTIVE → REVOCATION_REQUESTED) when authorised.
     * Does **not** bump epoch yet — epoch advances on [commitEpoch].
     */
    @Synchronized
    fun requestRevoke(
        scope: RevocationScope,
        actorPrincipalId: PrincipalId,
        reason: String,
        authorised: Boolean,
    ): OmniResult<RevocationRecord> {
        val current = ensureActive(scope)
        if (current.state != "ACTIVE") {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "revocation not idle",
                    details = mapOf("state" to current.state),
                ),
            )
        }
        val guards = GuardEvaluator.of("requestAuthorised" to authorised)
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.REVOCATION,
                current.state,
                "REVOKE",
                guards,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                hooks.audit(
                    scope,
                    "REVOKE",
                    mapOf(
                        "actor" to actorPrincipalId.value,
                        "reason" to reason,
                        "epoch" to current.epoch.toString(),
                    ),
                )
                val next = current.copy(
                    state = step.to,
                    reason = reason,
                    actorPrincipalId = actorPrincipalId.value,
                    updatedAtEpochMs = clock(),
                )
                store.upsert(next)
                OmniResult.ok(next)
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "revocation not authorised or illegal: ${step.reason}",
                    ),
                )
        }
    }

    /**
     * Durably advance epoch and enter FENCING (REV-002).
     * Monotonic: newEpoch = oldEpoch + 1.
     */
    @Synchronized
    fun commitEpoch(scope: RevocationScope): OmniResult<RevocationRecord> {
        val current = store.get(key(scope))
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "unknown revocation subject"))
        if (current.state != "REVOCATION_REQUESTED") {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "epoch commit requires REVOCATION_REQUESTED",
                    details = mapOf("state" to current.state),
                ),
            )
        }
        val oldEpoch = current.epoch
        val newEpoch = oldEpoch + 1L
        val guards = GuardEvaluator.of("epochAdvanced" to (newEpoch > oldEpoch))
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.REVOCATION,
                current.state,
                "EPOCH_COMMITTED",
                guards,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                hooks.rejectNewUse(scope, oldEpoch, newEpoch)
                val next = current.copy(
                    epoch = newEpoch,
                    state = step.to,
                    updatedAtEpochMs = clock(),
                )
                store.upsert(next)
                hooks.audit(
                    scope,
                    "EPOCH_COMMITTED",
                    mapOf("oldEpoch" to oldEpoch.toString(), "newEpoch" to newEpoch.toString()),
                )
                OmniResult.ok(next)
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "epoch commit rejected: ${step.reason}"),
                )
        }
    }

    /** FENCING → DRAINING (REV-003). */
    @Synchronized
    fun beginDrain(scope: RevocationScope): OmniResult<RevocationRecord> {
        val current = store.get(key(scope))
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "unknown revocation subject"))
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.REVOCATION,
                current.state,
                "BEGIN_DRAIN",
                GuardEvaluator.ALWAYS_TRUE,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                hooks.cancelAndDrain(scope, current.epoch)
                val next = current.copy(state = step.to, updatedAtEpochMs = clock())
                store.upsert(next)
                OmniResult.ok(next)
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "beginDrain rejected: ${step.reason}"),
                )
        }
    }

    /** DRAINING → ROTATING_SECRETS when no old capability remains (REV-004). */
    @Synchronized
    fun completeDrain(scope: RevocationScope): OmniResult<RevocationRecord> {
        val current = store.get(key(scope))
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "unknown revocation subject"))
        val oldEpoch = current.epoch - 1
        val noOld = !hooks.hasOldCapability(scope, oldEpoch.coerceAtLeast(0L))
        val guards = GuardEvaluator.of("noOldCapability" to noOld)
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.REVOCATION,
                current.state,
                "DRAIN_COMPLETE",
                guards,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                hooks.rotateSecrets(scope, current.epoch)
                val next = current.copy(state = step.to, updatedAtEpochMs = clock())
                store.upsert(next)
                OmniResult.ok(next)
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "drain complete rejected: ${step.reason}",
                        details = mapOf("noOldCapability" to noOld.toString()),
                    ),
                )
        }
    }

    /** ROTATING_SECRETS → ENFORCED (REV-005). */
    @Synchronized
    fun completeRotation(scope: RevocationScope): OmniResult<RevocationRecord> {
        val current = store.get(key(scope))
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "unknown revocation subject"))
        val oldEpoch = (current.epoch - 1).coerceAtLeast(0L)
        val noOld = !hooks.hasOldCapability(scope, oldEpoch)
        val guards = GuardEvaluator.of("noOldCapability" to noOld)
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.REVOCATION,
                current.state,
                "ROTATION_COMPLETE",
                guards,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                hooks.audit(
                    scope,
                    "ROTATION_COMPLETE",
                    mapOf("epoch" to current.epoch.toString()),
                )
                val next = current.copy(state = step.to, updatedAtEpochMs = clock())
                store.upsert(next)
                OmniResult.ok(next)
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "rotation complete rejected: ${step.reason}"),
                )
        }
    }

    /**
     * Full revoke → epoch bump → fence → drain → rotate → enforced pipeline.
     * Convenience for control-plane command handlers.
     */
    @Synchronized
    fun revokeAndFence(
        scope: RevocationScope,
        actorPrincipalId: PrincipalId,
        reason: String,
        authorised: Boolean = true,
    ): OmniResult<RevocationRecord> {
        val r1 = requestRevoke(scope, actorPrincipalId, reason, authorised)
        if (r1 is OmniResult.Err) return r1
        val r2 = commitEpoch(scope)
        if (r2 is OmniResult.Err) return r2
        val r3 = beginDrain(scope)
        if (r3 is OmniResult.Err) return r3
        val r4 = completeDrain(scope)
        if (r4 is OmniResult.Err) return r4
        return completeRotation(scope)
    }

    /**
     * Fail closed when a request carries a stale epoch
     * (ARCH-RUNTIME-LIFECYCLE / runtime-recovery-fixtures).
     */
    fun requireCurrentEpoch(scope: RevocationScope, observedEpoch: Long): OmniResult<Unit> {
        val current = currentEpoch(scope)
        if (observedEpoch != current) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "stale revocation epoch",
                    details = mapOf(
                        "observed" to observedEpoch.toString(),
                        "current" to current.toString(),
                        "subject" to scope.subjectId,
                    ),
                ),
            )
        }
        return OmniResult.ok(Unit)
    }
}
