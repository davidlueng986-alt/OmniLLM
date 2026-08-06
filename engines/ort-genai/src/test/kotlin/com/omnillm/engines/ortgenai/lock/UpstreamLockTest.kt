package com.omnillm.engines.ortgenai.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamLockTest {

    @Test
    fun template_isNotComplete() {
        val lock = UpstreamLock.template()
        assertEquals(UpstreamLock.DEFAULT_ENGINE_ID, lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertFalse(lock.isComplete())
        assertEquals("", lock.upstreamCommitOrTag())
        assertEquals(listOf("cpu"), lock.testedBackends)
        assertEquals(listOf("ONNX-GENAI"), lock.testedFormats)
        assertEquals(UpstreamLock.DEFAULT_ORT_REPOSITORY, lock.ortRuntimeRepository)
        assertFalse(lock.hasProviderDigest("cpu"))
        assertFalse(lock.hasProviderDigest("nnapi"))
        assertFalse(lock.hasProviderDigest("qnn"))
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

        val complete = incomplete.copy(engineBuildIdRaw = "ort-genai-build-1")
        assertTrue(complete.isComplete())
        assertEquals("abc123", complete.upstreamCommitOrTag())
    }

    @Test
    fun providerDigestFor_readsPerBackend() {
        val lock = UpstreamLock(
            providerCpuDigest = "11".repeat(32),
            providerNnapiDigest = "22".repeat(32),
            providerQnnDigest = null,
        )
        assertTrue(lock.hasProviderDigest("cpu"))
        assertTrue(lock.hasProviderDigest("nnapi"))
        assertFalse(lock.hasProviderDigest("qnn"))
        assertEquals("11".repeat(32), lock.providerDigestFor("cpu"))
    }

    @Test
    fun parseSimpleLock_readsNestedKeysIncludingProviders() {
        val text = """
            schemaVersion: 1
            engineId: ONNX-Runtime-GenAI
            lockState: NOT_LOCKED
            upstream:
              repository: "https://github.com/microsoft/onnxruntime-genai"
              tag: ""
              commit: ""
              sourceDigest: ""
              patchDigest: ""
            ortRuntime:
              repository: "https://github.com/microsoft/onnxruntime"
              artifactDigest: ""
            providerLibraries:
              cpu: ""
              nnapi: ""
              qnn: ""
            license:
              spdx: "MIT"
            toolchain:
              ndkVersion: "28.2.13676358"
            build:
              abis: []
              configSchemaDigest: ""
            testedProfile:
              backends: ["cpu"]
              formats: ["ONNX-GENAI"]
        """.trimIndent()

        val lock = UpstreamLockLoader.parseSimpleLock(text)
        assertEquals(UpstreamLock.DEFAULT_ENGINE_ID, lock.engineId)
        assertEquals(UpstreamLock.DEFAULT_REPOSITORY, lock.repository)
        assertEquals("MIT", lock.licenseSpdx)
        assertEquals("28.2.13676358", lock.ndkVersion)
        assertEquals(listOf("cpu"), lock.testedBackends)
        assertEquals(listOf("ONNX-GENAI"), lock.testedFormats)
        assertTrue(lock.patchSetPresent)
        assertEquals("https://github.com/microsoft/onnxruntime", lock.ortRuntimeRepository)
        assertFalse(lock.isComplete())
    }

    @Test
    fun toRegistrationFields_reportsLockState() {
        val template = UpstreamLock.template()
        val fields = template.toRegistrationFields()
        assertEquals(UpstreamLock.LOCK_STATE_NOT_LOCKED, fields["lockState"])
        assertEquals(UpstreamLock.DEFAULT_ENGINE_ID, fields["engineId"])
    }
}
