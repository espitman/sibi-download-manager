package com.espitman.sdm.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.espitman.sdm.R
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DownloadTransferService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val session = DownloadTransferSession()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground()
        } catch (_: Throwable) {
            session.handleCommand(startId, command = null)
            session.startIdIfIdle()?.let { stopSelf(it) }
            return START_NOT_STICKY
        }

        val command = DownloadTransferCommand.parse(
            action = intent?.action,
            downloadId = intent?.getStringExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID),
            tempFilePath = intent?.getStringExtra(DownloadTransferCommand.EXTRA_TEMP_FILE_PATH),
        )
        val jobToStart = session.handleCommand(startId, command)
        if (jobToStart != null) {
            serviceScope.launch {
                try {
                    executeTransfer(jobToStart)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Transfer failures are recorded by the engine; missing records are ignored.
                } finally {
                    session.onTransferFinished(jobToStart.downloadId)?.let { stopSelf(it) }
                }
            }
        } else {
            session.startIdIfIdle()?.let { stopSelf(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun executeTransfer(command: StartTransferCommand) {
        val repository = AppRepositories.downloads(applicationContext)
        val download = repository.get(command.downloadId) ?: return
        AppRepositories.transferEngine().executeTransfer(
            downloadId = download.id,
            url = download.url,
            tempFile = File(command.tempFilePath),
            repository = repository,
        )
    }

    private fun enterForeground() {
        ensurePlaceholderChannel()
        ServiceCompat.startForeground(
            this,
            PLACEHOLDER_NOTIFICATION_ID,
            placeholderNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensurePlaceholderChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(PLACEHOLDER_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                PLACEHOLDER_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun placeholderNotification(): Notification {
        val appName = getString(R.string.app_name)
        return NotificationCompat.Builder(this, PLACEHOLDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appName)
            .setContentText(appName)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val PLACEHOLDER_CHANNEL_ID = "sdm.transfer"
        const val PLACEHOLDER_NOTIFICATION_ID = 1001

        fun startTransfer(context: Context, downloadId: String, tempFilePath: String) {
            val appContext = context.applicationContext
            val command = DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_START_TRANSFER,
                downloadId = downloadId,
                tempFilePath = tempFilePath,
            ) ?: throw IllegalArgumentException(
                "Cannot start transfer without a non-blank download id and temp file path",
            )
            val intent = Intent(appContext, DownloadTransferService::class.java).apply {
                action = DownloadTransferCommand.ACTION_START_TRANSFER
                putExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID, command.downloadId)
                putExtra(DownloadTransferCommand.EXTRA_TEMP_FILE_PATH, command.tempFilePath)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }
    }
}
