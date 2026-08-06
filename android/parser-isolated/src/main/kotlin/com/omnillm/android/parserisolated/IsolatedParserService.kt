package com.omnillm.android.parserisolated

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * isolatedProcess parser service (`:parser`).
 *
 * Runtime binds explicitly, passes read-only [ParcelFileDescriptor]s registered
 * under opaque tokens, then issues [IsolatedParseRequest]. No DB, network, or
 * app-private catalog access (isolated UID has none of the host app's grants).
 */
class IsolatedParserService : Service() {

    private val engine = IsolatedParseEngine()
    private val fdRegistry = ConcurrentHashMap<String, ParcelFileDescriptor>()
    private val accepting = AtomicBoolean(true)
    private val runtimeEpoch = AtomicLong(-1L)
    private val bootId = AtomicReference<String?>(null)
    private val cancelFlag = AtomicBoolean(false)

    private val binder = object : Binder(), IsolatedParserBinder {
        override fun attach(epoch: Long, boot: String): Boolean {
            if (epoch < 0L || boot.isEmpty()) {
                return false
            }
            runtimeEpoch.set(epoch)
            bootId.set(boot)
            accepting.set(true)
            cancelFlag.set(false)
            return true
        }

        override fun registerReadOnlyFd(token: String, pfd: ParcelFileDescriptor): Boolean {
            if (!accepting.get()) return false
            if (token.isEmpty()) return false
            // Caller retains ownership until we adopt; we take ownership of the dup.
            val adopted = try {
                ParcelFileDescriptor.dup(pfd.fileDescriptor)
            } catch (_: Exception) {
                return false
            }
            val previous = fdRegistry.put(token, adopted)
            previous?.closeQuietly()
            return true
        }

        override fun parse(request: IsolatedParseRequest): IsolatedParseResult =
            this@IsolatedParserService.parse(request)

        override fun cancel() {
            cancelFlag.set(true)
        }

        override fun releaseAll() {
            clearFds()
        }

        override fun pid(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        accepting.set(false)
        clearFds()
        super.onDestroy()
    }

    private fun parse(request: IsolatedParseRequest): IsolatedParseResult {
        if (!accepting.get()) {
            return IsolatedParseResult.Err("INVALID_REQUEST", "parser not accepting")
        }
        val expectedEpoch = runtimeEpoch.get()
        val expectedBoot = bootId.get()
        if (expectedBoot == null || expectedEpoch < 0L) {
            return IsolatedParseResult.Err("INVALID_REQUEST", "parser not attached")
        }
        if (request.runtimeEpoch != expectedEpoch || request.bootId != expectedBoot) {
            return IsolatedParseResult.Err("INVALID_REQUEST", "epoch fence failed")
        }
        // Ensure all tokens are registered before open.
        for (token in request.inputFdTokens) {
            if (!fdRegistry.containsKey(token)) {
                return IsolatedParseResult.Err(
                    "INVALID_REQUEST",
                    "unknown fd token",
                )
            }
        }

        cancelFlag.set(false)
        val result = engine.parse(
            request = request,
            openStream = { token ->
                val pfd = fdRegistry[token]
                    ?: throw IllegalStateException("fd missing")
                FileInputStream(pfd.fileDescriptor)
            },
            monotonicNowMs = { SystemClock.elapsedRealtime() },
            cancel = cancelFlag,
        )
        // Consume FDs after parse (success or fail) — single-use policy default.
        clearFds()
        Log.i(TAG, "parse complete requestId=${request.requestId} ok=${result is IsolatedParseResult.Ok}")
        return result
    }

    private fun clearFds() {
        val keys = fdRegistry.keys.toList()
        for (k in keys) {
            fdRegistry.remove(k)?.closeQuietly()
        }
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "OmniIsolatedParser"
    }
}

/**
 * Internal binder surface for runtime ↔ isolated parser.
 * Not a public AIDL catalog type.
 */
interface IsolatedParserBinder : IBinder {
    fun attach(epoch: Long, boot: String): Boolean
    fun registerReadOnlyFd(token: String, pfd: ParcelFileDescriptor): Boolean
    fun parse(request: IsolatedParseRequest): IsolatedParseResult
    fun cancel()
    fun releaseAll()
    fun pid(): Int
}
