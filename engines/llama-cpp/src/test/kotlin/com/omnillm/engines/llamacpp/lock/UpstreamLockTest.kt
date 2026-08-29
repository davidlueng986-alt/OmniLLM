package com.omnillm.engines.llamacpp.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun classpathLock_completeAfterDigestCapture() {
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
        // Supply-chain lock complete (2026-08-29): source/toolchain/artifact digests
        // captured from the ubuntu-latest recaptured lock (pinned b9999 + NDK build).
        assertEquals(UpstreamLock.LOCK_STATE_LOCKED, lock.lockState)
        assertTrue(
            "sourceDigest must be captured",
            !lock.sourceDigest.isNullOrBlank(),
        )
        assertTrue(
            "toolchainDigest must be captured",
            !lock.toolchainDigest.isNullOrBlank(),
        )
        assertTrue(
            "artifactDigest must be captured",
            !lock.artifactDigest.isNullOrBlank(),
        )
        // D2 "stripped-packaged": per-ABI map recorded in the lock.
        assertEquals(
            mapOf(
                "arm64-v8a" to "dbd57c7b20bf82637da17da9cd978234bbb71ebfd69805b438fc8123568cc6c9",
                "x86_64" to "67a98bbb25b3006f190a3bac144e50cd2781c44d82c1c752bfae082808fcd6c5",
            ),
            lock.artifactDigestByAbi,
        )
        assertEquals(
            "dbd57c7b20bf82637da17da9cd978234bbb71ebfd69805b438fc8123568cc6c9",
            lock.artifactDigestFor("arm64-v8a"),
        )
        assertEquals(
            "67a98bbb25b3006f190a3bac144e50cd2781c44d82c1c752bfae082808fcd6c5",
            lock.artifactDigestFor("x86_64"),
        )
        assertNull("unknown ABI must fail closed", lock.artifactDigestFor("unknown-abi"))
        assertTrue("complete lock isComplete()", lock.isComplete())
        assertTrue(
            lock.abis.isEmpty() || lock.abis.contains("arm64-v8a"),
        )
        assertEquals("b9999@47c786924ad1ab7e91da2cdc72fcdb563780c2bd", lock.upstreamCommitOrTag())
        // A complete supply-chain lock alone must not mint SUPPORTED:
        // qualification cells stay UNQUALIFIED until device evidence.
        assertTrue(!lock.engineBuildIdRaw.isNullOrBlank())
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

        // Per-ABI map: complete only when every declared ABI has a digest.
        val mapComplete = incomplete.copy(
            abis = listOf("arm64-v8a", "x86_64"),
            artifactDigest = "arm64-v8a=11".repeat(16) + ",x86_64=22".repeat(16),
            artifactDigestByAbi = mapOf(
                "arm64-v8a" to "11".repeat(32),
                "x86_64" to "22".repeat(32),
            ),
            engineBuildIdRaw = "llama-cpp-build-1",
        )
        assertTrue(mapComplete.isComplete())
        assertEquals("22".repeat(32), mapComplete.artifactDigestFor("x86_64"))

        val mapMissingAbi = mapComplete.copy(
            artifactDigestByAbi = mapOf("arm64-v8a" to "11".repeat(32)),
        )
        assertFalse("missing x86_64 digest must keep lock incomplete", mapMissingAbi.isComplete())
        assertNull(mapMissingAbi.artifactDigestFor("x86_64"))
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

    @Test
    fun parseSimpleLock_artifactDigestPerAbiMap() {
        val text = """
            schemaVersion: 1
            engineId: llama.cpp
            lockState: LOCKED
            upstream:
              repository: "https://github.com/ggml-org/llama.cpp"
              tag: "b9999"
              commit: "47c786924ad1ab7e91da2cdc72fcdb563780c2bd"
              sourceDigest: "${"aa".repeat(32)}"
              patchDigest: ""
              observedAt: "2026-08-12T00:00:00Z"
            license:
              spdx: "MIT"
              licenseDigest: "${"bb".repeat(32)}"
            toolchain:
              ndkVersion: "28.2.13676358"
              toolchainDigest: "${"cc".repeat(32)}"
            build:
              abis: ["arm64-v8a", "x86_64"]
            artifact:
              engineBuildId: "llama-cpp-b9999-android"
              variant: "stripped-packaged"
              artifactDigest:
                arm64-v8a: "${"11".repeat(32)}"
                x86_64: "${"22".repeat(32)}"
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("11".repeat(32), lock.artifactDigestFor("arm64-v8a"))
        assertEquals("22".repeat(32), lock.artifactDigestFor("x86_64"))
        assertNull("unknown ABI must fail closed", lock.artifactDigestFor("unknown-abi"))
        assertEquals(
            mapOf("arm64-v8a" to "11".repeat(32), "x86_64" to "22".repeat(32)),
            lock.artifactDigestByAbi,
        )
        assertEquals(
            "arm64-v8a=${"11".repeat(32)},x86_64=${"22".repeat(32)}",
            lock.artifactDigest,
        )
        assertTrue(lock.isComplete())
    }

    @Test
    fun parseSimpleLock_artifactDigestLegacyScalar() {
        val text = """
            schemaVersion: 1
            engineId: llama.cpp
            lockState: LOCKED
            upstream:
              repository: "https://github.com/ggml-org/llama.cpp"
              tag: "b9999"
              commit: "47c786924ad1ab7e91da2cdc72fcdb563780c2bd"
              sourceDigest: "${"aa".repeat(32)}"
              patchDigest: ""
              observedAt: "2026-08-12T00:00:00Z"
            license:
              spdx: "MIT"
              licenseDigest: "${"bb".repeat(32)}"
            toolchain:
              ndkVersion: "28.2.13676358"
              toolchainDigest: "${"cc".repeat(32)}"
            build:
              abis: ["arm64-v8a", "x86_64"]
            artifact:
              engineBuildId: "llama-cpp-b9999-android"
              artifactDigest: "${"dd".repeat(32)}"
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("dd".repeat(32), lock.artifactDigest)
        assertTrue(lock.artifactDigestByAbi.isEmpty())
        assertEquals("dd".repeat(32), lock.artifactDigestFor("arm64-v8a"))
        assertEquals("dd".repeat(32), lock.artifactDigestFor("x86_64"))
        assertTrue(lock.isComplete())
    }

    @Test
    fun parseSimpleLock_artifactDigestMapMissingAbi_isNotComplete() {
        val text = """
            schemaVersion: 1
            engineId: llama.cpp
            lockState: LOCKED
            upstream:
              repository: "https://github.com/ggml-org/llama.cpp"
              tag: "b9999"
              commit: "47c786924ad1ab7e91da2cdc72fcdb563780c2bd"
              sourceDigest: "${"aa".repeat(32)}"
              patchDigest: ""
              observedAt: "2026-08-12T00:00:00Z"
            license:
              spdx: "MIT"
              licenseDigest: "${"bb".repeat(32)}"
            toolchain:
              ndkVersion: "28.2.13676358"
              toolchainDigest: "${"cc".repeat(32)}"
            build:
              abis: ["arm64-v8a", "x86_64"]
            artifact:
              engineBuildId: "llama-cpp-b9999-android"
              variant: "stripped-packaged"
              artifactDigest:
                arm64-v8a: "${"11".repeat(32)}"
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("11".repeat(32), lock.artifactDigestFor("arm64-v8a"))
        assertNull("declared ABI without digest must fail closed", lock.artifactDigestFor("x86_64"))
        assertFalse("map missing x86_64 must keep lock incomplete", lock.isComplete())
    }
}
