package com.omnillm.engines.mllm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamLockTest {

    @Test
    fun template_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals("mllm", lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
    }

    @Test
    fun completeLock_requiresMllmServerFields() {
        val incomplete = UpstreamLock(
            commit = "abc123",
            sourceDigest = "aa".repeat(32),
            patchSetPresent = true,
            patchDigest = "",
            goVersion = "1.22.5",
            toolchainDigest = "bb".repeat(32),
            abis = listOf("arm64-v8a"),
            artifactDigest = "11".repeat(32),
            serverProtocolDigest = "22".repeat(32),
            observedAt = "2026-08-06T00:00:00Z",
            licenseDigest = "33".repeat(32),
            // missing engineBuildId
        )
        assertFalse(incomplete.isComplete())

        val complete = incomplete.copy(engineBuildIdRaw = "mllm-build-1")
        assertTrue(complete.isComplete())
        assertEquals("abc123", complete.upstreamCommitOrTag())
    }

    @Test
    fun parseSimpleLock_readsNestedKeys() {
        val text = """
            schemaVersion: 1
            engineId: mllm
            lockState: NOT_LOCKED
            upstream:
              repository: "https://github.com/UbiquitousLearning/mllm"
              tag: ""
              commit: ""
              sourceDigest: ""
              patchDigest: ""
            license:
              spdx: "MIT"
            toolchain:
              ndkVersion: "28.2.13676358"
              goVersion: ""
            build:
              abis: []
            artifact:
              serverProtocolDigest: ""
            testedProfile:
              backends: ["cpu"]
              formats: []
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("mllm", lock.engineId)
        assertEquals("https://github.com/UbiquitousLearning/mllm", lock.repository)
        assertEquals("MIT", lock.licenseSpdx)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals(listOf("cpu"), lock.testedBackends)
        assertTrue(lock.patchSetPresent)
        assertFalse(lock.isComplete())
    }

    @Test
    fun registrationFields_exposeProtocolWhenPresent() {
        val lock = UpstreamLock(
            commit = "c1",
            sourceDigest = "aa".repeat(32),
            patchSetPresent = true,
            goVersion = "1.22.5",
            toolchainDigest = "bb".repeat(32),
            abis = listOf("arm64-v8a"),
            artifactDigest = "cc".repeat(32),
            serverProtocolDigest = "dd".repeat(32),
            observedAt = "2026-08-06T00:00:00Z",
            licenseDigest = "ee".repeat(32),
            engineBuildIdRaw = "mllm-1",
        )
        val fields = lock.toRegistrationFields()
        assertEquals(UpstreamLock.LOCK_STATE_LOCKED, fields["lockState"])
        assertEquals("dd".repeat(32), fields["serverProtocolDigest"])
        assertEquals("1.22.5", fields["goVersion"])
    }
}
