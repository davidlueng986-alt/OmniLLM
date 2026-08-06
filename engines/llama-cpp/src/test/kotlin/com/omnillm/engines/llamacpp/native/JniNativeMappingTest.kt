package com.omnillm.engines.llamacpp.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host unit tests for JNI wire mapping (no NDK / no .so required).
 */
class JniNativeMappingTest {

    @Test
    fun statusCodes_matchNativeHeaderContract() {
        // Keep in lockstep with android/native/src/main/cpp/omnillm_llama.h
        assertEquals(0, JniNativeMapping.Status.OK)
        assertEquals(1, JniNativeMapping.Status.NOT_AVAILABLE)
        assertEquals(5, JniNativeMapping.Status.MODEL_OPEN_FAILED)
        assertEquals(6, JniNativeMapping.Status.CONTEXT_CREATE_FAILED)
        assertEquals(7, JniNativeMapping.Status.TOKENIZE_FAILED)
        assertEquals(9, JniNativeMapping.Status.CANCELLED)
        assertEquals(13, JniNativeMapping.Status.WORKER_CRASH)
    }

    @Test
    fun statusToErrorCode_mapsKnownStatuses() {
        assertEquals(
            NativeErrorCode.NOT_AVAILABLE,
            JniNativeMapping.statusToErrorCode(JniNativeMapping.Status.NOT_AVAILABLE),
        )
        assertEquals(
            NativeErrorCode.UNSUPPORTED_OPERATION,
            JniNativeMapping.statusToErrorCode(JniNativeMapping.Status.UNSUPPORTED_OPERATION),
        )
        assertEquals(
            NativeErrorCode.CANCELLED,
            JniNativeMapping.statusToErrorCode(JniNativeMapping.Status.CANCELLED),
        )
        assertEquals(
            NativeErrorCode.TOKENIZE_FAILED,
            JniNativeMapping.statusToErrorCode(JniNativeMapping.Status.TOKENIZE_FAILED),
        )
        assertEquals(
            NativeErrorCode.INTERNAL,
            JniNativeMapping.statusToErrorCode(999),
        )
    }

    @Test
    fun statusToResultUnit_okAndErr() {
        assertTrue(JniNativeMapping.statusToResultUnit(JniNativeMapping.Status.OK).isOk)
        val err = JniNativeMapping.statusToResultUnit(JniNativeMapping.Status.INVALID_ARGUMENT)
        assertTrue(err.isErr)
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, err.errorOrNull()?.code)
        assertEquals(
            JniNativeMapping.Status.INVALID_ARGUMENT.toString(),
            err.errorOrNull()?.attributes?.get("nativeStatus"),
        )
    }

    @Test
    fun streamKindFromWire_coversAllKinds() {
        assertEquals(
            NativeStreamKind.METADATA,
            JniNativeMapping.streamKindFromWire(JniNativeMapping.StreamKindWire.METADATA),
        )
        assertEquals(
            NativeStreamKind.TOKEN_DELTA,
            JniNativeMapping.streamKindFromWire(JniNativeMapping.StreamKindWire.TOKEN_DELTA),
        )
        assertEquals(
            NativeStreamKind.USAGE,
            JniNativeMapping.streamKindFromWire(JniNativeMapping.StreamKindWire.USAGE),
        )
        assertEquals(
            NativeStreamKind.STOP,
            JniNativeMapping.streamKindFromWire(JniNativeMapping.StreamKindWire.STOP),
        )
        assertNull(JniNativeMapping.streamKindFromWire(-1))
        assertNull(JniNativeMapping.streamKindFromWire(42))
    }

    @Test
    fun parseAttributesKv_roundTrip() {
        val raw =
            "backend=cpu;library=omnillm-llama-0.2;native=true;available=true;fixtureMode=EXPERIMENTAL_FIXTURE"
        val map = JniNativeMapping.parseAttributesKv(raw)
        assertEquals("cpu", map["backend"])
        assertEquals("omnillm-llama-0.2", map["library"])
        assertEquals("true", map["native"])
        assertEquals("true", map["available"])
        assertEquals("EXPERIMENTAL_FIXTURE", map["fixtureMode"])

        val encoded = JniNativeMapping.encodeAttributesKv(map)
        val again = JniNativeMapping.parseAttributesKv(encoded)
        assertEquals(map, again)
    }

    @Test
    fun parseAttributesKv_emptyAndJunk() {
        assertTrue(JniNativeMapping.parseAttributesKv(null).isEmpty())
        assertTrue(JniNativeMapping.parseAttributesKv("").isEmpty())
        assertTrue(JniNativeMapping.parseAttributesKv(";;;").isEmpty())
        assertTrue(JniNativeMapping.parseAttributesKv("novalue").isEmpty())
        // Empty values are kept; empty keys are dropped.
        assertEquals(mapOf("a" to "1", "b" to ""), JniNativeMapping.parseAttributesKv("a=1;=x;b="))
    }

    @Test
    fun libraryNameAndAbi_locked() {
        assertEquals("omnillm_llama", JniNativeMapping.LIBRARY_NAME)
        assertEquals(2, JniNativeMapping.ABI_VERSION_EXPECTED)
        assertEquals("EXPERIMENTAL_FIXTURE", JniNativeMapping.EXPERIMENTAL_FIXTURE)
        assertFalse(JniNativeMapping.isOk(JniNativeMapping.Status.NOT_AVAILABLE))
        assertTrue(JniNativeMapping.isOk(JniNativeMapping.Status.OK))
    }

    @Test
    fun loadRequest_acceptsPathAndFdFields() {
        val req = NativeLoadRequest(
            storageRootKey = "broker:root",
            installationKey = JniNativeMapping.EXPERIMENTAL_FIXTURE,
            backend = "cpu",
            nCtx = 512,
            nThreads = 2,
            privilegedLoadTicketId = "ticket-fixture",
            resolvedModelPath = "fixture:EXPERIMENTAL_FIXTURE",
            modelFd = -1,
        )
        assertEquals(JniNativeMapping.EXPERIMENTAL_FIXTURE, req.installationKey)
        assertEquals("fixture:EXPERIMENTAL_FIXTURE", req.resolvedModelPath)
        assertEquals(-1, req.modelFd)
    }

    @Test
    fun generateRequest_acceptsOptionalPromptUtf8() {
        val req = NativeGenerateRequest(
            operationToken = "op-1",
            promptDigestHex = "aa".repeat(32),
            maxTokens = 8,
            promptUtf8 = "hello",
        )
        assertEquals("hello", req.promptUtf8)
        assertEquals(8, req.maxTokens)
    }

    @Test
    fun jniBridge_tryLoadLibrary_failClosedOnHostWithoutSo() {
        // Host JVM unit tests do not package the Android .so — load must fail closed.
        JniNativeBridge.resetLoadStateForTest()
        val loaded = JniNativeBridge.tryLoadLibrary()
        // May be false always on host; if somehow true, still must not crash.
        if (!loaded) {
            assertFalse(JniNativeBridge.isLibraryLoaded())
            assertNull(JniNativeBackend.createOrNull())
        }
    }
}
