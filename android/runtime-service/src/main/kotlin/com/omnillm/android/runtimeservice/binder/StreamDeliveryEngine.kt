package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.IOmniStreamCallback
import ai.omnillm.api.OmniEvent
import ai.omnillm.api.OmniEventBatch
import ai.omnillm.api.OmniError
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded event delivery for AIDL streams (ADR-006 / ANDROID-BINDER §4–5).
 *
 * - Sends only when [StreamCreditWindow] has capacity
 * - Half-open `[seqFrom, seqTo)` batches (`StreamBatch` / OmniEventBatch)
 * - `oneway` callback ≠ application consumption; only ACK frees credit
 * - Callback death detaches and invokes [onObserverDeath]
 */
class StreamDeliveryEngine(
    private val requestId: String,
    private val credit: StreamCreditWindow,
    private var callback: IOmniStreamCallback?,
    private val executor: Executor,
    private val onObserverDeath: () -> Unit,
    private val maxEventsPerBatch: Int = DEFAULT_MAX_EVENTS_PER_BATCH,
) {
    private val lock = Any()
    private val pending = ArrayDeque<OmniEvent>()
    private val closed = AtomicBoolean(false)
    private val terminalEnqueued = AtomicBoolean(false)
    private var deathRecipient: IBinder.DeathRecipient? = null

    init {
        linkDeath()
        // Initial credit so the first batches can flow (client may grant more via session).
        credit.grantCredit(
            eventCredit = StreamCreditWindow.DEFAULT_INITIAL_EVENT_CREDIT,
            byteCredit = StreamCreditWindow.DEFAULT_INITIAL_BYTE_CREDIT,
            epoch = 0L,
        )
    }

    fun isClosed(): Boolean = closed.get()

    fun creditSnapshot(): StreamCreditSnapshot = credit.snapshot()

    /**
     * Application-level credit grant (ANDROID-BINDER grantCredit(window)).
     * Formal wire surface maps this through [StreamSessionFacade.grantCredit].
     */
    fun grantCredit(eventCredit: Long, byteCredit: Long = 0L, epoch: Long? = null): GrantResult {
        if (closed.get()) {
            return GrantResult(
                accepted = false,
                streamEpoch = credit.currentEpoch(),
                grantedEventsDelta = 0L,
                grantedBytesDelta = 0L,
                reason = "closed",
            )
        }
        val result = credit.grantCredit(eventCredit, byteCredit, epoch)
        if (result.accepted && result.grantedEventsDelta > 0L) {
            scheduleFlush()
        }
        return result
    }

    fun ack(streamEpoch: Long, seqToExclusive: Long): AckResult {
        val result = credit.ack(streamEpoch, seqToExclusive)
        if (result.accepted && result.advanced) {
            scheduleFlush()
        }
        return result
    }

    /** Enqueue events for credit-gated delivery. Terminal events must be last. */
    fun enqueue(events: List<OmniEvent>) {
        if (events.isEmpty() || closed.get()) return
        synchronized(lock) {
            for (e in events) {
                if (terminalEnqueued.get()) break
                pending.addLast(e)
                if (e.terminal) {
                    terminalEnqueued.set(true)
                }
            }
        }
        scheduleFlush()
    }

    fun enqueueTerminal(
        terminalState: String,
        error: OmniError? = null,
        actualModelRevisionId: String? = null,
        engineBuildId: String? = null,
        backend: String? = null,
    ) {
        val event = OmniEvent().apply {
            kind = if (error != null) "error" else "terminal"
            textDelta = null
            hasTokenCount = false
            tokenCount = 0L
            hasProgress = false
            progress = 0.0
            this.actualModelRevisionId = actualModelRevisionId
            this.engineBuildId = engineBuildId
            this.backend = backend
            this.terminalState = terminalState
            this.error = error
            extensionSchemaId = null
            extensionCanonicalJson = null
            terminal = true
        }
        enqueue(listOf(event))
    }

    fun reject(error: OmniError) {
        if (closed.get()) return
        executor.execute {
            val cb = synchronized(lock) { callback }
            try {
                cb?.onRejected(error)
            } catch (_: DeadObjectException) {
                handleDeath()
            } catch (_: RemoteException) {
                handleDeath()
            }
        }
    }

    /**
     * closeObserver: stop further delivery; does **not** cancel the durable request.
     * Pending unsent events are dropped from the transport queue only.
     */
    fun closeObserver() {
        if (!closed.compareAndSet(false, true)) return
        unlinkDeath()
        synchronized(lock) {
            pending.clear()
            callback = null
        }
    }

    private fun scheduleFlush() {
        if (closed.get()) return
        executor.execute { flushOnce() }
    }

    private fun flushOnce() {
        if (closed.get()) return
        while (true) {
            val batchEvents: ArrayList<OmniEvent>
            val seqFrom: Long
            val seqTo: Long
            val epoch: Long
            val cb: IOmniStreamCallback
            synchronized(lock) {
                val active = callback
                if (active == null || pending.isEmpty()) return
                val available = credit.snapshot().availableEvents
                if (available <= 0L) return
                val take = minOf(maxEventsPerBatch.toLong(), available, pending.size.toLong()).toInt()
                if (take <= 0) return
                batchEvents = ArrayList(take)
                repeat(take) {
                    batchEvents.add(pending.removeFirst())
                }
                seqFrom = credit.sentToExclusive()
                val approxBytes = estimateBatchBytes(batchEvents)
                val consume = credit.tryConsumeForSend(seqFrom, batchEvents.size, approxBytes)
                if (!consume.accepted) {
                    // Put back and wait for credit/ACK.
                    for (i in batchEvents.size - 1 downTo 0) {
                        pending.addFirst(batchEvents[i])
                    }
                    return
                }
                seqTo = consume.seqToExclusive
                epoch = consume.streamEpoch
                cb = active
            }
            val batch = OmniEventBatch().apply {
                streamEpoch = epoch
                this.seqFrom = seqFrom
                this.seqTo = seqTo
                events = batchEvents.toTypedArray()
            }
            try {
                cb.onEvents(batch)
            } catch (_: DeadObjectException) {
                handleDeath()
                return
            } catch (e: RemoteException) {
                Log.w(TAG, "onEvents failed requestId=$requestId: ${e.message}")
                handleDeath()
                return
            }
            // Continue loop if more pending + credit.
            if (credit.snapshot().availableEvents <= 0L) return
            synchronized(lock) {
                if (pending.isEmpty()) return
            }
        }
    }

    private fun linkDeath() {
        val binder = try {
            callback?.asBinder()
        } catch (_: Exception) {
            null
        } ?: return
        val recipient = IBinder.DeathRecipient { handleDeath() }
        try {
            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
        } catch (_: RemoteException) {
            handleDeath()
        }
    }

    private fun unlinkDeath() {
        val binder = try {
            callback?.asBinder()
        } catch (_: Exception) {
            null
        }
        val recipient = deathRecipient
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Exception) {
            }
        }
        deathRecipient = null
    }

    private fun handleDeath() {
        if (!closed.compareAndSet(false, true)) return
        unlinkDeath()
        synchronized(lock) {
            pending.clear()
            callback = null
        }
        try {
            onObserverDeath()
        } catch (t: Throwable) {
            Log.w(TAG, "onObserverDeath failed: ${t.message}")
        }
    }

    private fun estimateBatchBytes(events: List<OmniEvent>): Long {
        var n = 64L // parcel overhead floor
        for (e in events) {
            n += 32L
            e.textDelta?.let { n += it.length.toLong() * 2 }
            e.extensionCanonicalJson?.let { n += it.length.toLong() * 2 }
            e.terminalState?.let { n += it.length.toLong() }
        }
        return n
    }

    companion object {
        private const val TAG = "OmniStreamDelivery"
        const val DEFAULT_MAX_EVENTS_PER_BATCH: Int = 16
    }
}
