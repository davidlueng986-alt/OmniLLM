package com.omnillm.android.runtimeservice.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.omnillm.android.RuntimeServiceModule
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.notify.RuntimeNotifications
import com.omnillm.android.runtimeservice.process.ProcessIdentity

/**
 * Non-exported transfer FGS / job adapter path for user-started download/import
 * (ANDROID-SERVICE §1, §3).
 *
 * Uses `foregroundServiceType=dataSync` with its own permission — **never**
 * piggybacks on inference `specialUse`. When OS quota/timeout ends, jobs must
 * checkpoint and wait for a new legal window (not implemented here; scaffold only).
 */
class TransferService : Service() {

    private var activeTransfers: Int = 0

    override fun onCreate() {
        super.onCreate()
        check(ProcessIdentity.isRuntimeProcess()) {
            "TransferService must run in :runtime"
        }
        RuntimeControlPlane.attach(this)
        RuntimeNotifications.ensureChannels(this)
        Log.i(TAG, "onCreate pid=${ProcessIdentity.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancel must become a canonical job cancel command (TODO job-manager).
                activeTransfers = 0
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                activeTransfers = 0
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                activeTransfers = (activeTransfers + 1).coerceAtLeast(1)
                enterForeground(running = true)
            }
        }
        // Sticky restart for long transfers is allowed by OS policy, not an SLA.
        return START_STICKY
    }

    private fun enterForeground(running: Boolean) {
        val notification = RuntimeNotifications.transferNotification(this, running)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    RuntimeNotifications.TRANSFER_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(
                    RuntimeNotifications.TRANSFER_NOTIFICATION_ID,
                    notification,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "transfer FGS not allowed; checkpoint and wait (no hidden retry)", e)
            // Job layer should mark PAUSED_WAITING_FOREGROUND (catalog JOB machine).
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "OmniTransferFgs"

        const val ACTION_START: String = RuntimeServiceModule.Actions.TRANSFER
        const val ACTION_CANCEL: String = "com.omnillm.action.TRANSFER_CANCEL"
        const val ACTION_STOP: String = "com.omnillm.action.TRANSFER_STOP"

        fun requestStart(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
