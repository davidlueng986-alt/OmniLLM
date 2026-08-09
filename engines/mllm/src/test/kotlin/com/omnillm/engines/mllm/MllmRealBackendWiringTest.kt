package com.omnillm.engines.mllm

import com.omnillm.engines.mllm.lock.UpstreamLock
import com.omnillm.engines.mllm.lock.UpstreamLockLoader
import com.omnillm.engines.mllm.server.MllmServerBackend
import com.omnillm.engines.mllm.server.ServerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module wiring tests: the in-tree UPSTREAM.lock is the locked real supply
 * chain and [MllmModule.createEngine] defaults to the real [MllmServerBackend]
 * with lock-complete execution gating.
 */
class MllmRealBackendWiringTest {

    @Test
    fun classpathLock_isCompleteAndLocked() {
        val lock = UpstreamLockLoader.loadFromClasspathOrTemplate()
        assertNotNull(lock)
        assertTrue(
            "in-tree UPSTREAM.lock must be LOCKED + complete for the real backend",
            lock.isComplete(),
        )
        assertEquals(UpstreamLock.LOCK_STATE_LOCKED, lock.lockState)
        assertEquals("mllm", lock.engineId)
        assertEquals("2.0.0", lock.tag)
        assertTrue("upstream commit must be pinned", !lock.commit.isNullOrBlank())
        assertEquals("MIT", lock.licenseSpdx)
        assertTrue(lock.abis.contains("arm64-v8a"))
    }

    @Test
    fun createEngine_defaultsToRealServerAndLockGatedExecution() {
        val engine = MllmModule.createEngine()
        assertTrue("default backend must be the real mllm server", engine.server is MllmServerBackend)
        assertTrue("complete lock must gate execution ON", engine.allowUnprovenExecution)
        assertEquals(
            "engineBuildId must resolve from the locked artifact",
            engine.engineBuildId,
            engine.lock.resolvedEngineBuildId(),
        )
    }

    @Test
    fun createEngine_withTemplateLockStaysFailClosed() {
        val engine = MllmModule.createEngine(
            lock = UpstreamLock.template(),
            server = com.omnillm.engines.mllm.server.StubServerBackend(),
        )
        assertFalse(engine.allowUnprovenExecution)
    }

    @Test
    fun createRealServer_isHostConstructibleAndFailClosedBeforeReady() {
        val server = MllmModule.createRealServer()
        assertEquals("mllm-server-gomllm", server.libraryLabel())
        val result = server.loadModel(
            com.omnillm.engines.mllm.server.ServerLoadRequest(
                storageRootKey = "k",
                installationKey = "i",
                backend = "cpu",
                privilegedLoadTicketId = "t",
                resolvedModelPath = "/data/model/qwen3",
            ),
        )
        // No channel credential → CHANNEL_POLICY (fail closed, no device needed).
        assertTrue(result is ServerResult.Err)
        assertEquals(
            com.omnillm.engines.mllm.server.ServerErrorCode.CHANNEL_POLICY,
            (result as ServerResult.Err).error.code,
        )
    }
}
