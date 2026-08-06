package com.omnillm.ui.admin

import ai.omnillm.api.IOmniAdmin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.omnillm.android.runtimeservice.service.AdminBindingService
import com.omnillm.android.runtimeservice.service.RuntimeForegroundService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * UI-process client that binds **only** [AdminBindingService] (INV-001 / ANDROID-SERVICE).
 *
 * Must never open domain DB writers or load native engines from the UI process.
 * Must never bind the exported [com.omnillm.android.runtimeservice.service.RuntimeBindingService]
 * for privileged admin operations.
 */
class AdminRuntimeConnection(
    private val appContext: Context,
) {
    fun interface Listener {
        fun onAdminChanged(admin: IOmniAdmin?)
    }

    private val adminRef = AtomicReference<IOmniAdmin?>(null)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var bound: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val admin = IOmniAdmin.Stub.asInterface(service)
            adminRef.set(admin)
            Log.i(TAG, "Admin binder connected component=$name")
            listeners.forEach { it.onAdminChanged(admin) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            adminRef.set(null)
            Log.w(TAG, "Admin binder disconnected component=$name")
            listeners.forEach { it.onAdminChanged(null) }
        }
    }

    val admin: IOmniAdmin? get() = adminRef.get()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onAdminChanged(adminRef.get())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Ensure runtime FGS is requested (user-visible / legal path) and bind Admin.
     * Safe to call from UI lifecycle (e.g. Activity.onStart).
     */
    fun connect() {
        RuntimeForegroundService.requestStart(appContext)
        if (bound) return
        val intent = AdminBindingService.bindIntent(appContext)
        bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.e(TAG, "bindService(AdminBindingService) returned false")
        }
    }

    fun disconnect() {
        if (!bound) return
        try {
            appContext.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbindService failed", e)
        }
        bound = false
        adminRef.set(null)
        listeners.forEach { it.onAdminChanged(null) }
    }

    companion object {
        private const val TAG = "OmniAdminConn"
    }
}
