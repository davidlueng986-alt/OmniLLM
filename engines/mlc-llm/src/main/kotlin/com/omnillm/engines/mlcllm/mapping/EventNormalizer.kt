package com.omnillm.engines.mlcllm.mapping

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.mlcllm.runtime.NativeStreamEvent
import com.omnillm.engines.mlcllm.runtime.NativeStreamKind

/**
 * Normalizes MLC stream callback events to catalog [EngineEvent] (ENGINE-MLC §8).
 *
 * Rules:
 * - Sequence is monotonic and epoch-bound for a single request
 * - Terminal is unique — second terminal is rejected
 * - No native pointers / raw secrets in attributes
 */
class EventNormalizer(
    private val requestId: RequestId,
    private val runtimeEpoch: Long,
) {
    private var nextSeq: Long = 0L
    private var terminalEmitted: Boolean = false

    val lastSeq: Long get() = nextSeq - 1L
    val hasTerminal: Boolean get() = terminalEmitted

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
}
