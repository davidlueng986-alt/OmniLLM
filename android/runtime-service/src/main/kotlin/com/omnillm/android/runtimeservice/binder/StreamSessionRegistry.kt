package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniRequestState

/**
 * Narrow live-session surface used by [StreamSessionRegistry] (COR-20/COR-06).
 * Implemented by [StreamSessionFacade]; deliberately NOT the AIDL
 * [ai.omnillm.api.IStreamSession] so registry logic stays testable on the JVM
 * without Android Binder stubs. Production entries are always
 * [StreamSessionFacade] instances.
 */
interface LiveStreamSession {
    val requestIdValue: String
    val principalIdValue: String
    val modelRevisionIdValue: String?
    fun isClosed(): Boolean
}

/**
 * Process-local index of live [StreamSessionFacade] instances (AIDL application streams).
 * Control-plane only; not a second durable writer.
 *
 * COR-20: lookups are principal-scoped — a session opened by one principal can
 * never be returned to another principal that reuses the same requestId.
 */
class StreamSessionRegistry {
    private val byRequestId = java.util.concurrent.ConcurrentHashMap<String, LiveStreamSession>()

    fun put(session: LiveStreamSession) {
        byRequestId[session.requestIdValue] = session
    }

    /**
     * Principal-scoped lookup: returns the live session for [requestId] only
     * when it was opened by [principalId]. A different principal reusing the
     * same requestId must never receive the session (COR-20).
     */
    fun getForPrincipal(requestId: String, principalId: String): LiveStreamSession? =
        byRequestId[requestId]?.takeIf { it.principalIdValue == principalId }

    /** Unscoped lookup — internal bookkeeping only (removal / cancel paths). */
    fun get(requestId: String): LiveStreamSession? = byRequestId[requestId]

    fun remove(requestId: String) {
        byRequestId.remove(requestId)
    }

    fun remove(session: LiveStreamSession) {
        byRequestId.remove(session.requestIdValue, session)
    }

    fun all(): Collection<LiveStreamSession> = byRequestId.values
}
