package com.omnillm.engines.ortgenai.mapping

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.ortgenai.session.GenAiStreamEvent
import com.omnillm.engines.ortgenai.session.GenAiStreamKind

/**
 * Normalizes GenAI stream callback events to catalog [EngineEvent]
 * (ENGINE-ORTGENAI §8, CORE-ENGINE §6).
 *
 * Rules:
 * - Sequence is monotonic and epoch-bound for a single request
 * - Terminal is unique — second terminal is rejected
 * - No native pointers / raw secrets in attributes
 * - Reply-loss uses runtime ledger, not callback arrival
 */
class EventNormalizer(
    private val requestId: RequestId,
    private val runtimeEpoch: Long,
) {
    private var nextSeq: Long = 0L
    private var terminalEmitted: Boolean = false

    val lastSeq: Long get() = nextSeq - 1L
    val hasTerminal: Boolean get() = terminalEmitted

    fun normalize(native: GenAiStreamEvent): EngineEvent? {
        if (terminalEmitted) return null

        val kind = when (native.kind) {
            GenAiStreamKind.METADATA -> EngineEventKinds.METADATA
            GenAiStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            GenAiStreamKind.USAGE -> EngineEventKinds.USAGE
            GenAiStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            GenAiStreamKind.WARNING -> EngineEventKinds.WARNING
            GenAiStreamKind.STOP -> EngineEventKinds.TERMINAL
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
            payloadDigest = null,
            attributes = ErrorMapper.sanitize(
                mapOf("stopReason" to disposition) + extra,
            ),
        )
    }

    companion object {
        fun catalogKindOf(kind: GenAiStreamKind): String = when (kind) {
            GenAiStreamKind.METADATA -> EngineEventKinds.METADATA
            GenAiStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            GenAiStreamKind.USAGE -> EngineEventKinds.USAGE
            GenAiStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            GenAiStreamKind.WARNING -> EngineEventKinds.WARNING
            GenAiStreamKind.STOP -> EngineEventKinds.TERMINAL
        }
    }
}
