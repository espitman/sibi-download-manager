package com.espitman.sdm.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.notification.TransferNotificationCoordinator
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
    private val notifications by lazy { TransferNotificationCoordinator(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!notifications.tryEnterForeground(this)) {
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

    companion object {
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
