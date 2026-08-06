package com.omnillm.ui

import android.app.Application
import android.util.Log
import com.omnillm.android.runtimeservice.controlplane.RuntimeProcessBootstrap
import com.omnillm.android.runtimeservice.process.ProcessIdentity
import com.omnillm.ui.session.UiSession

/**
 * Host Application for all processes of `com.omnillm`.
 *
 * Process branching (ARCH-TRUST-TOPOLOGY / INV-001):
 * - **main UI**: no control plane, no domain DB writer, no native engines.
 * - **:runtime**: attaches [com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane].
 *
 * UI talks to the control plane only through non-exported Admin binder.
 */
class OmniApplication : Application() {

    /** UI-process session (null in :runtime and secondary processes). */
    var uiSession: UiSession? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val process = ProcessIdentity.currentProcessName()
        Log.i(TAG, "onCreate process=$process pid=${ProcessIdentity.myPid()}")

        when {
            ProcessIdentity.isRuntimeProcess() ->
                RuntimeProcessBootstrap.onRuntimeProcessCreate(this)

            ProcessIdentity.isMainUiProcess(this) -> {
                RuntimeProcessBootstrap.onMainUiProcessCreate(this)
                uiSession = UiSession(this).also { it.start() }
            }

            else ->
                Log.i(TAG, "Secondary process bootstrap deferred: $process")
        }
    }

    override fun onTerminate() {
        uiSession?.stop()
        uiSession = null
        super.onTerminate()
    }

    companion object {
        private const val TAG = "OmniApplication"
    }
}
