package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.RequestId

/**
 * Engine event contract (CORE-ENGINE §6).
 *
 * Types: metadata, delta, usage, diagnostic, warning, terminal.
 * Sequence is half-open [from,to) style on the stream batch; per-event [seq]
 * is monotonic and epoch-bound for a single request. Terminal is unique.
 *
 * Labels are catalog strings — not invented enum types outside product docs.
 */
object EngineEventKinds {
    const val METADATA: String = "metadata"
    const val DELTA: String = "delta"
    const val USAGE: String = "usage"
    const val DIAGNOSTIC: String = "diagnostic"
    const val WARNING: String = "warning"
    const val TERMINAL: String = "terminal"

    val ALL: Set<String> = setOf(
        METADATA,
        DELTA,
        USAGE,
        DIAGNOSTIC,
        WARNING,
        TERMINAL,
    )

    fun isKnown(kind: String): Boolean = kind in ALL

    fun isTerminal(kind: String): Boolean = kind == TERMINAL
}

/**
 * Normalized engine event. Payload stays digest/opaque — adapters must not
 * smuggle native pointers or raw secrets across process boundaries.
 */
data class EngineEvent(
    val seq: Long,
    val kind: String,
    val requestId: RequestId,
    val runtimeEpoch: Long,
    /** Optional content digest for delta/usage/diagnostic payloads. */
    val payloadDigest: Sha256Digest? = null,
    /** Opaque structured fields (bounded; no prompt/token plaintext by default). */
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(seq >= 0L) { "seq must be non-negative" }
        require(kind.isNotEmpty()) { "kind must be non-empty" }
        require(EngineEventKinds.isKnown(kind)) {
            "unknown engine event kind (fail closed): $kind"
        }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }

    val isTerminal: Boolean get() = EngineEventKinds.isTerminal(kind)
}

/**
 * Consumer of engine stream events during [LoadedModelPort.start].
 * Implementations live in the control plane / worker — never in the UI process.
 */
fun interface EventSink {
    /**
     * Deliver one event. Must not throw for expected stream conditions;
     * adapters map failures into terminal events or [OmniResult] on start.
     */
    suspend fun onEvent(event: EngineEvent)
}

/**
 * Opaque operation handle after successful start claim (CORE-ENGINE §5).
 * Shares OPERATION FSM states: PREPARED → STARTING → RUNNING / …
 * No native pointer — queryable by [operationId].
 */
data class OperationHandle(
    val operationId: String,
    val preparedOperationId: String,
    val requestId: RequestId,
    /** Catalog OPERATION machine state ID at handle issuance (usually STARTING/RUNNING). */
    val state: String,
    val runtimeEpoch: Long,
) {
    init {
        require(operationId.isNotEmpty()) { "operationId must be non-empty" }
        require(preparedOperationId.isNotEmpty()) { "preparedOperationId must be non-empty" }
        require(state.isNotEmpty()) { "state must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}

/**
 * Catalog `OperationContext` (specs/canonical-types.yaml).
 * Client may pre-generate [operationId] so cancel works before handle reply.
 */
data class OperationContext(
    val operationId: String,
    val principalId: String,
    /** Monotonic deadline (issuer unit). */
    val deadline: Long,
    /** Opaque cancel handle / cancel-intent key (non-empty). */
    val cancelHandle: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
) {
    init {
        require(operationId.isNotEmpty()) { "operationId must be non-empty" }
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(deadline >= 0L) { "deadline must be non-negative" }
        require(cancelHandle.isNotEmpty()) { "cancelHandle must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }
}
