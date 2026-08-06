package com.omnillm.android.runtimeservice.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.omnillm.android.RuntimeServiceModule
import com.omnillm.android.runtimeservice.binder.OmniAdminFacade
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.process.ProcessIdentity

/**
 * Non-exported [ai.omnillm.api.IOmniAdmin] for the host app UI process only
 * (ANDROID-SERVICE §1, access-control-catalog LOCAL_ADMIN / INV-001).
 *
 * UI must bind this service exclusively for control/admin surfaces — never the
 * exported runtime facade for privileged operations.
 */
class AdminBindingService : Service() {

    private lateinit var facade: OmniAdminFacade

    override fun onCreate() {
        super.onCreate()
        check(ProcessIdentity.isRuntimeProcess()) {
            "AdminBindingService must run in :runtime"
        }
        RuntimeControlPlane.attach(this)
        facade = OmniAdminFacade(applicationContext)
        Log.i(TAG, "onCreate pid=${ProcessIdentity.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        // UI bind is a legal start trigger; FGS owns LEGAL_START → READY transitions.
        RuntimeForegroundService.requestStart(applicationContext)
        return facade
    }

    companion object {
        private const val TAG = "OmniAdminBind"

        fun bindIntent(context: Context): Intent =
            Intent(RuntimeServiceModule.Actions.BIND_ADMIN).setComponent(
                ComponentName(context, AdminBindingService::class.java),
            )
    }
}
