package com.omnillm.android.runtimeservice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.omnillm.android.runtimeservice.binder.OmniBindingFacade
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.process.ProcessIdentity

/**
 * Exported binding entry that returns only [ai.omnillm.api.IOmniBinding]
 * (ANDROID-SERVICE §1–2, §6).
 *
 * Authorization is **not** the normal bind permission — callers are authenticated
 * via observed UID + ClientRegistration + scope/epoch after pairing.
 * Never exposes [ai.omnillm.api.IOmniAdmin].
 */
class RuntimeBindingService : Service() {

    private lateinit var facade: OmniBindingFacade

    override fun onCreate() {
        super.onCreate()
        check(ProcessIdentity.isRuntimeProcess()) {
            "RuntimeBindingService must run in :runtime"
        }
        RuntimeControlPlane.attach(this)
        facade = OmniBindingFacade(applicationContext)
        Log.i(TAG, "onCreate pid=${ProcessIdentity.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        // Control plane is attached; FGS owns LEGAL_START → READY.
        // Binding alone does not claim specialUse eligibility for inference.
        return facade
    }

    companion object {
        private const val TAG = "OmniRuntimeBind"
    }
}
