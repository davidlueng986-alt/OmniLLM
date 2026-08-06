package com.omnillm.engines.mllm.mapping

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.mllm.server.ServerStreamEvent

/**
 * Normalizes mllm private-channel stream events to catalog [EngineEvent]
 * (ENGINE-MLLM §8).
 *
 * Rules:
 * - Sequence is monotonic and epoch-bound for a single request
 * - Terminal is unique — second terminal is rejected
 * - No server pointers / credentials / raw paths in attributes
 * - Adapter must not treat dual SSE write as client delivery
 */
class EventNormalizer(
    private val requestId: RequestId,
    private val runtimeEpoch: Long,
) {
    private var nextSeq: Long = 0L
    private var terminalEmitted: Boolean = false

    val lastSeq: Long get() = nextSeq - 1L
    val hasTerminal: Boolean get() = terminalEmitted

    fun normalize(server: ServerStreamEvent): EngineEvent? {
        if (terminalEmitted) return null

        val kind: String
        val payloadDigestHex: String?
        val attrs: Map<String, String>

        when (server) {
            is ServerStreamEvent.Metadata -> {
                kind = EngineEventKinds.METADATA
                payloadDigestHex = null
                attrs = server.attributes
            }
            is ServerStreamEvent.Delta -> {
                kind = EngineEventKinds.DELTA
                payloadDigestHex = server.payloadDigestHex
                attrs = server.attributes
            }
            is ServerStreamEvent.Usage -> {
                kind = EngineEventKinds.USAGE
                payloadDigestHex = null
                attrs = server.attributes
            }
            is ServerStreamEvent.Diagnostic -> {
                kind = EngineEventKinds.DIAGNOSTIC
                payloadDigestHex = null
                attrs = server.attributes
            }
            is ServerStreamEvent.Warning -> {
                kind = EngineEventKinds.WARNING
                payloadDigestHex = null
                attrs = server.attributes
            }
            is ServerStreamEvent.Stop -> {
                kind = EngineEventKinds.TERMINAL
                payloadDigestHex = null
                attrs = server.attributes + mapOf("stopReason" to server.stopReason)
            }
        }

        val digest = payloadDigestHex?.let { hex ->
            runCatching { Sha256Digest.parse(hex.lowercase()) }.getOrNull()
        }

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
            attributes = ErrorMapper.sanitize(attrs),
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
