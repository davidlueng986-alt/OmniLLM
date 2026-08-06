package com.omnillm.engines.litertlm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamLockTest {

    @Test
    fun template_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals("LiteRT-LM", lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
        assertEquals(UpstreamLock.LOCK_ELIGIBILITY, UpstreamLock.LOCK_ELIGIBILITY)
    }

    @Test
    fun completeLock_requiresAllFields() {
        val incomplete = UpstreamLock(
            commit = "abc123",
            aarDigest = "aa".repeat(32),
            patchSetPresent = true,
            patchDigest = "",
            toolchainDigest = "bb".repeat(32),
            abis = listOf("arm64-v8a"),
            artifactDigest = "cc".repeat(32),
            observedAt = "2026-08-03T00:00:00Z",
            licenseDigest = "dd".repeat(32),
            // missing engineBuildId
        )
        assertFalse(incomplete.isComplete())

        val complete = incomplete.copy(engineBuildIdRaw = "litert-lm-build-1")
        assertTrue(complete.isComplete())
        assertEquals("abc123", complete.upstreamCommitOrTag())
    }

    @Test
    fun parseSimpleLock_readsNestedKeys() {
        val text = """
            schemaVersion: 1
            engineId: LiteRT-LM
            lockState: NOT_LOCKED
            upstream:
              repository: "https://github.com/google-ai-edge/LiteRT-LM"
              tag: ""
              commit: ""
              sourceDigest: ""
            license:
              spdx: ""
            toolchain:
              ndkVersion: "28.2.13676358"
            sdk:
              aarDigest: ""
            build:
              abis: []
            testedProfile:
              backends: ["cpu"]
              formats: ["litertlm"]
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("LiteRT-LM", lock.engineId)
        assertEquals("https://github.com/google-ai-edge/LiteRT-LM", lock.repository)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals(listOf("cpu"), lock.testedBackends)
        assertEquals(listOf("litertlm"), lock.testedFormats)
        assertFalse(lock.isComplete())
    }
}
