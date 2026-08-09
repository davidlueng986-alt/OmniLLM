package com.omnillm.android.runtimeservice.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * COR-20 regression: StreamSessionRegistry reuse is principal-scoped.
 *
 * A session opened by principal A must never be handed to principal B that
 * reuses the same requestId (requestId is client-generated and therefore not
 * a principal-unique key). Tests use [LiveStreamSession] fakes — no Android
 * Binder stubs required on the JVM.
 */
class StreamSessionRegistryTest {

    private class FakeSession(
        override val requestIdValue: String,
        override val principalIdValue: String,
        override val modelRevisionIdValue: String? = null,
        @Volatile var closed: Boolean = false,
    ) : LiveStreamSession {
        override fun isClosed(): Boolean = closed

        fun markClosed() {
            closed = true
        }
    }

    @Test
    fun samePrincipalReusesLiveSession() {
        val registry = StreamSessionRegistry()
        val session = FakeSession("req-1", "aidl:uid=10001")
        registry.put(session)

        val found = registry.getForPrincipal("req-1", "aidl:uid=10001")
        assertNotNull(found)
        assertEquals("aidl:uid=10001", found!!.principalIdValue)
    }

    @Test
    fun differentPrincipalNeverReceivesSessionForSameRequestId() {
        val registry = StreamSessionRegistry()
        registry.put(FakeSession("req-2", "aidl:uid=10001"))

        // Second principal reuses the same requestId → must NOT get the session.
        val other = registry.getForPrincipal("req-2", "aidl:uid=99999")
        assertNull("session must not leak across principals (COR-20)", other)
    }

    @Test
    fun closedFilteringIsCallerSide_lookupReturnsSessionForSamePrincipal() {
        val registry = StreamSessionRegistry()
        val session = FakeSession("req-3", "aidl:uid=10001", closed = true)
        registry.put(session)

        // Registry scope check is principal-only; the caller (facade) applies
        // `takeUnless { it.isClosed() }`. Closed sessions are dropped on
        // registry remove — assert the removal path here.
        val found = registry.getForPrincipal("req-3", "aidl:uid=10001")
        assertNotNull(found)

        registry.remove(session)
        assertNull(registry.getForPrincipal("req-3", "aidl:uid=10001"))
    }

    @Test
    fun removalScopedToSessionInstance() {
        val registry = StreamSessionRegistry()
        val a = FakeSession("req-4", "aidl:uid=10001")
        val b = FakeSession("req-4", "aidl:uid=99999")
        registry.put(a)
        registry.put(b) // replaces a (same requestId key)

        registry.remove(a)
        val found = registry.getForPrincipal("req-4", "aidl:uid=99999")
        assertNotNull("replacement session must survive remove of stale instance", found)
    }

    @Test
    fun replacementKeepsOnlyLatestEntryPerRequestId() {
        val registry = StreamSessionRegistry()
        registry.put(FakeSession("req-5", "aidl:uid=10001"))
        registry.put(FakeSession("req-5", "aidl:uid=99999"))

        assertNull(registry.getForPrincipal("req-5", "aidl:uid=10001"))
        assertNotNull(registry.getForPrincipal("req-5", "aidl:uid=99999"))
        assertEquals(
            "the replacement entry is the only reachable one",
            1,
            registry.all().count { it.requestIdValue == "req-5" },
        )
    }
}
