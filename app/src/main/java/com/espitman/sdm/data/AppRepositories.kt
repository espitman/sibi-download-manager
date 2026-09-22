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
import com.espitman.sdm.storage.AndroidStorageCapacityProbe
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import com.espitman.sdm.storage.DocumentsContractTreeAccess
import com.espitman.sdm.storage.DownloadDestinationRef
import com.espitman.sdm.storage.PersistableTreeUriGrants
import com.espitman.sdm.storage.SafDownloadDestinationPublisher
import com.espitman.sdm.storage.SaveLocationCoordinator
import com.espitman.sdm.storage.SaveLocationDestinationAllocator
import com.espitman.sdm.storage.SaveLocationStorageCapacity
import com.espitman.sdm.storage.SaveLocationStore
import com.espitman.sdm.storage.StorageCapacityProbe
import java.io.File

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null
    @Volatile private var metadataRetriever: DownloadMetadataRetriever? = null
    @Volatile private var transferEngine: DownloadTransferEngine? = null
    @Volatile private var queueScheduler: DownloadQueueScheduler? = null
    @Volatile private var submissionCoordinator: DownloadSubmissionCoordinator? = null
    @Volatile private var saveLocationCoordinator: SaveLocationCoordinator? = null
    @Volatile private var storageCapacityProbe: StorageCapacityProbe? = null
    @Volatile private var saveLocationStorageCapacity: SaveLocationStorageCapacity? = null

    fun downloads(context: Context): DownloadRepository = downloadRepository ?: synchronized(this) {
        downloadRepository ?: SqliteDownloadRepository(context.applicationContext).also { downloadRepository = it }
    }

    fun metadataRetriever(): DownloadMetadataRetriever = metadataRetriever ?: synchronized(this) {
        metadataRetriever ?: HttpDownloadMetadataRetriever().also { metadataRetriever = it }
    }

    fun saveLocation(context: Context): SaveLocationCoordinator = saveLocationCoordinator ?: synchronized(this) {
        saveLocationCoordinator ?: run {
            val appContext = context.applicationContext
            SaveLocationCoordinator(
                store = SaveLocationStore.get(appContext),
                grants = PersistableTreeUriGrants(appContext.contentResolver),
                trees = DocumentsContractTreeAccess(appContext.contentResolver),
            ).also { saveLocationCoordinator = it }
        }
    }

    fun storageCapacityProbe(context: Context): StorageCapacityProbe = storageCapacityProbe ?: synchronized(this) {
        storageCapacityProbe ?: AndroidStorageCapacityProbe(context.applicationContext).also {
            storageCapacityProbe = it
        }
    }

    fun storageCapacity(context: Context): SaveLocationStorageCapacity = saveLocationStorageCapacity ?: synchronized(this) {
        saveLocationStorageCapacity ?: run {
            val appContext = context.applicationContext
            SaveLocationStorageCapacity(
                currentLocation = { saveLocation(appContext).current() },
                appSpecificDirectory = { AppSpecificDownloadsDirectory.from(appContext) },
                probe = storageCapacityProbe(appContext),
            ).also { saveLocationStorageCapacity = it }
        }
    }

    fun transferEngine(context: Context): DownloadTransferEngine = transferEngine ?: synchronized(this) {
        transferEngine ?: run {
            val appContext = context.applicationContext
            DownloadTransferEngine(
                destinationPublisher = SafDownloadDestinationPublisher(
                    trees = DocumentsContractTreeAccess(appContext.contentResolver),
                    coordinator = saveLocation(appContext),
                    appSpecificDirectory = { AppSpecificDownloadsDirectory.from(appContext) },
                ),
                storageCapacity = storageCapacityProbe(appContext),
            ).also { transferEngine = it }
        }
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
                    if (DownloadDestinationRef.isContentUri(destination)) {
                        throw IllegalStateException("Download ${download.id} is missing a local destination")
                    }
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
                destinationAllocator = SaveLocationDestinationAllocator(
                    coordinator = saveLocation(appContext),
                    appSpecificDirectory = directoryProvider,
                    trees = DocumentsContractTreeAccess(appContext.contentResolver),
                ),
            ).also { submissionCoordinator = it }
        }
    }
}
