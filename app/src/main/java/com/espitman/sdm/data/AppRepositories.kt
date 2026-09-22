package com.espitman.sdm.data

import android.content.Context
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.download.AggregateSpeedLimiter
import com.espitman.sdm.download.AndroidValidatedConnectivityMonitor
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.DownloadInterruptionRecovery
import com.espitman.sdm.download.DownloadInterruptionTrigger
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.download.DownloadQueueScheduler
import com.espitman.sdm.download.DownloadRecoveryOnceGate
import com.espitman.sdm.download.DownloadSubmissionCoordinator
import com.espitman.sdm.download.DownloadTransferEngine
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.download.MutableTransferAllowance
import com.espitman.sdm.download.NetworkRestrictionCoordinator
import com.espitman.sdm.download.SpeedLimitPolicy
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null
    @Volatile private var metadataRetriever: DownloadMetadataRetriever? = null
    @Volatile private var transferEngine: DownloadTransferEngine? = null
    @Volatile private var speedLimiter: AggregateSpeedLimiter? = null
    @Volatile private var queueScheduler: DownloadQueueScheduler? = null
    @Volatile private var transferAllowance: MutableTransferAllowance? = null
    @Volatile private var networkRestriction: NetworkRestrictionCoordinator? = null
    @Volatile private var connectivityMonitor: AndroidValidatedConnectivityMonitor? = null
    @Volatile private var restrictionCollectorStarted = false
    @Volatile private var speedLimitCollectorStarted = false
    private val restrictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    fun transferEngine(context: Context): DownloadTransferEngine {
        transferEngine?.let { return it }
        val limiter = speedLimiter(context)
        synchronized(this) {
            transferEngine?.let { return it }
            val appContext = context.applicationContext
            return DownloadTransferEngine(
                destinationPublisher = SafDownloadDestinationPublisher(
                    trees = DocumentsContractTreeAccess(appContext.contentResolver),
                    coordinator = saveLocation(appContext),
                    appSpecificDirectory = { AppSpecificDownloadsDirectory.from(appContext) },
                ),
                storageCapacity = storageCapacityProbe(appContext),
                speedLimiter = limiter,
            ).also { transferEngine = it }
        }
    }

    fun speedLimiter(context: Context): AggregateSpeedLimiter {
        speedLimiter?.let { return it }
        synchronized(this) {
            return speedLimiterLocked(context.applicationContext)
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
                autoResume = SettingsRepository.get(context).settings.value.autoResume,
            )
        }
        networkRestriction(context).apply()
    }

    fun transferAllowance(context: Context): MutableTransferAllowance {
        ensureNetworkRestriction(context)
        return transferAllowance!!
    }

    fun networkRestriction(context: Context): NetworkRestrictionCoordinator {
        ensureNetworkRestriction(context)
        return networkRestriction!!
    }

    fun queueScheduler(context: Context): DownloadQueueScheduler {
        ensureNetworkRestriction(context)
        return queueScheduler!!
    }

    private fun ensureNetworkRestriction(context: Context) {
        if (networkRestriction != null && queueScheduler != null && restrictionCollectorStarted) return
        synchronized(this) {
            val appContext = context.applicationContext
            val allowance = transferAllowance ?: MutableTransferAllowance(initiallyAllowed = false)
                .also { transferAllowance = it }
            if (queueScheduler == null) {
                queueScheduler = DownloadQueueScheduler(
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
                    transferAllowance = allowance,
                )
            }
            if (connectivityMonitor == null) {
                connectivityMonitor = AndroidValidatedConnectivityMonitor(appContext)
            }
            speedLimiterLocked(appContext)
            if (networkRestriction == null) {
                val monitor = connectivityMonitor!!
                networkRestriction = NetworkRestrictionCoordinator(
                    repository = downloads(appContext),
                    wifiOnly = { SettingsRepository.get(appContext).settings.value.wifiOnly },
                    connectivity = { monitor.current() },
                    allowance = allowance,
                    scheduler = queueScheduler!!,
                    pauseActive = { id ->
                        DownloadTransferService.pauseTransfer(
                            appContext,
                            id,
                            DownloadPauseCause.NETWORK_POLICY,
                        )
                    },
                    autoResume = { SettingsRepository.get(appContext).settings.value.autoResume },
                ).also { it.syncAllowanceFromSnapshot() }
            }
            if (!restrictionCollectorStarted) {
                restrictionCollectorStarted = true
                val monitor = connectivityMonitor!!
                val coordinator = networkRestriction!!
                restrictionScope.launch {
                    combine(
                        downloads(appContext).downloads
                            .map { records -> records.map { it.id to it.state } }
                            .distinctUntilChanged(),
                        SettingsRepository.get(appContext).settings
                            .map { settings -> settings.wifiOnly to settings.autoResume }
                            .distinctUntilChanged(),
                        monitor.connectivity,
                    ) { _, _, _ -> }
                        .collect { coordinator.apply() }
                }
            }
        }
    }

    private fun speedLimiterLocked(appContext: Context): AggregateSpeedLimiter {
        speedLimiter?.let { return it }
        if (connectivityMonitor == null) {
            connectivityMonitor = AndroidValidatedConnectivityMonitor(appContext)
        }
        val monitor = connectivityMonitor!!
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = {
                val settings = SettingsRepository.get(appContext).settings.value
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = settings.unlimitedSpeed,
                    speedLimitMbps = settings.speedLimitMbps,
                    speedLimitWifiOnly = settings.speedLimitWifiOnly,
                    transport = monitor.current().transport,
                )
            },
        )
        speedLimiter = limiter
        if (!speedLimitCollectorStarted) {
            speedLimitCollectorStarted = true
            restrictionScope.launch {
                combine(
                    SettingsRepository.get(appContext).settings
                        .map { settings ->
                            Triple(
                                settings.unlimitedSpeed,
                                settings.speedLimitMbps,
                                settings.speedLimitWifiOnly,
                            )
                        }
                        .distinctUntilChanged(),
                    monitor.connectivity
                        .map { it.transport }
                        .distinctUntilChanged(),
                ) { _, _ -> }
                    .collect { limiter.notifyPolicyChanged() }
            }
        }
        return limiter
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
