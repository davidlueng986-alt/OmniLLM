package com.omnillm.engines.llamacpp.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamLockTest {

    @Test
    fun template_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals("llama.cpp", lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
    }

    @Test
    fun classpathLock_pinsRepositoryTagCommit_butRemainsNotLocked() {
        val lock = UpstreamLockLoader.loadFromClasspathOrTemplate()
        assertEquals("https://github.com/ggml-org/llama.cpp", lock.repository)
        assertEquals("b9999", lock.tag)
        assertEquals("47c786924ad1ab7e91da2cdc72fcdb563780c2bd", lock.commit)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals("3.22.1", lock.cmakeVersion)
        assertEquals(
            "bcd8ec749126d45cb06737d0690295d73df4b6e7e194205bcf91190368f27285",
            lock.licenseDigest,
        )
        // sourceDigest / artifactDigest / toolchainDigest intentionally pending.
        assertFalse(
            "lock must stay incomplete until source/artifact/toolchain digests",
            lock.isComplete(),
        )
        assertTrue(
            lock.abis.isEmpty() || lock.abis.contains("arm64-v8a"),
        )
        assertEquals("b9999@47c786924ad1ab7e91da2cdc72fcdb563780c2bd", lock.upstreamCommitOrTag())
    }

    @Test
    fun completeLock_requiresAllFields() {
        val incomplete = UpstreamLock(
            commit = "abc123",
            sourceDigest = "aa".repeat(32),
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

        val complete = incomplete.copy(engineBuildIdRaw = "llama-cpp-build-1")
        assertTrue(complete.isComplete())
        assertEquals("abc123", complete.upstreamCommitOrTag())
    }

    @Test
    fun parseSimpleLock_readsNestedKeys() {
        val text = """
            schemaVersion: 1
            engineId: llama.cpp
            lockState: NOT_LOCKED
            upstream:
              repository: "https://github.com/ggml-org/llama.cpp"
              tag: ""
              commit: ""
              sourceDigest: ""
            license:
              spdx: "MIT"
            toolchain:
              ndkVersion: "28.2.13676358"
            build:
              abis: []
            testedProfile:
              backends: ["cpu"]
              formats: ["GGUF"]
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("llama.cpp", lock.engineId)
        assertEquals("https://github.com/ggml-org/llama.cpp", lock.repository)
        assertEquals("MIT", lock.licenseSpdx)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals(listOf("cpu"), lock.testedBackends)
        assertEquals(listOf("GGUF"), lock.testedFormats)
        assertFalse(lock.isComplete())
    }
}
