package com.omnillm.android.runtimeservice.binder

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local index of live [StreamSessionFacade] instances (AIDL application streams).
 * Control-plane only; not a second durable writer.
 */
class StreamSessionRegistry {
    private val byRequestId = ConcurrentHashMap<String, StreamSessionFacade>()

    fun put(session: StreamSessionFacade) {
        byRequestId[session.requestIdValue] = session
    }

    fun get(requestId: String): StreamSessionFacade? = byRequestId[requestId]

    fun remove(requestId: String) {
        byRequestId.remove(requestId)
    }

    fun remove(session: StreamSessionFacade) {
        byRequestId.remove(session.requestIdValue, session)
    }

    fun all(): Collection<StreamSessionFacade> = byRequestId.values
}
