package com.omnillm.android.runtimeservice.native

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnillm.engines.llamacpp.native.JniNativeBackend
import com.omnillm.engines.llamacpp.native.NativeGenerateRequest
import com.omnillm.engines.llamacpp.native.NativeLoadRequest
import com.omnillm.engines.llamacpp.native.NativeResult
import com.omnillm.engines.llamacpp.native.NativeSessionRequest
import com.omnillm.engines.llamacpp.native.NativeStreamKind
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real upstream llama.cpp verification on emulator/device (E1).
 *
 * Requires a GGUF pushed to the test APK's own external files dir:
 *   adb push gemma-3-270m-Q8_0.gguf \
 *     /sdcard/Android/data/com.omnillm.android.runtimeservice.test/files/e2e/gemma-3-270m-Q8_0.gguf
 *
 * Verifies the packaged `libomnillm_llama.so` links vendored llama.cpp (b9999),
 * loads a real GGUF, tokenizes, and generates >= 1 completion token.
 * Qualification cells stay UNQUALIFIED — this is engineering evidence only.
 *
 * CI policy: when the GGUF fixture is ABSENT the test SKIPS via
 * [org.junit.Assume] (never AssertionError) — CI is model-free smoke only and
 * never downloads/pushes a ~300 MB GGUF. Real llama runs are manual/local.
 */
@RunWith(AndroidJUnit4::class)
class RealLlamaUpstreamInstrumentedTest {

    private companion object {
        const val TAG = "OmniNativeE2E"
        const val GGUF_RELATIVE = "e2e/gemma-3-270m-Q8_0.gguf"
    }

    /** Null when the GGUF fixture is missing (caller must Assume-skip). */
    private fun ggufPathOrNull(): File? {
        val candidates = listOf(
            File("/data/user/0/com.omnillm.android.runtimeservice.test/files", GGUF_RELATIVE),
            File("/sdcard/Android/data/com.omnillm.android.runtimeservice.test/files", GGUF_RELATIVE),
            File("/storage/emulated/0/Android/data/com.omnillm.android.runtimeservice.test/files", GGUF_RELATIVE),
            File("/data/user/0/com.omnillm.debug/files", GGUF_RELATIVE),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun loadRealGgufAndGenerateTokens() {
        val ggufOrNull = ggufPathOrNull()
        // Skip (AssumptionViolatedException), not fail, when no GGUF is pushed.
        assumeTrue(
            "GGUF fixture missing ($GGUF_RELATIVE) — real llama is manual/local; CI is model-free smoke only",
            ggufOrNull != null,
        )
        val gguf = requireNotNull(ggufOrNull) { "GGUF fixture missing: $GGUF_RELATIVE" }
        val backend = JniNativeBackend.createOrNull()
            ?: throw AssertionError("libomnillm_llama not loadable in this APK")
        Log.i(
            TAG,
            "native library=${backend.libraryLabel()} upstreamLinked=${backend.isUpstreamLinked()}",
        )
        assertTrue("must link vendored llama.cpp", backend.isUpstreamLinked())

        Log.i(TAG, "GGUF ${gguf.absolutePath} size=${gguf.length()}")

        val load = backend.loadModel(
            NativeLoadRequest(
                storageRootKey = "broker:instrumented-install",
                installationKey = "instrumented-install",
                backend = "cpu",
                nCtx = 1024,
                nThreads = 2,
                privilegedLoadTicketId = "instrumented-privileged-ticket",
                resolvedModelPath = gguf.absolutePath,
            ),
        )
        assertTrue("GGUF load failed: ${load.errorOrNull()?.message}", load.isOk)
        val model = load.getOrNull()!!
        Log.i(TAG, "model loaded ${model.value}")

        val session = backend.createSession(model, NativeSessionRequest(nCtx = 1024))
        assertTrue("session create failed: ${session.errorOrNull()?.message}", session.isOk)
        val sessionToken = session.getOrNull()!!

        val deltas = mutableListOf<String>()
        val gen = backend.generate(
            session = sessionToken,
            request = NativeGenerateRequest(
                operationToken = "op-instrumented-1",
                promptDigestHex = "a".repeat(64),
                maxTokens = 12,
                temperature = 0.0f,
                topP = 0.9f,
                promptUtf8 = "Hello",
            ),
            cancelFlag = { false },
            onEvent = { ev ->
                if (ev.kind == NativeStreamKind.TOKEN_DELTA) {
                    deltas.add(ev.payloadDigestHex ?: "")
                }
            },
        )
        assertTrue("generate failed: ${gen.errorOrNull()?.message}", gen.isOk)
        val outcome = gen.getOrNull()!!
        Log.i(
            TAG,
            "generate promptTokens=${outcome.promptTokens} completionTokens=${outcome.completionTokens} " +
                "stop=${outcome.stopReason} deltas=${deltas.size}",
        )
        assertTrue("prompt must tokenize (promptTokens=${outcome.promptTokens})", outcome.promptTokens >= 1)
        assertTrue(
            "must produce >= 1 completion token (got ${outcome.completionTokens})",
            outcome.completionTokens >= 1,
        )
        assertTrue("must emit >= 1 token delta (got ${deltas.size})", deltas.size >= 1)

        // Deterministic seed -> identical digest for the same first token.
        val gen2 = backend.generate(
            session = sessionToken,
            request = NativeGenerateRequest(
                operationToken = "op-instrumented-2",
                promptDigestHex = "a".repeat(64),
                maxTokens = 3,
                temperature = 0.0f,
                promptUtf8 = "Hello",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue("second generate failed: ${gen2.errorOrNull()?.message}", gen2.isOk)
        assertTrue(gen2.getOrNull()!!.completionTokens >= 1)

        backend.closeSession(sessionToken)
        backend.unloadModel(model)
        Log.i(TAG, "real llama.cpp upstream E2E PASS")
    }
}
