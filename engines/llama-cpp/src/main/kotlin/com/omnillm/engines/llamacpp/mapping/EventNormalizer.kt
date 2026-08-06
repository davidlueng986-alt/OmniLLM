package com.omnillm.engines.llamacpp.mapping

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.llamacpp.native.NativeStreamEvent
import com.omnillm.engines.llamacpp.native.NativeStreamKind

/**
 * Normalizes native stream events to catalog [EngineEvent] (CORE-ENGINE §6,
 * ENGINE-LLAMACPP §9).
 *
 * Rules:
 * - Sequence is monotonic and epoch-bound for a single request (half-open stream)
 * - Terminal is unique — second terminal is rejected
 * - No native pointers / raw secrets in attributes
 * - Token delta uses half-open sequence style via monotonic [seq]
 */
class EventNormalizer(
    private val requestId: RequestId,
    private val runtimeEpoch: Long,
) {
    private var nextSeq: Long = 0L
    private var terminalEmitted: Boolean = false

    val lastSeq: Long get() = nextSeq - 1L
    val hasTerminal: Boolean get() = terminalEmitted

    /**
     * Map one native event. Returns null if the event must be dropped after
     * terminal (fail closed: no post-terminal deltas).
     */
    fun normalize(native: NativeStreamEvent): EngineEvent? {
        if (terminalEmitted) return null

        val kind = when (native.kind) {
            NativeStreamKind.METADATA -> EngineEventKinds.METADATA
            NativeStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            NativeStreamKind.USAGE -> EngineEventKinds.USAGE
            NativeStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            NativeStreamKind.WARNING -> EngineEventKinds.WARNING
            NativeStreamKind.STOP -> EngineEventKinds.TERMINAL
        }

        val digest = native.payloadDigestHex?.let { hex ->
            runCatching { Sha256Digest.parse(hex.lowercase()) }.getOrNull()
        }

        val attrs = ErrorMapper.sanitize(native.attributes)
        val seq = nextSeq++
        if (kind == EngineEventKinds.TERMINAL) {
            terminalEmitted = true
        }

        return EngineEvent(
            seq = seq,
            kind = kind,
            requestId = requestId,
            runtimeEpoch = runtimeEpoch,
            payloadDigest = digest,
            attributes = attrs,
        )
    }

    /**
     * Emit a terminal event when native ended without STOP (e.g. error path
     * after stream commit). No-op if terminal already emitted.
     */
    fun ensureTerminal(
        disposition: String,
        extra: Map<String, String> = emptyMap(),
    ): EngineEvent? {
        if (terminalEmitted) return null
        terminalEmitted = true
        val seq = nextSeq++
        return EngineEvent(
            seq = seq,
            kind = EngineEventKinds.TERMINAL,
            requestId = requestId,
            runtimeEpoch = runtimeEpoch,
            attributes = ErrorMapper.sanitize(
                mapOf("disposition" to disposition) + extra,
            ),
        )
    }

    companion object {
        /**
         * Pure mapping of native kind → catalog kind (for unit tests without seq state).
         */
        fun catalogKindOf(kind: NativeStreamKind): String = when (kind) {
            NativeStreamKind.METADATA -> EngineEventKinds.METADATA
            NativeStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            NativeStreamKind.USAGE -> EngineEventKinds.USAGE
            NativeStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            NativeStreamKind.WARNING -> EngineEventKinds.WARNING
            NativeStreamKind.STOP -> EngineEventKinds.TERMINAL
        }
    }
}
