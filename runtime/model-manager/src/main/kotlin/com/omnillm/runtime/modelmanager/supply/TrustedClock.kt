package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.errors.generated.OmniError

/**
 * Trusted time record and grace state machine (SEC-SUPPLY §3).
 *
 * Grace policy is a **versioned state machine**, not scattered constants.
 * Remote metadata must not be extended by arbitrary local clock rollback.
 * First fully offline launch without a trusted anchor may only use the
 * non-expired APK-embedded root.
 */
enum class TrustedTimeState {
    /** No trusted anchor yet — embedded root only if unexpired. */
    NO_ANCHOR,

    /** Wall clock consistent with last trusted source within grace. */
    ANCHORED,

    /** Forward jump beyond max advance — hold remote until re-anchor. */
    FORWARD_UNCERTAIN,

    /** Backward jump / rollback suspected — remote cannot extend expiry. */
    ROLLBACK_UNCERTAIN,

    /** Explicitly invalidated (compromise / recovery). */
    INVALID,
}

enum class TrustedTimeSource {
    EMBEDDED_ROOT,
    SIGNED_TIMESTAMP_METADATA,
    PLATFORM_SECURE_TIME,
    MANUAL_RECOVERY,
}

data class TrustedClockRecord(
    val source: TrustedTimeSource,
    val wallTimeEpochMs: Long,
    val bootId: String,
    val elapsedRealtimeAnchorMs: Long,
    val maxForwardAdvanceMs: Long,
    val state: TrustedTimeState,
    /** Schema version of the grace state machine. */
    val gracePolicyVersion: Int = 1,
) {
    init {
        require(wallTimeEpochMs >= 0L)
        require(bootId.isNotEmpty())
        require(elapsedRealtimeAnchorMs >= 0L)
        require(maxForwardAdvanceMs > 0L)
        require(gracePolicyVersion >= 1)
    }
}

/**
 * Grace policy state machine (version 1).
 */
object TrustedClockGraceMachine {

    const val POLICY_VERSION: Int = 1

    sealed class Outcome {
        data class Accepted(val record: TrustedClockRecord) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    /**
     * Observe local wall + elapsed realtime and transition grace state.
     *
     * [observedWallEpochMs] and [observedElapsedRealtimeMs] are device readings.
     * [bootId] change resets elapsed anchor.
     */
    fun observe(
        previous: TrustedClockRecord?,
        observedWallEpochMs: Long,
        observedElapsedRealtimeMs: Long,
        bootId: String,
        maxForwardAdvanceMs: Long = 24L * 60L * 60L * 1000L,
        source: TrustedTimeSource = TrustedTimeSource.PLATFORM_SECURE_TIME,
    ): Outcome {
        if (bootId.isBlank()) {
            return Outcome.Rejected(
                OmniError.INVALID_REQUEST(message = "bootId blank"),
                "blank bootId",
            )
        }
        if (previous == null) {
            return Outcome.Accepted(
                TrustedClockRecord(
                    source = source,
                    wallTimeEpochMs = observedWallEpochMs,
                    bootId = bootId,
                    elapsedRealtimeAnchorMs = observedElapsedRealtimeMs,
                    maxForwardAdvanceMs = maxForwardAdvanceMs,
                    state = TrustedTimeState.NO_ANCHOR,
                    gracePolicyVersion = POLICY_VERSION,
                ),
            )
        }
        if (previous.state == TrustedTimeState.INVALID) {
            return Outcome.Rejected(
                OmniError.STATE_CONFLICT(message = "trusted clock invalidated"),
                "invalid clock",
            )
        }

        val sameBoot = previous.bootId == bootId
        val elapsedDelta = if (sameBoot) {
            observedElapsedRealtimeMs - previous.elapsedRealtimeAnchorMs
        } else {
            0L
        }
        val wallDelta = observedWallEpochMs - previous.wallTimeEpochMs

        val newState = when {
            !sameBoot -> {
                // Reboot: keep wall anchor, mark uncertain until signed timestamp.
                if (previous.state == TrustedTimeState.ANCHORED) {
                    TrustedTimeState.FORWARD_UNCERTAIN
                } else {
                    previous.state
                }
            }
            wallDelta < 0L && -wallDelta > 60_000L -> TrustedTimeState.ROLLBACK_UNCERTAIN
            wallDelta > maxForwardAdvanceMs && elapsedDelta < wallDelta / 2 ->
                TrustedTimeState.FORWARD_UNCERTAIN
            previous.state == TrustedTimeState.NO_ANCHOR &&
                source == TrustedTimeSource.SIGNED_TIMESTAMP_METADATA ->
                TrustedTimeState.ANCHORED
            previous.state == TrustedTimeState.ANCHORED -> TrustedTimeState.ANCHORED
            else -> previous.state
        }

        return Outcome.Accepted(
            TrustedClockRecord(
                source = source,
                wallTimeEpochMs = observedWallEpochMs,
                bootId = bootId,
                elapsedRealtimeAnchorMs = observedElapsedRealtimeMs,
                maxForwardAdvanceMs = maxForwardAdvanceMs,
                state = newState,
                gracePolicyVersion = POLICY_VERSION,
            ),
        )
    }

    /**
     * Whether remote metadata expiry may be trusted for acceptance.
     * ROLLBACK_UNCERTAIN / NO_ANCHOR without embedded unexpired root ⇒ false for remote.
     */
    fun allowsRemoteMetadataExpiry(record: TrustedClockRecord?): Boolean =
        when (record?.state) {
            TrustedTimeState.ANCHORED -> true
            TrustedTimeState.FORWARD_UNCERTAIN -> true // may still accept, not extend past prior
            TrustedTimeState.ROLLBACK_UNCERTAIN -> false
            TrustedTimeState.NO_ANCHOR -> false
            TrustedTimeState.INVALID -> false
            null -> false
        }
}
