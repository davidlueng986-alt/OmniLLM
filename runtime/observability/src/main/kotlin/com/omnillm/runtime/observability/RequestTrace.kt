package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError

/**
 * Monotonic event within a request / job / command trace
 * (CORE-OBSERVABILITY §4).
 *
 * Prompt / model path / token secrets never appear in [attributes]
 * (enforced via [Redactor] + dimension policy).
 */
data class TraceEvent(
    val sequence: Long,
    val name: String,
    val phase: String?,
    val monotonicNs: Long,
    val wallEpochMs: Long?,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative" }
        require(name.isNotBlank()) { "event name must be non-blank" }
        require(monotonicNs >= 0L) { "monotonicNs must be non-negative" }
        // Same dimension privacy rules as metrics (no prompt / private path keys).
        DimensionPolicy.validate(attributes)
        for ((k, v) in attributes) {
            require(!Redactor.containsSecretPattern(v)) {
                "trace attribute $k contains secret/token pattern"
            }
        }
    }
}

/**
 * Trace envelope for one request / job / command
 * (CORE-OBSERVABILITY §4).
 */
data class RequestTrace(
    val correlationId: String,
    val causationId: String?,
    val principalId: String?,
    val requestId: String?,
    val jobId: String?,
    val policyVersion: String?,
    val engineBuildId: String?,
    val modelRevisionId: String?,
    val runtimeEpoch: Long?,
    val events: List<TraceEvent>,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must be non-blank" }
        // Sequences must be strictly monotonic within a trace.
        var prev = -1L
        for (e in events) {
            require(e.sequence > prev) {
                "trace event sequences must be strictly increasing"
            }
            prev = e.sequence
        }
    }
}

interface TraceRecorder {
    fun start(
        correlationId: String,
        causationId: String? = null,
        principalId: String? = null,
        requestId: String? = null,
        jobId: String? = null,
        policyVersion: String? = null,
        engineBuildId: String? = null,
        modelRevisionId: String? = null,
        runtimeEpoch: Long? = null,
    ): OmniResult<Unit>

    fun append(
        correlationId: String,
        name: String,
        phase: String? = null,
        attributes: Map<String, String> = emptyMap(),
        wallEpochMs: Long? = null,
    ): OmniResult<TraceEvent>

    fun get(correlationId: String): RequestTrace?

    fun finish(correlationId: String): OmniResult<RequestTrace>

    fun redactedView(correlationId: String): RequestTrace?
}

/**
 * In-memory monotonic trace store. Persistence / retention (7 days for
 * request-traces per retention-policy.yaml) is a data-layer concern.
 */
class InMemoryTraceRecorder(
    private val clockMonotonicNs: () -> Long = { System.nanoTime() },
    private val maxEventsPerTrace: Int = 4_096,
) : TraceRecorder {

    private val lock = Any()
    private data class MutableTrace(
        val correlationId: String,
        val causationId: String?,
        val principalId: String?,
        val requestId: String?,
        val jobId: String?,
        val policyVersion: String?,
        val engineBuildId: String?,
        val modelRevisionId: String?,
        val runtimeEpoch: Long?,
        val events: MutableList<TraceEvent> = mutableListOf(),
        var nextSeq: Long = 0L,
        var finished: Boolean = false,
    )

    private val traces = linkedMapOf<String, MutableTrace>()

    override fun start(
        correlationId: String,
        causationId: String?,
        principalId: String?,
        requestId: String?,
        jobId: String?,
        policyVersion: String?,
        engineBuildId: String?,
        modelRevisionId: String?,
        runtimeEpoch: Long?,
    ): OmniResult<Unit> = synchronized(lock) {
        if (correlationId.isBlank()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "correlationId must be non-blank"),
            )
        }
        if (traces.containsKey(correlationId)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "trace already started",
                    details = mapOf("correlationId" to correlationId),
                ),
            )
        }
        traces[correlationId] = MutableTrace(
            correlationId = correlationId,
            causationId = causationId,
            principalId = principalId,
            requestId = requestId,
            jobId = jobId,
            policyVersion = policyVersion,
            engineBuildId = engineBuildId,
            modelRevisionId = modelRevisionId,
            runtimeEpoch = runtimeEpoch,
        )
        OmniResult.ok(Unit)
    }

    override fun append(
        correlationId: String,
        name: String,
        phase: String?,
        attributes: Map<String, String>,
        wallEpochMs: Long?,
    ): OmniResult<TraceEvent> = synchronized(lock) {
        val t = traces[correlationId]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "trace not found",
                    details = mapOf("correlationId" to correlationId),
                ),
            )
        if (t.finished) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "trace already finished",
                    details = mapOf("correlationId" to correlationId),
                ),
            )
        }
        if (t.events.size >= maxEventsPerTrace) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "trace event limit exceeded",
                    details = mapOf("max" to maxEventsPerTrace.toString()),
                ),
            )
        }
        val redactedAttrs = attributes.mapValues { (_, v) -> Redactor.redactFreeText(v) }
        return try {
            val event = TraceEvent(
                sequence = t.nextSeq,
                name = name,
                phase = phase,
                monotonicNs = clockMonotonicNs(),
                wallEpochMs = wallEpochMs,
                attributes = redactedAttrs,
            )
            t.nextSeq += 1
            t.events += event
            OmniResult.ok(event)
        } catch (e: IllegalArgumentException) {
            OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message,
                    details = mapOf("correlationId" to correlationId),
                ),
            )
        }
    }

    override fun get(correlationId: String): RequestTrace? = synchronized(lock) {
        traces[correlationId]?.toImmutable()
    }

    override fun finish(correlationId: String): OmniResult<RequestTrace> =
        synchronized(lock) {
            val t = traces[correlationId]
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "trace not found",
                        details = mapOf("correlationId" to correlationId),
                    ),
                )
            t.finished = true
            OmniResult.ok(t.toImmutable())
        }

    override fun redactedView(correlationId: String): RequestTrace? {
        val raw = get(correlationId) ?: return null
        return raw.copy(
            events = raw.events.map { e ->
                e.copy(
                    attributes = e.attributes.mapValues { (_, v) ->
                        Redactor.redactFreeText(v)
                    },
                )
            },
        )
    }

    private fun MutableTrace.toImmutable(): RequestTrace =
        RequestTrace(
            correlationId = correlationId,
            causationId = causationId,
            principalId = principalId,
            requestId = requestId,
            jobId = jobId,
            policyVersion = policyVersion,
            engineBuildId = engineBuildId,
            modelRevisionId = modelRevisionId,
            runtimeEpoch = runtimeEpoch,
            events = events.toList(),
        )
}
