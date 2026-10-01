package com.espitman.sdm.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.data.settings.automaticRetry
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.ErrorReportSanitizer
import com.espitman.sdm.notification.TransferNotificationCoordinator
import com.espitman.sdm.notification.TransferNotificationPendingIntentSpec
import com.espitman.sdm.storage.DownloadDestinationRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class DownloadTransferService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val session = DownloadTransferSession()
    private val notifications by lazy { TransferNotificationCoordinator(this) }
    private val progressCollectorStarted = AtomicBoolean(false)
    private val schedulerCollectorStarted = AtomicBoolean(false)
    private val wakeLockGuard = Any()
    private val acceptingWork = AtomicBoolean(false)
    private var keepActiveClosed = false
    private var keepActiveWakeLock: PowerManager.WakeLock? = null
    private var keepActiveAcquiredAtElapsedMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = resolveCommand(intent)
        val enteredForeground = notifications.tryEnterForeground(this)
        if (!enteredForeground) {
            session.handleCommand(startId, command = null)
            val claimedId = TransferServiceLifecyclePolicy.claimedIdToReleaseOnRejectedForeground(
                enteredForeground = false,
                command = command,
            )
            if (claimedId != null) {
                runBlocking {
                    TransferSlotCleanup.afterRejectedStart {
                        AppRepositories.queueScheduler(applicationContext).releaseClaim(claimedId)
                    }
                }
            }
            session.startIdIfIdle()?.let { stopSelf(it) }
            return START_NOT_STICKY
        }
        acceptingWork.set(true)
        startProgressCollectorOnce()
        if (TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(command)) {
            startSchedulerCollectorOnce()
        }

        if (command is PauseAllCommand || command is ResumeAllCommand) {
            session.handleCommand(startId, command = null)
            serviceScope.launch {
                try {
                    val snapshot = AppRepositories.downloads(applicationContext).schedulingSnapshot()
                    when (command) {
                        is PauseAllCommand -> {
                            if (snapshot.none { it.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.QUEUED) || SettingsRepository.get(applicationContext).settings.value.automaticRetry().dueAt(it) != null }) {
                                notifications.showControlFeedback("Nothing active to pause", snapshot)
                            } else {
                                notifications.showControlFeedback("Pausing all downloads…", snapshot)
                                AppRepositories.queueScheduler(applicationContext).pauseAll { id ->
                                    pauseActiveInSession(startId, id)
                                }
                            }
                        }
                        is ResumeAllCommand -> {
                            when {
                                snapshot.none { it.state == DownloadState.PAUSED } ->
                                    notifications.showControlFeedback("Nothing paused to resume", snapshot)
                                !AppRepositories.networkRestriction(applicationContext).allowsTransfers() ->
                                    notifications.showControlFeedback("No internet connection · Resume All unavailable", snapshot)
                                else -> {
                                    notifications.showControlFeedback("Resuming downloads…", snapshot)
                                    AppRepositories.queueScheduler(applicationContext).resumeAll()
                                }
                            }
                        }
                        else -> Unit
                    }
                } finally {
                    startSchedulerCollectorOnce()
                    session.startIdIfIdle()?.let { finishedStartId ->
                        if (acceptingWork.get()) stopSelf(finishedStartId)
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (command is ResumeTransferCommand) {
            session.handleCommand(startId, command = null)
            serviceScope.launch {
                try {
                    AppRepositories.queueScheduler(applicationContext).resume(command.downloadId)
                } finally {
                    session.startIdIfIdle()?.let { stopSelf(it) }
                }
            }
            return START_NOT_STICKY
        }
        when (val result = session.handleCommand(startId, command)) {
            is SessionCommandResult.StartJob -> {
                com.espitman.sdm.storage.FolderMutationGate.workerStarted()
                val transferJob = serviceScope.launch {
                    try {
                        when (val transferCommand = result.command) {
                            is StartTransferCommand -> executeTransfer(transferCommand)
                            else -> Unit
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        // The engine records expected failures. Cover unexpected failures too, so
                        // a card cannot remain in an active state after its worker has exited.
                        val repository = AppRepositories.downloads(applicationContext)
                        val current = repository.get(result.command.downloadId)
                        if (current?.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING) &&
                            !session.isPauseRequested(result.command.downloadId) &&
                            !AppRepositories.isNetworkOffline(applicationContext)
                        ) {
                            repository.transition(
                                current!!.id,
                                DownloadState.FAILED,
                                max(System.currentTimeMillis(), current.updatedAtEpochMillis),
                                ErrorReportSanitizer.sanitize(failure.message ?: "Download failed")
                                    .ifBlank { "Download failed" },
                            )
                        }
                    } finally {
                        withContext(NonCancellable) {
                            persistCancelledIfRequested(result.command.downloadId)
                            TransferSlotCleanup.afterTransferFinished(
                                acceptingWork = acceptingWork.get(),
                                releaseClaim = {
                                    AppRepositories.queueScheduler(applicationContext)
                                        .releaseClaim(result.command.downloadId)
                                },
                                reschedule = {
                                    AppRepositories.queueScheduler(applicationContext).schedule()
                                },
                            )
                            session.onTransferFinished(result.command.downloadId)?.let { finishedStartId ->
                                if (acceptingWork.get()) stopSelf(finishedStartId)
                            }
                        }
                    }
                }
                transferJob.invokeOnCompletion { com.espitman.sdm.storage.FolderMutationGate.workerFinished() }
                if (session.attachJob(result.command.downloadId, transferJob)) {
                    transferJob.cancel()
                }
            }
            is SessionCommandResult.CancelJob -> {
                result.job.cancel()
                startSchedulerCollectorOnce()
            }
            SessionCommandResult.None -> {
                if (command is PauseTransferCommand) {
                    serviceScope.launch {
                        persistPausedNow(command.downloadId, command.pauseCause)
                        AppRepositories.queueScheduler(applicationContext).releaseClaim(command.downloadId)
                        startSchedulerCollectorOnce()
                        session.startIdIfIdle()?.let { if (acceptingWork.get()) stopSelf(it) }
                    }
                } else if (command is CancelTransferCommand && !session.isCancelRequested(command.downloadId)) {
                    serviceScope.launch {
                        withContext(NonCancellable) {
                            persistCancelledNow(command.downloadId)
                            AppRepositories.queueScheduler(applicationContext)
                                .releaseClaim(command.downloadId)
                        }
                        startSchedulerCollectorOnce()
                        if (acceptingWork.get()) {
                            AppRepositories.queueScheduler(applicationContext).schedule()
                        }
                        session.startIdIfIdle()?.let { finishedStartId ->
                            if (acceptingWork.get()) stopSelf(finishedStartId)
                        }
                    }
                } else {
                    startSchedulerCollectorOnce()
                    session.startIdIfIdle()?.let { stopSelf(it) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        acceptingWork.set(false)
        synchronized(wakeLockGuard) {
            keepActiveClosed = true
            applyKeepActiveWakeLockLocked(shouldHold = false)
        }
        val teardownSnapshot = AppRepositories.downloads(applicationContext).downloads.value
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.onServiceTeardown(
            downloads = teardownSnapshot,
            settings = SettingsRepository.get(applicationContext).settings.value,
        )
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
                        downloads = downloads,
                        settings = settings,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
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
        serviceScope.launch {
            while (true) {
                delay(ALERT_TICK_INTERVAL_MS)
                notifications.updateAlerts(
                    downloads = AppRepositories.downloads(applicationContext).downloads.value,
                    settings = SettingsRepository.get(applicationContext).settings.value,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
        }
    }

    private fun startSchedulerCollectorOnce() {
        if (!schedulerCollectorStarted.compareAndSet(false, true)) return
        serviceScope.launch {
            combine(
                AppRepositories.downloads(applicationContext).downloads
                    .map { downloads -> downloads.map { it.id to it.state } }
                    .distinctUntilChanged(),
                SettingsRepository.get(applicationContext).settings
                    .map { it.simultaneous }
                    .distinctUntilChanged(),
            ) { _, _ -> }
                .collect {
                    AppRepositories.queueScheduler(applicationContext).schedule()
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

    private suspend fun persistCancelledIfRequested(downloadId: String) {
        if (!session.isCancelRequested(downloadId)) return
        persistCancelledNow(downloadId)
    }

    private suspend fun persistCancelledNow(downloadId: String) = withContext(NonCancellable) {
        val repository = AppRepositories.downloads(applicationContext)
        val download = repository.get(downloadId) ?: return@withContext
        repository.cancelAtExactOffset(
            id = download.id,
            fileLengthBytes = onDiskPartLength(download, session.tempFilePath(downloadId)),
            nowEpochMillis = max(System.currentTimeMillis(), download.updatedAtEpochMillis),
        )
    }

    private suspend fun persistPausedNow(downloadId: String, cause: DownloadPauseCause?) = withContext(NonCancellable) {
        val repository = AppRepositories.downloads(applicationContext)
        val download = repository.get(downloadId) ?: return@withContext
        if (download.state !in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.FAILED)) return@withContext
        repository.pauseAtExactOffset(
            id = download.id,
            fileLengthBytes = onDiskPartLength(download, session.tempFilePath(downloadId)),
            nowEpochMillis = max(System.currentTimeMillis(), download.updatedAtEpochMillis),
            pauseCause = cause,
        )
    }

    private fun onDiskPartLength(download: Download, tempFilePath: String?): Long {
        val tempFile = tempFilePath?.let(::File)
        if (tempFile != null) {
            FolderSegmentCheckpoint.load(tempFile, download)?.let { return FolderSegmentCheckpoint.bytes(tempFile, it) }
            return if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
        }
        val destinationPath = download.destinationPath ?: return 0L
        if (DownloadDestinationRef.isContentUri(destinationPath)) return 0L
        val partFile = try {
            DownloadPartFile.forDestination(File(destinationPath))
        } catch (_: IllegalArgumentException) {
            return 0L
        }
        FolderSegmentCheckpoint.load(partFile, download)?.let { return FolderSegmentCheckpoint.bytes(partFile, it) }
        return if (partFile.exists()) partFile.length().coerceAtLeast(0L) else 0L
    }

    private fun pauseActiveInSession(startId: Int, downloadId: String) {
        when (val result = session.handleCommand(startId, PauseTransferCommand(downloadId))) {
            is SessionCommandResult.CancelJob -> result.job.cancel()
            SessionCommandResult.None -> serviceScope.launch {
                persistPausedNow(downloadId, null)
                AppRepositories.queueScheduler(applicationContext).releaseClaim(downloadId)
            }
            is SessionCommandResult.StartJob -> Unit
        }
    }

    private fun resolveCommand(intent: Intent?): TransferCommand? {
        val parsed = DownloadTransferCommand.parse(
            action = intent?.action,
            downloadId = intent?.getStringExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID),
            tempFilePath = intent?.getStringExtra(DownloadTransferCommand.EXTRA_TEMP_FILE_PATH),
        )
        if (parsed !is PauseTransferCommand) return parsed
        val rawCause = intent?.getStringExtra(DownloadTransferCommand.EXTRA_PAUSE_CAUSE)
        val cause = rawCause?.let { runCatching { DownloadPauseCause.valueOf(it) }.getOrNull() }
        return if (cause == null) parsed else parsed.copy(pauseCause = cause)
    }

    private suspend fun executeTransfer(command: StartTransferCommand) {
        if (com.espitman.sdm.storage.FolderRenameCoordinator.isPending(applicationContext)) return
        val repository = AppRepositories.downloads(applicationContext)
        val download = repository.get(command.downloadId) ?: return
        val effectiveTempFilePath = download.destinationPath?.takeUnless { com.espitman.sdm.storage.DownloadDestinationRef.isContentUri(it) }
            ?.let { com.espitman.sdm.download.DownloadPartFile.forDestination(File(it)).absolutePath } ?: command.tempFilePath
        val downloadId = download.id
        if (download.schedule?.isOpen(System.currentTimeMillis()) == false) {
            if (download.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)) {
                repository.pauseAtExactOffset(
                    id = downloadId,
                    fileLengthBytes = onDiskPartLength(download, effectiveTempFilePath),
                    nowEpochMillis = max(System.currentTimeMillis(), download.updatedAtEpochMillis),
                    pauseCause = DownloadPauseCause.SCHEDULE,
                )
            }
            return
        }
        val url = download.url
        val tempFile = File(effectiveTempFilePath)
        val blocked = NetworkRestrictionStartGuard.blockStartIfDisallowed(
            allowed = AppRepositories.transferAllowance(applicationContext).isAllowed(),
            repository = repository,
            downloadId = downloadId,
            fileLengthBytes = onDiskPartLength(download, effectiveTempFilePath),
            nowEpochMillis = max(System.currentTimeMillis(), download.updatedAtEpochMillis),
        )
        if (blocked) return
        while (true) {
            try {
                run {
                    AppRepositories.transferEngine(applicationContext).executeTransfer(
                        downloadId = downloadId,
                        url = url,
                        tempFile = tempFile,
                        repository = repository,
                        pauseRequested = { session.isPauseRequested(command.downloadId) },
                        pauseCause = { session.pauseCause(command.downloadId) },
                    )
                }
                return
            } catch (_: OfflineTransferRetryException) {
                // Keep the visible active state while connectivity is absent. The job remains
                // cancellable, so a manual Pause still interrupts the wait immediately.
                while (AppRepositories.isNetworkOffline(applicationContext)) delay(1_000L)
                while (!AppRepositories.transferAllowance(applicationContext).isAllowed()) delay(1_000L)
                val current = repository.get(downloadId) ?: return
                if (current.state !in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)) return
                if (current.schedule?.isOpen(System.currentTimeMillis()) == false) {
                    repository.pauseAtExactOffset(
                        id = downloadId,
                        fileLengthBytes = onDiskPartLength(current, command.tempFilePath),
                        nowEpochMillis = max(System.currentTimeMillis(), current.updatedAtEpochMillis),
                        pauseCause = DownloadPauseCause.SCHEDULE,
                    )
                    return
                }
                repository.transition(downloadId, DownloadState.QUEUED,
                    max(System.currentTimeMillis(), current.updatedAtEpochMillis))
            }
        }
    }

    companion object {
        private const val ALERT_TICK_INTERVAL_MS = 1_000L
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

        fun pauseTransfer(
            context: Context,
            downloadId: String,
            pauseCause: DownloadPauseCause? = null,
        ) {
            startControl(
                context,
                downloadId,
                DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
                pauseCause,
            )
        }

        fun cancelTransfer(context: Context, downloadId: String) {
            startControl(context, downloadId, DownloadTransferCommand.ACTION_CANCEL_TRANSFER)
        }

        fun resumeTransfer(context: Context, downloadId: String) {
            startControl(context, downloadId, DownloadTransferCommand.ACTION_RESUME_TRANSFER)
        }

        fun pauseAll(context: Context) {
            startBulkControl(context, DownloadTransferCommand.ACTION_PAUSE_ALL)
        }

        fun resumeAll(context: Context) {
            startBulkControl(context, DownloadTransferCommand.ACTION_RESUME_ALL)
        }

        fun controlIntent(context: Context, downloadId: String, action: String): Intent? {
            val appContext = context.applicationContext
            val command = DownloadTransferCommand.parse(
                action = action,
                downloadId = downloadId,
                tempFilePath = null,
            ) ?: return null
            val resolvedAction = when (command) {
                is PauseTransferCommand -> DownloadTransferCommand.ACTION_PAUSE_TRANSFER
                is CancelTransferCommand -> DownloadTransferCommand.ACTION_CANCEL_TRANSFER
                is ResumeTransferCommand -> DownloadTransferCommand.ACTION_RESUME_TRANSFER
                is PauseAllCommand -> DownloadTransferCommand.ACTION_PAUSE_ALL
                is ResumeAllCommand -> DownloadTransferCommand.ACTION_RESUME_ALL
                is StartTransferCommand -> return null
            }
            val identity = TransferNotificationPendingIntentSpec.identity(command.downloadId, resolvedAction)
                ?: return null
            return Intent(appContext, DownloadTransferService::class.java).apply {
                this.action = resolvedAction
                data = Uri.parse(identity.data)
                if (command !is PauseAllCommand && command !is ResumeAllCommand) {
                    putExtra(DownloadTransferCommand.EXTRA_DOWNLOAD_ID, command.downloadId)
                }
            }
        }

        fun bulkControlIntent(context: Context, action: String): Intent? =
            controlIntent(context, DownloadTransferCommand.BULK_TARGET_ID, action)

        private fun startControl(
            context: Context,
            downloadId: String,
            action: String,
            pauseCause: DownloadPauseCause? = null,
        ) {
            val appContext = context.applicationContext
            val intent = controlIntent(appContext, downloadId, action) ?: return
            if (pauseCause != null) {
                intent.putExtra(DownloadTransferCommand.EXTRA_PAUSE_CAUSE, pauseCause.name)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        private fun startBulkControl(context: Context, action: String) {
            val appContext = context.applicationContext
            val intent = bulkControlIntent(appContext, action) ?: return
            ContextCompat.startForegroundService(appContext, intent)
        }
    }
}
