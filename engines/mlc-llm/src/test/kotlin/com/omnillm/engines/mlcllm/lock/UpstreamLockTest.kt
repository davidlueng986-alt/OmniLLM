package com.omnillm.engines.mlcllm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamLockTest {

    @Test
    fun template_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals("MLC-LLM", lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
        assertEquals(UpstreamLock.LOCK_ELIGIBILITY, UpstreamLock.LOCK_ELIGIBILITY)
    }

    @Test
    fun completeLock_requiresMlcAndTvmFields() {
        val incomplete = UpstreamLock(
            commit = "abc123",
            sourceDigest = "aa".repeat(32),
            tvmCommit = "tvm-sha",
            patchSetPresent = true,
            patchDigest = "",
            compilerConfigDigest = "cc".repeat(32),
            targetDigest = "dd".repeat(32),
            modelConfigDigest = "ee".repeat(32),
            generatedLibraryDigest = "ff".repeat(32),
            toolchainDigest = "bb".repeat(32),
            abis = listOf("arm64-v8a"),
            artifactDigest = "11".repeat(32),
            runtimeArtifactDigest = "22".repeat(32),
            observedAt = "2026-08-03T00:00:00Z",
            licenseDigest = "33".repeat(32),
            // missing engineBuildId
        )
        assertFalse(incomplete.isComplete())

        val complete = incomplete.copy(engineBuildIdRaw = "mlc-llm-build-1")
        assertTrue(complete.isComplete())
        assertEquals("abc123", complete.upstreamCommitOrTag())
        assertEquals(
            UpstreamLock.LOCK_STATE_LOCKED,
            complete.toRegistrationFields()["lockState"],
        )
    }

    @Test
    fun parseSimpleLock_readsNestedKeys() {
        val text = """
            schemaVersion: 1
            engineId: MLC-LLM
            lockState: NOT_LOCKED
            upstream:
              repository: "https://github.com/mlc-ai/mlc-llm"
              tag: ""
              commit: ""
              sourceDigest: ""
              tvmCommit: ""
            license:
              spdx: "Apache-2.0"
            toolchain:
              ndkVersion: "28.2.13676358"
            build:
              abis: []
            testedProfile:
              backends: ["cpu", "opencl", "vulkan"]
              formats: ["mlc-model-lib"]
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals("MLC-LLM", lock.engineId)
        assertEquals("https://github.com/mlc-ai/mlc-llm", lock.repository)
        assertEquals("Apache-2.0", lock.licenseSpdx)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals(listOf("cpu", "opencl", "vulkan"), lock.testedBackends)
        assertEquals(listOf("mlc-model-lib"), lock.testedFormats)
        assertFalse(lock.isComplete())
    }

    @Test
    fun parseSimpleLock_completePinFields() {
        val text = """
            schemaVersion: 1
            engineId: MLC-LLM
            lockState: LOCKED
            upstream:
              repository: "https://github.com/mlc-ai/mlc-llm"
              tag: "v0.1.0"
              commit: "deadbeefcafebabe"
              sourceDigest: "${"aa".repeat(32)}"
              tvmCommit: "tvmcommit1"
              tvmSourceDigest: "${"bb".repeat(32)}"
              patchDigest: ""
              observedAt: "2026-08-03T00:00:00Z"
            compiler:
              compilerConfigDigest: "${"cc".repeat(32)}"
              target: "opencl-android"
              targetDigest: "${"dd".repeat(32)}"
              modelConfigDigest: "${"ee".repeat(32)}"
              generatedSourceDigest: "${"ff".repeat(32)}"
              generatedLibraryDigest: "${"11".repeat(32)}"
            license:
              spdx: "Apache-2.0"
              licenseDigest: "${"22".repeat(32)}"
            toolchain:
              ndkVersion: "28.2.13676358"
              cmakeVersion: "3.22.1"
              toolchainDigest: "${"33".repeat(32)}"
            build:
              abis: ["arm64-v8a"]
            artifact:
              engineBuildId: "mlc-llm-build-pin-1"
              artifactDigest: "${"44".repeat(32)}"
              runtimeArtifactDigest: "${"55".repeat(32)}"
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertTrue(lock.isComplete())
        assertEquals("v0.1.0@deadbeefcafebabe", lock.upstreamCommitOrTag())
        assertEquals("mlc-llm-build-pin-1", lock.resolvedEngineBuildId()!!.value)
        assertEquals("opencl-android", lock.target)
        assertTrue(lock.abis.contains("arm64-v8a"))
    }
}
