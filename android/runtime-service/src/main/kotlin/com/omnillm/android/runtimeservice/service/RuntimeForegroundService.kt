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
import com.omnillm.android.runtimeservice.controlplane.LifecycleStepResult
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.android.runtimeservice.http.GatewayLifecycle
import com.omnillm.android.runtimeservice.notify.RuntimeNotifications
import com.omnillm.android.runtimeservice.process.ProcessIdentity

/**
 * Non-exported FGS holding the control plane and user-visible inference work
 * (ANDROID-SERVICE §1, §3–5).
 *
 * - `foregroundServiceType=specialUse` only — downloads must use [TransferService].
 * - Returns [START_STICKY] so the OS *may* recreate the service; this is **not**
 *   a fixed recovery SLA (ARCH-RUNTIME-LIFECYCLE §6).
 * - Not a third-party binding entry; clients bind [RuntimeBindingService] /
 *   [AdminBindingService] instead.
 */
class RuntimeForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        check(ProcessIdentity.isRuntimeProcess()) {
            "RuntimeForegroundService must run in :runtime " +
                "(current=${ProcessIdentity.currentProcessName()})"
        }
        RuntimeControlPlane.attach(this)
        RuntimeNotifications.ensureChannels(this)
        Log.i(TAG, "onCreate pid=${ProcessIdentity.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancel → canonical drain path; do not kill process (ANDROID-SERVICE §4).
                val plane = RuntimeControlPlane.require()
                GatewayLifecycle.stop()
                plane.requestDrain()
                updateForeground(plane.runtimeState)
                if (plane.runtimeState == "STOPPED") {
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                GatewayLifecycle.stop()
                RuntimeControlPlane.get()?.requestDrain()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                enterForegroundAndStart()
            }
        }
        // START_STICKY: OS may restart after kill — no timed recovery promise.
        return START_STICKY
    }

    /**
     * Catalog path:
     * STOPPED + LEGAL_START → STARTING → (FGS ok) RECOVERING → READY
     * STARTING + FOREGROUND_NOT_ALLOWED → WAITING_FOR_USER_FOREGROUND
     */
    private fun enterForegroundAndStart() {
        val plane = RuntimeControlPlane.require()
        val lifecycle = plane.lifecycle

        // Enter STARTING first so FOREGROUND_NOT_ALLOWED is a legal edge.
        if (lifecycle.state == "STOPPED") {
            when (val step = lifecycle.legalStart(foregroundLegal = true)) {
                is LifecycleStepResult.Accepted ->
                    Log.i(TAG, "LEGAL_START ${step.from}→${step.to} epoch=${step.identity.runtimeEpoch}")
                is LifecycleStepResult.Rejected ->
                    Log.w(TAG, "LEGAL_START rejected: ${step.reason}")
            }
        } else if (lifecycle.state == "WAITING_FOR_USER_FOREGROUND") {
            when (val step = lifecycle.userStart(foregroundLegal = true)) {
                is LifecycleStepResult.Accepted ->
                    Log.i(TAG, "USER_START ${step.from}→${step.to}")
                is LifecycleStepResult.Rejected ->
                    Log.w(TAG, "USER_START rejected: ${step.reason}")
            }
        }

        val preState = lifecycle.state
        val notification = RuntimeNotifications.runtimeNotification(this, preState)
        val fgsOk = try {
            startAsSpecialUse(notification)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground not allowed; WAITING_FOR_USER_FOREGROUND", e)
            if (lifecycle.state == "STARTING") {
                lifecycle.foregroundNotAllowed()
            }
            false
        }

        if (!fgsOk) {
            return
        }

        when (lifecycle.state) {
            "STARTING" -> {
                lifecycle.beginRecovery()
                // REL-RECOVERY: durable SQLite ledgers — fence open commits, prefer READY.
                plane.finishRecovery()
            }
            "RECOVERING" -> plane.finishRecovery()
            else -> Unit
        }

        updateForeground(lifecycle.state)

        // Loopback HTTP gateway (token still required except minimal /health).
        if (lifecycle.state == "READY" || lifecycle.state == "DEGRADED") {
            val port = GatewayLifecycle.ensureStarted(plane)
            Log.i(TAG, "HTTP gateway port=${port ?: "failed"}")
        }

        Log.i(
            TAG,
            "runtime state=${lifecycle.state} bootId=${plane.identity.bootId} " +
                "epoch=${plane.identity.runtimeEpoch}",
        )
    }

    private fun startAsSpecialUse(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                RuntimeNotifications.RUNTIME_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(
                RuntimeNotifications.RUNTIME_NOTIFICATION_ID,
                notification,
            )
        }
    }

    private fun updateForeground(runtimeState: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            RuntimeNotifications.RUNTIME_NOTIFICATION_ID,
            RuntimeNotifications.runtimeNotification(this, runtimeState),
        )
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
        private const val TAG = "OmniRuntimeFgs"

        const val ACTION_START: String = RuntimeServiceModule.Actions.RUNTIME_FOREGROUND
        const val ACTION_CANCEL: String = "com.omnillm.action.RUNTIME_CANCEL"
        const val ACTION_STOP: String = "com.omnillm.action.RUNTIME_STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, RuntimeForegroundService::class.java).apply {
                action = ACTION_START
            }

        /** Same-package legal entry (UI / Admin path). */
        fun requestStart(context: Context) {
            val intent = startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
