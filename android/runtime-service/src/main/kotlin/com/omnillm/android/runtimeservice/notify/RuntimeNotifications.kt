package com.omnillm.android.runtimeservice.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omnillm.android.runtimeservice.R
import com.omnillm.android.runtimeservice.service.RuntimeForegroundService
import com.omnillm.android.runtimeservice.service.TransferService

/**
 * Notification channels + builders for inference FGS and transfer FGS.
 * Cancel actions route into service intents that must become **canonical commands**
 * (never hard-kill the process — ANDROID-SERVICE §4).
 */
object RuntimeNotifications {

    const val RUNTIME_NOTIFICATION_ID: Int = 1001
    const val TRANSFER_NOTIFICATION_ID: Int = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val runtime = NotificationChannel(
            context.getString(R.string.runtime_notification_channel_id),
            context.getString(R.string.runtime_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.runtime_notification_channel_description)
        }
        val transfer = NotificationChannel(
            context.getString(R.string.transfer_notification_channel_id),
            context.getString(R.string.transfer_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.transfer_notification_channel_description)
        }
        nm.createNotificationChannel(runtime)
        nm.createNotificationChannel(transfer)
    }

    fun runtimeNotification(context: Context, runtimeState: String): Notification {
        ensureChannels(context)
        val text = stateText(context, runtimeState)
        val cancelIntent = Intent(context, RuntimeForegroundService::class.java).apply {
            action = RuntimeForegroundService.ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            context,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(
            context,
            context.getString(R.string.runtime_notification_channel_id),
        )
            .setContentTitle(context.getString(R.string.runtime_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_omnillm_runtime)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(R.string.runtime_notification_action_cancel),
                cancelPi,
            )
            .build()
    }

    fun transferNotification(context: Context, running: Boolean): Notification {
        ensureChannels(context)
        val text = if (running) {
            context.getString(R.string.transfer_notification_text_running)
        } else {
            context.getString(R.string.transfer_notification_text_idle)
        }
        val cancelIntent = Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            context,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(
            context,
            context.getString(R.string.transfer_notification_channel_id),
        )
            .setContentTitle(context.getString(R.string.transfer_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_omnillm_runtime)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(R.string.runtime_notification_action_cancel),
                cancelPi,
            )
            .build()
    }

    private fun stateText(context: Context, runtimeState: String): String =
        when (runtimeState) {
            "STARTING" -> context.getString(R.string.runtime_notification_text_starting)
            "RECOVERING" -> context.getString(R.string.runtime_notification_text_recovering)
            "READY" -> context.getString(R.string.runtime_notification_text_ready)
            "DEGRADED" -> context.getString(R.string.runtime_notification_text_degraded)
            "DRAINING" -> context.getString(R.string.runtime_notification_text_draining)
            "WAITING_FOR_USER_FOREGROUND" ->
                context.getString(R.string.runtime_notification_text_waiting_fg)
            "FAULTED" -> context.getString(R.string.runtime_notification_text_faulted)
            "STOPPED" -> context.getString(R.string.runtime_notification_text_stopped)
            else -> runtimeState
        }
}
