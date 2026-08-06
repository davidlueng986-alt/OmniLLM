package com.omnillm.engines.llamacpp.native

import java.util.concurrent.atomic.AtomicInteger

/**
 * JNI entry points for [JniNativeBackend].
 *
 * Method names / signatures must match `jni_bridge.cpp`
 * (`Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_*`).
 *
 * Load only via [tryLoadLibrary] from the verified application package
 * (ANDROID-NATIVE §4) — never arbitrary downloaded `.so` paths.
 */
object JniNativeBridge {

    @Volatile
    private var loadState: LoadState = LoadState.UNATTEMPTED

    private enum class LoadState {
        UNATTEMPTED,
        LOADED,
        FAILED,
    }

    /**
     * Attempt [System.loadLibrary] for [JniNativeMapping.LIBRARY_NAME].
     * Safe to call multiple times. Returns true only when natives are linked.
     */
    @Synchronized
    fun tryLoadLibrary(): Boolean {
        when (loadState) {
            LoadState.LOADED -> return true
            LoadState.FAILED -> return false
            LoadState.UNATTEMPTED -> Unit
        }
        return try {
            System.loadLibrary(JniNativeMapping.LIBRARY_NAME)
            loadState = LoadState.LOADED
            true
        } catch (_: UnsatisfiedLinkError) {
            loadState = LoadState.FAILED
            false
        } catch (_: SecurityException) {
            loadState = LoadState.FAILED
            false
        }
    }

    fun isLibraryLoaded(): Boolean = loadState == LoadState.LOADED

    /** Test hook: reset load state (does not unload the .so). */
    @Synchronized
    internal fun resetLoadStateForTest() {
        loadState = LoadState.UNATTEMPTED
    }

    // ---- native methods (implemented in libomnillm_llama.so) ----

    @JvmStatic
    external fun nativeLibraryLabel(): String

    @JvmStatic
    external fun nativeAbiVersion(): Int

    /** True when this .so was linked against vendored llama.cpp. */
    @JvmStatic
    external fun nativeUpstreamLinked(): Boolean

    @JvmStatic
    external fun nativeProbe(
        backend: String,
        operationToken: String,
        outAttrs: Array<String?>,
    ): Int

    @JvmStatic
    external fun nativeLoadModel(
        storageRootKey: String,
        installationKey: String,
        backend: String,
        nCtx: Int,
        nThreads: Int,
        privilegedLoadTicketId: String,
        resolvedModelPath: String?,
        modelFd: Int,
        outToken: Array<String?>,
    ): Int

    @JvmStatic
    external fun nativeCreateSession(
        modelToken: String,
        nCtx: Int,
        seed: Long,
        hasSeed: Boolean,
        outToken: Array<String?>,
    ): Int

    @JvmStatic
    external fun nativeGenerate(
        sessionToken: String,
        operationToken: String,
        promptDigestHex: String,
        promptUtf8: String?,
        maxTokens: Int,
        temperature: Float,
        hasTemp: Boolean,
        topP: Float,
        hasTopP: Boolean,
        topK: Int,
        hasTopK: Boolean,
        stopCount: Int,
        cancelFlag: AtomicInteger,
        eventCallback: NativeEventCallback,
        outCounts: IntArray,
        outStopReason: Array<String?>,
    ): Int

    @JvmStatic
    external fun nativeEmbed(
        modelToken: String,
        operationToken: String,
        inputDigestHex: String,
    ): Int

    @JvmStatic
    external fun nativeCloseSession(sessionToken: String): Int

    @JvmStatic
    external fun nativeUnloadModel(modelToken: String): Int

    @JvmStatic
    external fun nativeRequestCancel(operationToken: String): Int
}

/**
 * Callback interface invoked from native during generate (stream events).
 * Implemented by [JniNativeBackend]; keep signature stable for JNI GetMethodID.
 */
interface NativeEventCallback {
    fun onNativeEvent(kind: Int, payloadDigestHex: String?, attributesKv: String?)
}
