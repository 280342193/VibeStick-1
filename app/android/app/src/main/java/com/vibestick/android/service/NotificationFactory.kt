package com.vibestick.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vibestick.android.MainActivity
import com.vibestick.android.R
import com.vibestick.android.data.Alert
import com.vibestick.android.data.AlertType

const val monitorNotificationId = 1_001

private const val monitorChannelId = "vibestick_monitor"
private const val taskAlertsChannelId = "vibestick_task_alerts"

enum class TaskAlertKind {
    COMPLETED,
    FAILED,
    APPROVAL,
}

internal fun taskAlertKind(type: AlertType): TaskAlertKind? = when (type) {
    AlertType.DONE -> TaskAlertKind.COMPLETED
    AlertType.ERROR -> TaskAlertKind.FAILED
    AlertType.APPROVAL -> TaskAlertKind.APPROVAL
    AlertType.NONE -> null
}

class NotificationFactory(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val systemManager = context.getSystemService(NotificationManager::class.java)
        val monitorChannel = NotificationChannel(
            monitorChannelId,
            context.getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.monitor_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        val taskChannel = NotificationChannel(
            taskAlertsChannelId,
            context.getString(R.string.task_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.task_alert_channel_description)
            enableVibration(true)
        }
        systemManager.createNotificationChannels(listOf(monitorChannel, taskChannel))
    }

    fun foregroundNotification(computerName: String?): Notification {
        val content = computerName
            ?.takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.monitor_connected, it) }
            ?: context.getString(R.string.monitor_waiting)
        return NotificationCompat.Builder(context, monitorChannelId)
            .setSmallIcon(R.drawable.ic_vibestick)
            .setColor(ContextCompat.getColor(context, R.color.vibestick_blue))
            .setContentTitle(context.getString(R.string.monitor_notification_title))
            .setContentText(content)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showTaskAlert(alert: Alert): Boolean {
        val kind = taskAlertKind(alert.type) ?: return false
        if (alert.eventId.isBlank() || !notificationsAllowed()) return false
        val title = when (kind) {
            TaskAlertKind.COMPLETED -> context.getString(R.string.task_completed_title)
            TaskAlertKind.FAILED -> context.getString(R.string.task_failed_title)
            TaskAlertKind.APPROVAL -> context.getString(R.string.task_approval_title)
        }
        val fallbackBody = when (kind) {
            TaskAlertKind.COMPLETED -> context.getString(R.string.task_completed_body)
            TaskAlertKind.FAILED -> context.getString(R.string.task_failed_body)
            TaskAlertKind.APPROVAL -> context.getString(R.string.task_approval_body)
        }
        val notification = NotificationCompat.Builder(context, taskAlertsChannelId)
            .setSmallIcon(R.drawable.ic_vibestick)
            .setColor(ContextCompat.getColor(context, R.color.vibestick_blue))
            .setContentTitle(title)
            .setContentText(alert.message.ifBlank { fallbackBody })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    alert.message.ifBlank { fallbackBody },
                ),
            )
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(taskNotificationId(alert.eventId), notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun taskNotificationId(eventId: String): Int =
        0x2000_0000 or (eventId.hashCode() and 0x0fff_ffff)
}
