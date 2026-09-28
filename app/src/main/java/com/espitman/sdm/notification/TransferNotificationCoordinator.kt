package com.espitman.sdm.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.espitman.sdm.MainActivity
import com.espitman.sdm.R
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferCommand
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.ui.RecentTransferSpeedTracker

class TransferNotificationCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val completionAlerts = CompletionAlertTracker()
    private val stallAlerts = StallAlertTracker()
    private val stalledIds = linkedSetOf<String>()
    private val notificationGuard = Any()
    private val speedTracker = RecentTransferSpeedTracker()
    private var completedSinceStart = 0
    private var serviceTornDown = false
    private var serviceAlive = false
    private var legacyCleaned = false
    @Volatile private var controlFeedback: Pair<String, Long>? = null

    fun showControlFeedback(message: String, downloads: List<Download>) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        synchronized(notificationGuard) {
            controlFeedback = message to (SystemClock.elapsedRealtime() + 10_000L)
            try {
                manager.notify(
                    TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                    aggregateNotification(TransferNotificationAggregate.from(downloads), foreground = serviceAlive),
                )
            } catch (_: SecurityException) {
            }
        }
    }

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

    /** Reconcile a previous process's notification without creating per-download entries. */
    fun clearOrphanSummary(downloads: List<Download>) {
        if (downloads.any { it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING }) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        try {
            cancelLegacyNotifications(manager, downloads)
            val aggregate = TransferNotificationAggregate.from(downloads, bytesPerSecond = 0L)
            if (aggregate.pendingCount > 0) {
                ensureChannel()
                manager.notify(TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID, aggregateNotification(aggregate, foreground = false))
            } else {
                manager.cancel(TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun ensureAlertChannel(manager: NotificationManager) {
        val spec = TransferAlertChannelSpec.create(
            name = appContext.getString(R.string.transfer_alert_channel_name),
            description = appContext.getString(R.string.transfer_alert_channel_description),
        )
        manager.createNotificationChannel(
            NotificationChannel(spec.id, spec.name, spec.importance).apply {
                description = spec.description
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
            },
        )
    }

    internal fun ongoingTransferNotification(aggregate: TransferNotificationAggregate? = null): Notification =
        aggregateNotification(aggregate, foreground = true)

    private fun aggregateNotification(
        aggregate: TransferNotificationAggregate?,
        foreground: Boolean,
        alert: Boolean = false,
    ): Notification {
        val appName = appContext.getString(R.string.app_name)
        val builder = NotificationCompat.Builder(
            appContext,
            if (alert) TransferAlertChannelSpec.ID else channelSpec().id,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appName)
            .setContentText(
                controlFeedback?.takeIf { SystemClock.elapsedRealtime() < it.second }?.first
                    ?: compactNotificationText(aggregate, completedSinceStart, stalledIds.size),
            )
            .setContentIntent(listPendingIntent())
            .setOngoing(foreground)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (aggregate != null && aggregate.pendingCount > 0) {
            builder.setProgress(aggregate.progress.max, aggregate.progress.percent, aggregate.progress.indeterminate)
            addBulkActions(builder, aggregate)
        }
        return builder.build()
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
            synchronized(notificationGuard) { serviceAlive = true }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun updateActiveTransfers(
        downloads: List<Download>,
        settings: SdmSettings,
        nowElapsedMs: Long,
    ) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        synchronized(notificationGuard) {
            if (serviceTornDown) return
            try {
                cancelLegacyNotifications(manager, downloads)
                val alertChange = observeAlertPlans(downloads, settings, nowElapsedMs)
                if (alertChange.shouldAlert) ensureAlertChannel(manager)
                manager.notify(
                    TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                    aggregateNotification(
                        currentAggregate(downloads, nowElapsedMs),
                        foreground = serviceAlive,
                        alert = alertChange.shouldAlert,
                    ),
                )
            } catch (_: SecurityException) {
            }
        }
    }

    fun updateAlerts(downloads: List<Download>, settings: SdmSettings, nowElapsedMs: Long) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        synchronized(notificationGuard) {
            if (serviceTornDown) return
            try {
                cancelLegacyNotifications(manager, downloads)
                val alertChange = observeAlertPlans(downloads, settings, nowElapsedMs)
                val aggregate = currentAggregate(downloads, nowElapsedMs)
                if (alertChange.changed || aggregate.pendingCount > 0) {
                    if (alertChange.shouldAlert) ensureAlertChannel(manager)
                    manager.notify(
                        TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                        aggregateNotification(
                            aggregate,
                            foreground = serviceAlive,
                            alert = alertChange.shouldAlert,
                        ),
                    )
                }
            } catch (_: SecurityException) {
            }
        }
    }

    private data class AlertChange(val changed: Boolean, val shouldAlert: Boolean)

    private fun observeAlertPlans(
        downloads: List<Download>,
        settings: SdmSettings,
        nowElapsedMs: Long,
    ): AlertChange {
        val completionPlan = completionAlerts.observe(downloads, settings.downloadComplete)
        val stallPlan = stallAlerts.observe(downloads, settings.speedAlerts, nowElapsedMs)
        completedSinceStart += completionPlan.toPost.size
        stalledIds.removeAll(stallPlan.idsToCancel)
        stalledIds.addAll(stallPlan.toPost.map { it.id })
        val shouldAlert = completionPlan.toPost.isNotEmpty() || stallPlan.toPost.isNotEmpty()
        return AlertChange(shouldAlert || stallPlan.idsToCancel.isNotEmpty(), shouldAlert)
    }

    fun onServiceTeardown(downloads: List<Download>, settings: SdmSettings) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        synchronized(notificationGuard) {
            serviceTornDown = true
            serviceAlive = false
            completedSinceStart += completionAlerts.observe(downloads, settings.downloadComplete).toPost.size
            try {
                cancelLegacyNotifications(manager, downloads)
                val aggregate = TransferNotificationAggregate.from(downloads, bytesPerSecond = 0L)
                when {
                    aggregate.pendingCount > 0 -> manager.notify(
                        TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                        aggregateNotification(aggregate, foreground = false),
                    )
                    settings.downloadComplete && completedSinceStart > 0 -> {
                        ensureAlertChannel(manager)
                        manager.cancel(TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID)
                        manager.notify(
                            TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID,
                            completionNotification(completedSinceStart),
                        )
                    }
                    else -> manager.cancel(TransferNotificationChannelSpec.ONGOING_NOTIFICATION_ID)
                }
            } catch (_: SecurityException) {
            }
        }
    }

    private fun cancelLegacyNotifications(manager: NotificationManager, downloads: List<Download>) {
        if (legacyCleaned) return
        val legacy = manager.activeNotifications
            .filter { it.id == CHILD_NOTIFICATION_ID || it.id == COMPLETION_NOTIFICATION_ID || it.id == STALL_NOTIFICATION_ID }
        legacy.forEach { item ->
            if (item.tag == null) manager.cancel(item.id) else manager.cancel(item.tag, item.id)
        }
        downloads.forEach { download ->
            manager.cancel(download.id, CHILD_NOTIFICATION_ID)
            manager.cancel(download.id, COMPLETION_NOTIFICATION_ID)
            manager.cancel(download.id, STALL_NOTIFICATION_ID)
        }
        legacyCleaned = true
    }

    private fun completionNotification(count: Int): Notification =
        NotificationCompat.Builder(appContext, TransferAlertChannelSpec.ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(if (count == 1) "1 download completed" else "$count downloads completed")
            .setContentIntent(listPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

    private fun compactNotificationText(
        aggregate: TransferNotificationAggregate?,
        completedSinceStart: Int,
        stalledCount: Int,
    ): String = when {
        aggregate == null -> "Preparing downloads"
        aggregate.pendingCount == 0 && completedSinceStart > 0 -> "$completedSinceStart completed"
        aggregate.pendingCount == 0 -> "Finishing downloads"
        else -> aggregate.compactText(completedSinceStart, stalledCount)
    }

    private fun currentAggregate(downloads: List<Download>, nowElapsedMs: Long): TransferNotificationAggregate {
        val bytesPerSecond = speedTracker.aggregateBytesPerSecond(downloads, nowElapsedMs)
        return TransferNotificationAggregate.from(downloads, bytesPerSecond)
    }

    private fun addBulkActions(builder: NotificationCompat.Builder, aggregate: TransferNotificationAggregate) {
        for (kind in TransferNotificationActions.forAggregate(aggregate)) {
            val action = TransferNotificationActions.serviceAction(kind)
            val identity = TransferNotificationPendingIntentSpec.identity(
                DownloadTransferCommand.BULK_TARGET_ID,
                action,
            ) ?: continue
            val intent = DownloadTransferService.controlIntent(appContext, identity.downloadId, action) ?: continue
            val pendingIntent = bulkActionPendingIntent(identity.requestCode, intent)
            builder.addAction(
                android.R.drawable.ic_media_pause.takeIf { kind == TransferNotificationActionKind.PAUSE_ALL }
                    ?: android.R.drawable.ic_media_play,
                TransferNotificationActions.label(kind),
                pendingIntent,
            )
        }
    }

    private fun bulkActionPendingIntent(requestCode: Int, intent: Intent): PendingIntent {
        val flags = TransferNotificationPendingIntentSpec.flags()
        return if (TransferNotificationPendingIntentSpec.usesForegroundServicePendingIntent(Build.VERSION.SDK_INT)) {
            PendingIntent.getForegroundService(appContext, requestCode, intent, flags)
        } else {
            PendingIntent.getService(appContext, requestCode, intent, flags)
        }
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

    companion object {
        const val GROUP_KEY = TransferNotificationChannelSpec.ID
        const val CHILD_NOTIFICATION_ID = 1002
        const val COMPLETION_NOTIFICATION_ID = 1003
        const val STALL_NOTIFICATION_ID = 1004
        const val ACTION_OPEN_DOWNLOAD = "com.espitman.sdm.action.OPEN_DOWNLOAD"
        const val ACTION_OPEN_DOWNLOADS = "com.espitman.sdm.action.OPEN_DOWNLOADS"
        const val EXTRA_DOWNLOAD_ID = "com.espitman.sdm.extra.DOWNLOAD_ID"
    }
}
