package com.espitman.sdm.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.notification.TransferNotificationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DownloadTransferService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val session = DownloadTransferSession()
    private val notifications by lazy { TransferNotificationCoordinator(this) }
    private val progressCollectorStarted = AtomicBoolean(false)
    private val wakeLockGuard = Any()
    private var keepActiveClosed = false
    private var keepActiveWakeLock: PowerManager.WakeLock? = null
    private var keepActiveAcquiredAtElapsedMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!notifications.tryEnterForeground(this)) {
            session.handleCommand(startId, command = null)
            session.startIdIfIdle()?.let { stopSelf(it) }
            return START_NOT_STICKY
        }
        startProgressCollectorOnce()

        val command = DownloadTransferCommand.parse(
            action = intent?.action,
            downloadId = intent?.getStringExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID),
            tempFilePath = intent?.getStringExtra(DownloadTransferCommand.EXTRA_TEMP_FILE_PATH),
        )
        when (val result = session.handleCommand(startId, command)) {
            is SessionCommandResult.StartJob -> {
                val transferJob = serviceScope.launch {
                    try {
                        executeTransfer(result.command)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // Transfer failures are recorded by the engine; missing records are ignored.
                    } finally {
                        session.onTransferFinished(result.command.downloadId)?.let { stopSelf(it) }
                    }
                }
                if (session.attachJob(result.command.downloadId, transferJob)) {
                    transferJob.cancel()
                }
            }
            is SessionCommandResult.CancelJob -> result.job.cancel()
            SessionCommandResult.None -> session.startIdIfIdle()?.let { stopSelf(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(wakeLockGuard) {
            keepActiveClosed = true
            applyKeepActiveWakeLockLocked(shouldHold = false)
        }
        notifications.cancelAllChildren()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startProgressCollectorOnce() {
        if (!progressCollectorStarted.compareAndSet(false, true)) return
        serviceScope.launch {
            combine(
                AppRepositories.downloads(applicationContext).downloads,
                SettingsRepository.get(applicationContext).settings,
            ) { downloads, settings -> downloads to settings }
                .collectLatest { (downloads, settings) ->
                    notifications.updateActiveTransfers(
                        downloads.filter {
                            it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING
                        },
                    )
                    val shouldHold = KeepActivePolicy.shouldHoldWakeLock(
                        keepActive = settings.keepActive,
                        keepActiveDuration = settings.keepActiveDuration,
                        states = downloads.map { it.state },
                    )
                    applyKeepActiveWakeLock(shouldHold)
                    if (!shouldHold) return@collectLatest
                    while (true) {
                        delay(KeepActivePolicy.WAKE_LOCK_TIMEOUT_MS / 2)
                        applyKeepActiveWakeLock(shouldHold = true)
                    }
                }
        }
    }

    private fun applyKeepActiveWakeLock(shouldHold: Boolean) {
        synchronized(wakeLockGuard) {
            applyKeepActiveWakeLockLocked(shouldHold)
        }
    }

    private fun applyKeepActiveWakeLockLocked(shouldHold: Boolean) {
        val wakeLock = keepActiveWakeLock
        val held = wakeLock?.isHeld == true
        val elapsedSinceAcquireMs = keepActiveAcquiredAtElapsedMs?.let { SystemClock.elapsedRealtime() - it }
        when (
            KeepActiveWakeLockDecision.nextAction(
                closed = keepActiveClosed,
                shouldHold = shouldHold,
                held = held,
                elapsedSinceAcquireMs = elapsedSinceAcquireMs,
                timeoutMs = KeepActivePolicy.WAKE_LOCK_TIMEOUT_MS,
            )
        ) {
            KeepActiveWakeLockAction.ACQUIRE -> acquireKeepActiveWakeLockLocked()
            KeepActiveWakeLockAction.RELEASE -> releaseKeepActiveWakeLockLocked()
            KeepActiveWakeLockAction.NONE -> Unit
        }
    }

    private fun acquireKeepActiveWakeLockLocked() {
        if (keepActiveClosed) return
        val wakeLock = keepActiveWakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { keepActiveWakeLock = it }
        wakeLock.acquire(KeepActivePolicy.WAKE_LOCK_TIMEOUT_MS)
        keepActiveAcquiredAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun releaseKeepActiveWakeLockLocked() {
        val wakeLock = keepActiveWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
        }
        keepActiveAcquiredAtElapsedMs = null
    }

    private suspend fun executeTransfer(command: StartTransferCommand) {
        val repository = AppRepositories.downloads(applicationContext)
        val download = repository.get(command.downloadId) ?: return
        AppRepositories.transferEngine().executeTransfer(
            downloadId = download.id,
            url = download.url,
            tempFile = File(command.tempFilePath),
            repository = repository,
            pauseRequested = { session.isPauseRequested(command.downloadId) },
        )
    }

    companion object {
        private const val WAKE_LOCK_TAG = "sdm:keep-active"

        fun startTransfer(context: Context, downloadId: String, tempFilePath: String) {
            val appContext = context.applicationContext
            val command = DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_START_TRANSFER,
                downloadId = downloadId,
                tempFilePath = tempFilePath,
            ) as? StartTransferCommand ?: throw IllegalArgumentException(
                "Cannot start transfer without a non-blank download id and temp file path",
            )
            val intent = Intent(appContext, DownloadTransferService::class.java).apply {
                action = DownloadTransferCommand.ACTION_START_TRANSFER
                putExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID, command.downloadId)
                putExtra(DownloadTransferCommand.EXTRA_TEMP_FILE_PATH, command.tempFilePath)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun pauseTransfer(context: Context, downloadId: String) {
            val appContext = context.applicationContext
            val command = DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
                downloadId = downloadId,
                tempFilePath = null,
            ) as? PauseTransferCommand ?: return
            val intent = Intent(appContext, DownloadTransferService::class.java).apply {
                action = DownloadTransferCommand.ACTION_PAUSE_TRANSFER
                putExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID, command.downloadId)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }
    }
}
