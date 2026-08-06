package com.omnillm.engines.litertlm.mapping

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.litertlm.sdk.SdkStreamEvent
import com.omnillm.engines.litertlm.sdk.SdkStreamKind

/**
 * Normalizes SDK stream callbacks to catalog [EngineEvent]
 * (CORE-ENGINE §6, ENGINE-LITERT §9).
 *
 * Rules:
 * - Sequence is monotonic and epoch-bound for a single request
 * - Terminal is unique — second terminal is rejected
 * - No SDK objects / native pointers / raw secrets in attributes
 * - Reply-loss uses runtime ledger, not SDK callback arrival (ENGINE-LITERT §9)
 */
class EventNormalizer(
    private val requestId: RequestId,
    private val runtimeEpoch: Long,
) {
    private var nextSeq: Long = 0L
    private var terminalEmitted: Boolean = false

    val lastSeq: Long get() = nextSeq - 1L
    val hasTerminal: Boolean get() = terminalEmitted

    fun normalize(sdk: SdkStreamEvent): EngineEvent? {
        if (terminalEmitted) return null

        val kind = when (sdk.kind) {
            SdkStreamKind.METADATA -> EngineEventKinds.METADATA
            SdkStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            SdkStreamKind.USAGE -> EngineEventKinds.USAGE
            SdkStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            SdkStreamKind.WARNING -> EngineEventKinds.WARNING
            SdkStreamKind.STOP -> EngineEventKinds.TERMINAL
        }

        val digest = sdk.payloadDigestHex?.let { hex ->
            runCatching { Sha256Digest.parse(hex.lowercase()) }.getOrNull()
        }

        val attrs = ErrorMapper.sanitize(sdk.attributes)
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
            attributes = ErrorMapper.sanitize(
                mapOf("disposition" to disposition) + extra,
            ),
        )
    }

    companion object {
        fun catalogKindOf(kind: SdkStreamKind): String = when (kind) {
            SdkStreamKind.METADATA -> EngineEventKinds.METADATA
            SdkStreamKind.TOKEN_DELTA -> EngineEventKinds.DELTA
            SdkStreamKind.USAGE -> EngineEventKinds.USAGE
            SdkStreamKind.DIAGNOSTIC -> EngineEventKinds.DIAGNOSTIC
            SdkStreamKind.WARNING -> EngineEventKinds.WARNING
            SdkStreamKind.STOP -> EngineEventKinds.TERMINAL
        }
    }
}
