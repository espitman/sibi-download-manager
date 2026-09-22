package com.espitman.sdm.data

import android.content.Context
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.DownloadInterruptionRecovery
import com.espitman.sdm.download.DownloadInterruptionTrigger
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.download.DownloadQueueScheduler
import com.espitman.sdm.download.DownloadRecoveryOnceGate
import com.espitman.sdm.download.DownloadSubmissionCoordinator
import com.espitman.sdm.download.DownloadTransferEngine
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.network.DownloadMetadataRetriever
import com.espitman.sdm.network.HttpDownloadMetadataRetriever
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import java.io.File

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null
    @Volatile private var metadataRetriever: DownloadMetadataRetriever? = null
    @Volatile private var transferEngine: DownloadTransferEngine? = null
    @Volatile private var queueScheduler: DownloadQueueScheduler? = null
    @Volatile private var submissionCoordinator: DownloadSubmissionCoordinator? = null

    fun downloads(context: Context): DownloadRepository = downloadRepository ?: synchronized(this) {
        downloadRepository ?: SqliteDownloadRepository(context.applicationContext).also { downloadRepository = it }
    }

    fun metadataRetriever(): DownloadMetadataRetriever = metadataRetriever ?: synchronized(this) {
        metadataRetriever ?: HttpDownloadMetadataRetriever().also { metadataRetriever = it }
    }

    fun transferEngine(): DownloadTransferEngine = transferEngine ?: synchronized(this) {
        transferEngine ?: DownloadTransferEngine().also { transferEngine = it }
    }

    suspend fun recoverInterruptedDownloads(
        context: Context,
        trigger: DownloadInterruptionTrigger,
        gate: DownloadRecoveryOnceGate = DownloadRecoveryOnceGate.shared,
        clock: Clock = Clock.SystemClock,
    ) {
        gate.runOnce {
            DownloadInterruptionRecovery.recover(
                repository = downloads(context),
                clock = clock,
                trigger = trigger,
            )
        }
        queueScheduler(context).schedule()
    }

    fun queueScheduler(context: Context): DownloadQueueScheduler = queueScheduler ?: synchronized(this) {
        queueScheduler ?: run {
            val appContext = context.applicationContext
            DownloadQueueScheduler(
                repository = downloads(appContext),
                concurrentLimit = {
                    SettingsRepository.get(appContext).settings.value.simultaneous
                },
                starter = { download ->
                    val destination = download.destinationPath
                        ?: throw IllegalStateException("Download ${download.id} is missing a destination")
                    DownloadTransferService.startTransfer(
                        appContext,
                        download.id,
                        DownloadPartFile.forDestination(File(destination)).absolutePath,
                    )
                },
            ).also { queueScheduler = it }
        }
    }

    fun submissionCoordinator(context: Context): DownloadSubmissionCoordinator = submissionCoordinator ?: synchronized(this) {
        submissionCoordinator ?: run {
            val appContext = context.applicationContext
            val repo = downloads(appContext)
            val retriever = metadataRetriever()
            val directoryProvider = { AppSpecificDownloadsDirectory.from(appContext) }
            DownloadSubmissionCoordinator(
                metadataRetriever = retriever,
                repository = repo,
                directoryProvider = directoryProvider,
                queueScheduler = queueScheduler(appContext),
            ).also { submissionCoordinator = it }
        }
    }
}
