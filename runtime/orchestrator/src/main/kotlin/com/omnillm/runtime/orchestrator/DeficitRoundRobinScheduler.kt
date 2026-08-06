package com.omnillm.runtime.orchestrator

import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import java.util.ArrayDeque

/**
 * Per-principal FIFO + global deficit round-robin scheduler
 * (CORE-ORCHESTRATOR §3, FEAT-ROUTING §5).
 *
 * Rules:
 * - Same principal: strict FIFO by [ScheduledWork.enqueueSeq]
 * - Global: deficit counters + fair weight select the next principal
 * - Tie-break: smallest enqueue sequence among selectable heads
 * - Cost classes share one policy; quanta may differ by class
 * - Earliest-start is an estimate with [policyVersion] — not an SLA
 *
 * Thread-safe. Does not admit resources — Orchestrator calls Governor separately.
 */
class DeficitRoundRobinScheduler(
    val policyVersion: String,
    private val defaultQuantum: Long = 8L,
    private val weightFor: (PrincipalId) -> Long = { 1L },
    private val clockMonotonic: () -> Long = { System.nanoTime() },
) {
    init {
        require(policyVersion.isNotEmpty()) { "policyVersion must be non-empty" }
        require(defaultQuantum > 0L) { "defaultQuantum must be positive" }
    }

    private val lock = Any()
    private val queues = linkedMapOf<String, ArrayDeque<ScheduledWork>>()
    private val deficit = linkedMapOf<String, Long>()
    private var nextEnqueueSeq: Long = 0L
    private var totalQueued: Int = 0

    /** Snapshot of queue depths (for tests / dashboard projection). */
    fun snapshot(): SchedulerSnapshot = synchronized(lock) {
        SchedulerSnapshot(
            policyVersion = policyVersion,
            totalQueued = totalQueued,
            perPrincipal = queues.mapValues { it.value.size },
            deficits = deficit.toMap(),
            nextEnqueueSeq = nextEnqueueSeq,
        )
    }

    /**
     * Enqueue planned work under the principal FIFO.
     * Returns the assigned [ScheduledWork] with stable enqueue sequence.
     */
    fun enqueue(
        requestId: RequestId,
        principalId: PrincipalId,
        planned: PlannedCandidate,
        request: OrchestrationRequest,
    ): ScheduledWork = synchronized(lock) {
        val seq = nextEnqueueSeq++
        val work = ScheduledWork(
            requestId = requestId,
            principalId = principalId,
            costClass = planned.costClass,
            costUnits = planned.costUnits,
            enqueueSeq = seq,
            planned = planned,
            request = request,
            enqueuedMonotonic = clockMonotonic(),
        )
        val q = queues.getOrPut(principalId.value) { ArrayDeque() }
        q.addLast(work)
        deficit.putIfAbsent(principalId.value, 0L)
        totalQueued++
        work
    }

    /**
     * Select the next work item using DRR among non-empty principal queues.
     *
     * Optional [canAdmit] filters heads that currently cannot reserve resources;
     * skipped heads stay queued (no reordering within a principal FIFO).
     *
     * Returns null when empty or when no head is admittable after a bounded
     * quantum refill pass (prevents infinite spin when all heads blocked).
     */
    fun selectNext(
        canAdmit: (ScheduledWork) -> Boolean = { true },
    ): ScheduledWork? = synchronized(lock) {
        if (totalQueued == 0) return null

        // First attempt with current deficits.
        pickOnceLocked(canAdmit)?.let { return it }
        // Refill quanta so large cost units eventually admit; try once more.
        refillQuantaLocked()
        pickOnceLocked(canAdmit)
    }

    /** Remove work by request id (cancel / terminal / admission reject). */
    fun remove(requestId: RequestId): ScheduledWork? = synchronized(lock) {
        for ((principal, q) in queues) {
            val it = q.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (w.requestId.value == requestId.value) {
                    it.remove()
                    totalQueued--
                    if (q.isEmpty()) {
                        queues.remove(principal)
                    }
                    return w
                }
            }
        }
        null
    }

    fun isEmpty(): Boolean = synchronized(lock) { totalQueued == 0 }

    fun size(): Int = synchronized(lock) { totalQueued }

    /**
     * Earliest-start estimate for a queued request (not an SLA).
     * Confidence is always ESTIMATED for pure queue math without live load samples.
     */
    fun estimateStart(requestId: RequestId): EarliestStartEstimate? = synchronized(lock) {
        var cumulativeCost = 0L
        var found: ScheduledWork? = null
        // Approximate: sum cost of all work with lower enqueueSeq across principals.
        val all = queues.values.flatten().sortedBy { it.enqueueSeq }
        for (w in all) {
            if (w.requestId.value == requestId.value) {
                found = w
                break
            }
            cumulativeCost += w.costUnits
        }
        val work = found ?: return null
        val now = clockMonotonic()
        // One cost unit ≈ 1ms of synthetic wait for estimate only.
        return EarliestStartEstimate(
            enqueueSeq = work.enqueueSeq,
            estimatedStartMonotonic = now + cumulativeCost * 1_000_000L,
            policyVersion = policyVersion,
            confidence = "ESTIMATED",
            requestId = requestId,
        )
    }

    // -----------------------------------------------------------------------
    // Internal DRR
    // -----------------------------------------------------------------------

    private fun pickOnceLocked(canAdmit: (ScheduledWork) -> Boolean): ScheduledWork? {
        // Candidates: principals whose head is admittable and deficit covers cost.
        data class Head(
            val principal: String,
            val work: ScheduledWork,
            val deficit: Long,
            val weight: Long,
        )

        val heads = mutableListOf<Head>()
        for ((principal, q) in queues) {
            val head = q.peekFirst() ?: continue
            if (!canAdmit(head)) continue
            val d = deficit[principal] ?: 0L
            if (d < head.costUnits) continue
            val w = weightFor(head.principalId).coerceAtLeast(1L)
            heads += Head(principal, head, d, w)
        }
        if (heads.isEmpty()) return null

        // Prefer highest deficit/weight ratio; tie-break by enqueueSeq (global FIFO fairness).
        val best = heads.minWith(
            compareBy<Head> { -it.deficit.toDouble() / it.weight.toDouble() }
                .thenBy { it.work.enqueueSeq },
        )

        val q = queues[best.principal]!!
        val work = q.removeFirst()
        totalQueued--
        if (q.isEmpty()) {
            queues.remove(best.principal)
        }
        deficit[best.principal] = (deficit[best.principal] ?: 0L) - work.costUnits
        return work
    }

    private fun refillQuantaLocked() {
        for ((principal, q) in queues) {
            if (q.isEmpty()) continue
            val head = q.peekFirst() ?: continue
            val weight = weightFor(head.principalId).coerceAtLeast(1L)
            val quantum = CostClassLabels.defaultQuantum(head.costClass).coerceAtLeast(defaultQuantum)
            deficit[principal] = (deficit[principal] ?: 0L) + quantum * weight
        }
    }
}

/** Work item held in the scheduler queue after PLAN_READY. */
data class ScheduledWork(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val costClass: String,
    val costUnits: Long,
    val enqueueSeq: Long,
    val planned: PlannedCandidate,
    val request: OrchestrationRequest,
    val enqueuedMonotonic: Long,
) {
    init {
        require(CostClassLabels.isKnown(costClass)) { "unknown cost class: $costClass" }
        require(costUnits > 0L) { "costUnits must be positive" }
        require(enqueueSeq >= 0L) { "enqueueSeq must be non-negative" }
        require(enqueuedMonotonic >= 0L) { "enqueuedMonotonic must be non-negative" }
    }
}

data class SchedulerSnapshot(
    val policyVersion: String,
    val totalQueued: Int,
    val perPrincipal: Map<String, Int>,
    val deficits: Map<String, Long>,
    val nextEnqueueSeq: Long,
)
