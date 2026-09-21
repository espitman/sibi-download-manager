package com.espitman.sdm.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.espitman.sdm.R

class TransferNotificationCoordinator(context: Context) {
    private val appContext = context.applicationContext

    fun channelSpec(): TransferNotificationChannelSpec = TransferNotificationChannelSpec.create(
        name = appContext.getString(R.string.transfer_notification_channel_name),
        description = appContext.getString(R.string.transfer_notification_channel_description),
    )

    fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val spec = channelSpec()
        val channel = NotificationChannel(spec.id, spec.name, spec.importance).apply {
            description = spec.description
            setShowBadge(spec.showBadge)
            enableLights(false)
            enableVibration(spec.enableVibration)
            if (!spec.enableVibration) {
                vibrationPattern = null
            }
            if (!spec.enableSound) {
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(channel)
    }

    fun ongoingTransferNotification(): Notification {
        val spec = channelSpec()
        val appName = appContext.getString(R.string.app_name)
        return NotificationCompat.Builder(appContext, spec.id)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appName)
            .setContentText(appName)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun tryEnterForeground(service: Service): Boolean {
        return try {
            ensureChannel()
            ServiceCompat.startForeground(
                service,
                TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                ongoingTransferNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
