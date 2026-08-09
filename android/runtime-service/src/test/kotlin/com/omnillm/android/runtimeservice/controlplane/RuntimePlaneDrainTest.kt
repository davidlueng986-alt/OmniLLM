package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.SessionId
import com.omnillm.runtime.session.InMemorySessionManager
import com.omnillm.runtime.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TST-04 / COR-23h: runtime drain must ACTUALLY drain live sessions via the
 * SESSION FSM before drain can be declared complete — never a fake immediate
 * success while sessions are still open.
 */
class RuntimePlaneDrainTest {

    private val owner = OwnerKey("owner-test")
    private val rev = ModelRevisionId.parse("a".repeat(64))
    private val loadKey = LoadKey(
        modelRevisionId = rev,
        engineBuildId = EngineBuildId.parse("engine-build-1"),
        backend = "cpu",
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-1"),
        templateEpoch = 1L,
        tokenizerEpoch = 1L,
        loadConfigurationDigest = Sha256Digest.parse("b".repeat(64)),
    )
    private val tokenizer = Sha256Digest.parse("c".repeat(64))
    private val alloc = AllocationHandleId.parse("alloc-1")

    private fun activeSession(
        mgr: SessionManager,
        id: String,
    ): SessionId {
        val sessionId = SessionId("s-$id")
        val created = mgr.create(
            sessionId = sessionId,
            ownerKey = owner,
            sessionEpoch = 1L,
            modelRevisionId = rev,
            loadKey = loadKey,
            tokenizerDigest = tokenizer,
            contextConfig = "{}",
            allocationHandleId = alloc,
        )
        assertTrue(created is OmniResult.Ok)
        val published = mgr.publish(sessionId)
        assertTrue(published is OmniResult.Ok)
        assertEquals("ACTIVE", (published as OmniResult.Ok).value.aggregateState)
        return sessionId
    }

    @Test
    fun activeSessions_areDrained_notLeftOpen() {
        val mgr = InMemorySessionManager()
        val s1 = activeSession(mgr, "1")
        activeSession(mgr, "2")

        val failures = RuntimeControlPlane.drainLiveSessions(mgr)
        assertTrue("all sessions must drain: $failures", failures.isEmpty())
        assertEquals("DRAINING", mgr.get(s1)!!.aggregateState)
        assertEquals(0, failures.size)
    }

    @Test
    fun newSessions_areClosedDuringDrain() {
        val mgr = InMemorySessionManager()
        val sessionId = SessionId("s-new")
        mgr.create(
            sessionId = sessionId,
            ownerKey = owner,
            sessionEpoch = 1L,
            modelRevisionId = rev,
            loadKey = loadKey,
            tokenizerDigest = tokenizer,
            contextConfig = "{}",
            allocationHandleId = alloc,
        )
        // Never published — NEW state.
        assertEquals("NEW", mgr.get(sessionId)!!.aggregateState)

        val failures = RuntimeControlPlane.drainLiveSessions(mgr)
        assertTrue("NEW session must close during drain: $failures", failures.isEmpty())
        assertEquals("CLOSING", mgr.get(sessionId)!!.aggregateState)
    }

    @Test
    fun alreadyDrainingOrClosedSessions_doNotBlockDrain() {
        val mgr = InMemorySessionManager()
        activeSession(mgr, "1")
        RuntimeControlPlane.drainLiveSessions(mgr)
        // Now everything is DRAINING — a second drain must be a no-op success.
        val failures = RuntimeControlPlane.drainLiveSessions(mgr)
        assertTrue("idempotent second drain: $failures", failures.isEmpty())
    }

    @Test
    fun failingDrain_surfacesHonestFailures() {
        val inner = InMemorySessionManager()
        activeSession(inner, "1")
        val failing = FailingDrainSessionManager(inner, "s-1")
        val failures = RuntimeControlPlane.drainLiveSessions(failing)
        assertEquals(1, failures.size)
        assertTrue(
            "failure must name the session: ${failures[0]}",
            failures[0].contains("s-1"),
        )
        // The session must NOT have been drained by the failed attempt.
        assertEquals("ACTIVE", inner.get(SessionId("s-1"))!!.aggregateState)
    }

    /** Delegating SessionManager whose requestDrain always fails for [failSessionId]. */
    private class FailingDrainSessionManager(
        private val delegate: InMemorySessionManager,
        private val failSessionId: String,
    ) : SessionManager by delegate {
        override fun requestDrain(sessionId: SessionId): OmniResult<com.omnillm.runtime.session.SessionRecord> {
            if (sessionId.value == failSessionId) {
                return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.STATE_CONFLICT(
                        message = "session pinned by active operation",
                    ),
                )
            }
            return delegate.requestDrain(sessionId)
        }
    }
}
