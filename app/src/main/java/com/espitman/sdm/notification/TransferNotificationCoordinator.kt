package com.espitman.sdm.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.espitman.sdm.MainActivity
import com.espitman.sdm.R
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

class TransferNotificationCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val postedChildTags = linkedSetOf<String>()

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

    fun ongoingTransferNotification(activeCount: Int = 0): Notification {
        val spec = channelSpec()
        val appName = appContext.getString(R.string.app_name)
        return NotificationCompat.Builder(appContext, spec.id)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appName)
            .setContentText(summaryText(activeCount))
            .setContentIntent(listPendingIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
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

    fun updateActiveTransfers(downloads: List<Download>) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val active = downloads.filter {
            it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING
        }
        val activeIds = active.map { it.id }.toSet()
        val staleTags = postedChildTags.filter { it !in activeIds }
        try {
            staleTags.forEach { tag ->
                manager.cancel(tag, CHILD_NOTIFICATION_ID)
                postedChildTags.remove(tag)
            }
            active.forEach { download ->
                manager.notify(download.id, CHILD_NOTIFICATION_ID, childNotification(download))
                postedChildTags.add(download.id)
            }
            manager.notify(
                TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                ongoingTransferNotification(active.size),
            )
        } catch (_: SecurityException) {
        }
    }

    fun cancelAllChildren() {
        val tags = postedChildTags.toList()
        postedChildTags.clear()
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        try {
            tags.forEach { tag ->
                manager.cancel(tag, CHILD_NOTIFICATION_ID)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun childNotification(download: Download): Notification {
        val spec = channelSpec()
        val progress = TransferNotificationProgress.from(download.downloadedBytes, download.totalBytes)
        return NotificationCompat.Builder(appContext, spec.id)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(download.fileName)
            .setContentText(progress.text)
            .setContentIntent(openDownloadPendingIntent(download.id))
            .setProgress(progress.max, progress.percent, progress.indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()
    }

    private fun summaryText(activeCount: Int): String = when {
        activeCount <= 0 -> "No active downloads"
        activeCount == 1 -> "1 download in progress"
        else -> "$activeCount downloads in progress"
    }

    private fun listPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOADS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openDownloadPendingIntent(downloadId: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_DOWNLOAD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("sdm://download/${Uri.encode(downloadId)}")
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getActivity(
            appContext,
            CHILD_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val GROUP_KEY = TransferNotificationChannelSpec.ID
        const val CHILD_NOTIFICATION_ID = 1002
        const val ACTION_OPEN_DOWNLOAD = "com.espitman.sdm.action.OPEN_DOWNLOAD"
        const val ACTION_OPEN_DOWNLOADS = "com.espitman.sdm.action.OPEN_DOWNLOADS"
        const val EXTRA_DOWNLOAD_ID = "com.espitman.sdm.extra.DOWNLOAD_ID"
    }
}
