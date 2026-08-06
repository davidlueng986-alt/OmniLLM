package com.omnillm.android.runtimeservice.controlplane

import android.content.Context
import android.util.Log
import com.omnillm.android.runtimeservice.process.ProcessIdentity

/**
 * Entry used from [android.app.Application.onCreate] when the current process is `:runtime`.
 *
 * UI main process must **not** call this (INV-001).
 */
object RuntimeProcessBootstrap {
    private const val TAG = "OmniRuntimeBootstrap"

    fun onRuntimeProcessCreate(context: Context) {
        check(ProcessIdentity.isRuntimeProcess()) {
            "RuntimeProcessBootstrap only valid in :runtime process " +
                "(current=${ProcessIdentity.currentProcessName()})"
        }
        RuntimeControlPlane.attach(context)
        Log.i(TAG, "Runtime process bootstrap complete")
    }

    fun onMainUiProcessCreate(context: Context) {
        check(ProcessIdentity.isMainUiProcess(context)) {
            "UI bootstrap only valid in main process " +
                "(current=${ProcessIdentity.currentProcessName()})"
        }
        // INV-001: no control plane, no DB writer, no native engine load.
        Log.i(TAG, "UI process bootstrap (Admin binder only; no control plane)")
    }
}
