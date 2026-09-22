package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.PauseQueuedMutation
import com.espitman.sdm.domain.RecoverInterruptedActiveMutation
import com.espitman.sdm.domain.RequeueNetworkPausedMutation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NetworkRestrictionCoordinatorTest {
    @Test
    fun selectedSettingCombinationsDriveEveryRuntimePolicyTogether() = runBlocking {
        data class Case(
            val name: String,
            val settings: SdmSettings,
            val transport: ValidatedTransport,
            val expectedAllowed: Boolean,
            val expectedBytesPerSecond: Long?,
            val expectedAutoRecovery: Boolean,
        )

        val cases = listOf(
            Case(
                name = "wifi-capped-auto",
                settings = SdmSettings(
                    connections = 8,
                    simultaneous = 2,
                    autoResume = true,
                    wifiOnly = true,
                    speedLimitMbps = 4f,
                ),
                transport = ValidatedTransport.WIFI,
                expectedAllowed = true,
                expectedBytesPerSecond = 4_000_000L,
                expectedAutoRecovery = true,
            ),
            Case(
                name = "cellular-blocked-wifi-cap",
                settings = SdmSettings(
                    connections = 16,
                    simultaneous = 4,
                    autoResume = true,
                    wifiOnly = true,
                    speedLimitMbps = 9f,
                    speedLimitWifiOnly = true,
                ),
                transport = ValidatedTransport.CELLULAR,
                expectedAllowed = false,
                expectedBytesPerSecond = null,
                expectedAutoRecovery = false,
            ),
            Case(
                name = "cellular-allowed-manual-recovery",
                settings = SdmSettings(
                    connections = 24,
                    simultaneous = 6,
                    autoResume = false,
                    wifiOnly = false,
                    speedLimitMbps = 6f,
                ),
                transport = ValidatedTransport.CELLULAR,
                expectedAllowed = true,
                expectedBytesPerSecond = 6_000_000L,
                expectedAutoRecovery = false,
            ),
            Case(
                name = "ethernet-unlimited-auto",
                settings = SdmSettings(
                    connections = 32,
                    simultaneous = 10,
                    autoResume = true,
                    wifiOnly = true,
                    unlimitedSpeed = true,
                    speedLimitMbps = 30f,
                ),
                transport = ValidatedTransport.ETHERNET,
                expectedAllowed = true,
                expectedBytesPerSecond = null,
                expectedAutoRecovery = true,
            ),
        )

        for (case in cases) {
            val connectivity = ValidatedConnectivity(case.transport)
            assertEquals(
                case.name,
                case.expectedAllowed,
                WifiOnlyPolicy.allowsTransfers(case.settings.wifiOnly, connectivity),
            )
            assertEquals(
                case.name,
                case.expectedBytesPerSecond,
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = case.settings.unlimitedSpeed,
                    speedLimitMbps = case.settings.speedLimitMbps,
                    speedLimitWifiOnly = case.settings.speedLimitWifiOnly,
                    transport = case.transport,
                ),
            )

            val active = active("active-${case.name}", DownloadState.DOWNLOADING, 1L)
            val waiting = (1..12).map {
                queued("${case.name}-$it").copy(createdAtEpochMillis = it.toLong())
            }
            assertEquals(
                case.name,
                (case.settings.simultaneous - 1).coerceAtLeast(0),
                DownloadQueuePolicy.select(listOf(active) + waiting, case.settings.simultaneous).size,
            )

            val segmented = queued("segment-${case.name}").copy(
                totalBytes = 8L * 1024L * 1024L,
                acceptsRanges = true,
                etag = "\"${case.name}\"",
            )
            assertEquals(
                case.name,
                case.settings.connections,
                SegmentedTransferPolicy.plan(segmented, 0L, case.settings.connections)!!.size,
            )

            val recoveryRepo = FakeRepo(
                listOf(paused("recover-${case.name}").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY)),
            )
            val starter = RecordingStarter()
            val allowance = MutableTransferAllowance()
            val scheduler = DownloadQueueScheduler(
                recoveryRepo,
                { case.settings.simultaneous },
                starter,
                transferAllowance = allowance,
            )
            coordinator(
                repo = recoveryRepo,
                allowance = allowance,
                scheduler = scheduler,
                wifiOnly = case.settings.wifiOnly,
                transport = case.transport,
                autoResume = { case.settings.autoResume },
            ).apply()
            assertEquals(case.name, case.expectedAllowed, allowance.isAllowed())
            assertEquals(
                case.name,
                case.expectedAutoRecovery,
                starter.startedIds().contains("recover-${case.name}"),
            )
        }
    }

    @Test
    fun snapshotBlocksCellularWhenWifiOnlyBeforeAnySchedule() = runBlocking {
        val repo = FakeRepo(
            listOf(
                queued("waiting"),
                active("live", DownloadState.DOWNLOADING, downloadedBytes = 25L),
                paused("manual"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = true)
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val pausedActive = CopyOnWriteArrayList<String>()
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = true,
            transport = ValidatedTransport.CELLULAR,
            pauseActive = { pausedActive += it },
        )

        coordinator.syncAllowanceFromSnapshot()
        assertFalse(allowance.isAllowed())
        scheduler.schedule()
        assertTrue(starter.startedIds().isEmpty())

        coordinator.apply()
        assertEquals(DownloadState.PAUSED, repo.get("waiting")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("waiting")!!.pauseCause)
        assertEquals(listOf("live"), pausedActive.toList())
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertTrue(starter.startedIds().isEmpty())
    }

    @Test
    fun networkLossAndMobileFallbackPauseActiveWorkUntilWifiReturns() = runBlocking {
        val repo = FakeRepo(
            listOf(
                active("partial", DownloadState.DOWNLOADING, downloadedBytes = 20L),
                paused("manual", downloadedBytes = 7L),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter, transferAllowance = allowance)
        val transport = AtomicReference(ValidatedTransport.WIFI)
        val pauseRequests = CopyOnWriteArrayList<String>()
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = { true },
            connectivity = { ValidatedConnectivity(transport.get()) },
            pauseActive = { pauseRequests += it },
        )

        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertTrue(starter.startedIds().isEmpty())

        transport.set(ValidatedTransport.NONE)
        coordinator.apply()
        assertFalse(allowance.isAllowed())
        assertEquals(listOf("partial"), pauseRequests)
        val pausedAt = repo.pauseAtExactOffset(
            id = "partial",
            fileLengthBytes = 29L,
            nowEpochMillis = 8_000L,
            pauseCause = DownloadPauseCause.NETWORK_POLICY,
        )!!
        assertEquals(DownloadState.PAUSED, pausedAt.state)
        assertEquals(29L, pausedAt.downloadedBytes)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, pausedAt.pauseCause)

        transport.set(ValidatedTransport.CELLULAR)
        coordinator.apply()
        assertFalse(allowance.isAllowed())
        assertEquals(listOf("partial"), pauseRequests)
        assertTrue(starter.startedIds().isEmpty())

        transport.set(ValidatedTransport.WIFI)
        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertEquals(listOf("partial"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("partial")!!.state)
        assertEquals(29L, repo.get("partial")!!.downloadedBytes)
        assertNull(repo.get("partial")!!.pauseCause)
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertEquals(7L, repo.get("manual")!!.downloadedBytes)
    }

    @Test
    fun ethernetIsAllowedWhenWifiOnlyMatchesPauseOnMobileDataCopy() = runBlocking {
        val repo = FakeRepo(listOf(queued("waiting")))
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = true,
            transport = ValidatedTransport.ETHERNET,
        )

        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertEquals(listOf("waiting"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("waiting")!!.state)
    }

    @Test
    fun turningWifiOnlyOffOnCellularRequeuesOnlyNetworkPausedRecords() = runBlocking {
        val repo = FakeRepo(
            listOf(
                paused("network").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY, downloadedBytes = 8L),
                paused("manual", downloadedBytes = 9L),
                queued("already-queued"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val wifiOnly = AtomicBoolean(true)
        val transport = AtomicReference(ValidatedTransport.CELLULAR)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = { wifiOnly.get() },
            connectivity = { ValidatedConnectivity(transport.get()) },
        )

        coordinator.apply()
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("already-queued")!!.pauseCause)

        wifiOnly.set(false)
        coordinator.apply()
        assertEquals(DownloadState.QUEUED, repo.get("network")!!.state)
        assertNull(repo.get("network")!!.pauseCause)
        assertEquals(8L, repo.get("network")!!.downloadedBytes)
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertEquals(setOf("network", "already-queued"), starter.startedIds().toSet())
    }

    @Test
    fun applyIsIdempotentAndDoesNotSpinBlockedQueuedWork() = runBlocking {
        val repo = FakeRepo(listOf(queued("waiting")))
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter, transferAllowance = allowance)
        val pauseCalls = AtomicInteger(0)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = true,
            transport = ValidatedTransport.NONE,
            pauseActive = { pauseCalls.incrementAndGet() },
        )

        repeat(4) { coordinator.apply() }
        scheduler.schedule()
        scheduler.schedule()
        assertEquals(DownloadState.PAUSED, repo.get("waiting")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("waiting")!!.pauseCause)
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(0, pauseCalls.get())
    }

    @Test
    fun autoResumeOffLeavesNetworkPausedWhenTransfersBecomeAllowed() = runBlocking {
        val repo = FakeRepo(
            listOf(
                paused("network").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY, downloadedBytes = 8L),
                paused("manual", downloadedBytes = 9L),
                queued("already-queued"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val wifiOnly = AtomicBoolean(true)
        val autoResume = AtomicBoolean(false)
        val transport = AtomicReference(ValidatedTransport.CELLULAR)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = { wifiOnly.get() },
            connectivity = { ValidatedConnectivity(transport.get()) },
            autoResume = { autoResume.get() },
        )

        coordinator.apply()
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(DownloadState.PAUSED, repo.get("already-queued")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("already-queued")!!.pauseCause)

        wifiOnly.set(false)
        coordinator.apply()
        assertEquals(DownloadState.PAUSED, repo.get("network")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("network")!!.pauseCause)
        assertEquals(8L, repo.get("network")!!.downloadedBytes)
        assertEquals(DownloadState.PAUSED, repo.get("already-queued")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("already-queued")!!.pauseCause)
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertTrue(starter.startedIds().isEmpty())
    }

    @Test
    fun turningAutoResumeOnWhileAllowedRequeuesNetworkPausedAndSchedules() = runBlocking {
        val repo = FakeRepo(
            listOf(
                paused("network").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY, downloadedBytes = 15L),
                paused("manual", downloadedBytes = 4L),
                active("live", DownloadState.DOWNLOADING, downloadedBytes = 20L),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val autoResume = AtomicBoolean(false)
        val pausedActive = CopyOnWriteArrayList<String>()
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.CELLULAR,
            pauseActive = { pausedActive += it },
            autoResume = { autoResume.get() },
        )

        coordinator.apply()
        assertEquals(DownloadState.PAUSED, repo.get("network")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("network")!!.pauseCause)
        assertTrue(starter.startedIds().isEmpty())
        assertTrue(pausedActive.isEmpty())
        assertEquals(DownloadState.DOWNLOADING, repo.get("live")!!.state)

        autoResume.set(true)
        coordinator.apply()
        assertEquals(DownloadState.QUEUED, repo.get("network")!!.state)
        assertNull(repo.get("network")!!.pauseCause)
        assertEquals(15L, repo.get("network")!!.downloadedBytes)
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertEquals(listOf("network"), starter.startedIds())
        assertTrue(pausedActive.isEmpty())
        assertEquals(DownloadState.DOWNLOADING, repo.get("live")!!.state)
    }

    @Test
    fun turningAutoResumeOffDoesNotPauseHealthyActiveTransfers() = runBlocking {
        val repo = FakeRepo(
            listOf(
                active("live", DownloadState.DOWNLOADING, downloadedBytes = 30L),
                queued("waiting"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = true)
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val autoResume = AtomicBoolean(true)
        val pausedActive = CopyOnWriteArrayList<String>()
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.WIFI,
            pauseActive = { pausedActive += it },
            autoResume = { autoResume.get() },
        )

        coordinator.apply()
        assertEquals(listOf("waiting"), starter.startedIds())
        assertEquals(DownloadState.DOWNLOADING, repo.get("live")!!.state)

        autoResume.set(false)
        coordinator.apply()
        assertTrue(pausedActive.isEmpty())
        assertEquals(DownloadState.DOWNLOADING, repo.get("live")!!.state)
        assertEquals(DownloadState.QUEUED, repo.get("waiting")!!.state)
        assertTrue(allowance.isAllowed())
    }

    @Test
    fun startGuardPausesQueuedAndActiveWithoutRecordingFailure() = runBlocking {
        val repo = FakeRepo(
            listOf(
                queued("waiting", downloadedBytes = 3L),
                active("live", DownloadState.CONNECTING, downloadedBytes = 11L),
            ),
        )
        assertTrue(
            NetworkRestrictionStartGuard.blockStartIfDisallowed(
                allowed = false,
                repository = repo,
                downloadId = "waiting",
                fileLengthBytes = 3L,
                nowEpochMillis = 5_000L,
            ),
        )
        assertFalse(
            NetworkRestrictionStartGuard.blockStartIfDisallowed(
                allowed = true,
                repository = repo,
                downloadId = "live",
                fileLengthBytes = 11L,
                nowEpochMillis = 5_000L,
            ),
        )
        assertTrue(
            NetworkRestrictionStartGuard.blockStartIfDisallowed(
                allowed = false,
                repository = repo,
                downloadId = "live",
                fileLengthBytes = 11L,
                nowEpochMillis = 5_000L,
            ),
        )
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("waiting")!!.pauseCause)
        assertEquals(3L, repo.get("waiting")!!.downloadedBytes)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("live")!!.pauseCause)
        assertEquals(11L, repo.get("live")!!.downloadedBytes)
        assertNull(repo.get("waiting")!!.error)
        assertNull(repo.get("live")!!.error)
    }

    private fun coordinator(
        repo: FakeRepo,
        allowance: MutableTransferAllowance,
        scheduler: DownloadQueueScheduler,
        wifiOnly: Boolean,
        transport: ValidatedTransport,
        pauseActive: (String) -> Unit = {},
        autoResume: () -> Boolean = { true },
    ) = coordinator(
        repo = repo,
        allowance = allowance,
        scheduler = scheduler,
        wifiOnly = { wifiOnly },
        connectivity = { ValidatedConnectivity(transport) },
        pauseActive = pauseActive,
        autoResume = autoResume,
    )

    private fun coordinator(
        repo: FakeRepo,
        allowance: MutableTransferAllowance,
        scheduler: DownloadQueueScheduler,
        wifiOnly: () -> Boolean,
        connectivity: () -> ValidatedConnectivity,
        pauseActive: (String) -> Unit = {},
        autoResume: () -> Boolean = { true },
    ) = NetworkRestrictionCoordinator(
        repository = repo,
        wifiOnly = wifiOnly,
        connectivity = connectivity,
        allowance = allowance,
        scheduler = scheduler,
        pauseActive = pauseActive,
        clock = object : Clock {
            override fun currentTimeMillis(): Long = 8_000L
        },
        autoResume = autoResume,
    )

    private fun queued(id: String, downloadedBytes: Long = 0L) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        downloadedBytes = downloadedBytes,
        createdAtEpochMillis = 1_000L,
    )

    private fun paused(id: String, downloadedBytes: Long = 0L) = DownloadStateMachine.transition(
        DownloadStateMachine.transition(queued(id, downloadedBytes), DownloadState.CONNECTING, 2_000),
        DownloadState.PAUSED,
        3_000,
    )

    private fun active(id: String, state: DownloadState, downloadedBytes: Long) =
        DownloadStateMachine.transition(queued(id, downloadedBytes), DownloadState.CONNECTING, 2_000)
            .let { connecting ->
                if (state == DownloadState.DOWNLOADING) {
                    DownloadStateMachine.transition(connecting, DownloadState.DOWNLOADING, 3_000)
                } else {
                    connecting
                }
            }.copy(downloadedBytes = downloadedBytes)

    private class RecordingStarter : QueuedTransferStarter {
        private val started = CopyOnWriteArrayList<String>()
        override fun startQueued(download: Download) {
            started += download.id
        }
        fun startedIds(): List<String> = started.toList()
    }

    private class FakeRepo(
        initial: List<Download>,
    ) : DownloadRepository {
        private val mutex = Mutex()
        private val _downloads = MutableStateFlow(initial)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? = mutex.withLock {
            _downloads.value.find { it.id == id }
        }

        override suspend fun schedulingSnapshot(): List<Download> = mutex.withLock { _downloads.value }

        override suspend fun insert(download: Download) = mutex.withLock { replace(download) }

        override suspend fun delete(id: String): Boolean = mutex.withLock {
            val existed = _downloads.value.any { it.id == id }
            _downloads.value = _downloads.value.filterNot { it.id == id }
            existed
        }

        override suspend fun transition(
            id: String,
            to: DownloadState,
            nowEpochMillis: Long,
            error: String?,
        ): Download = mutex.withLock {
            val current = _downloads.value.first { it.id == id }
            val updated = DownloadStateMachine.transition(current, to, nowEpochMillis, error)
            replace(updated)
            updated
        }

        override suspend fun updateProgress(
            id: String,
            downloadedBytes: Long,
            nowEpochMillis: Long,
        ): Download = error("unused")

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = pauseAtExactOffset(id, fileLengthBytes, nowEpochMillis, null)

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
            pauseCause: DownloadPauseCause?,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis, pauseCause)
            if (paused != current) replace(paused)
            paused
        }

        override suspend fun cancelAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = error("unused")

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = DownloadResumeMutation.apply(current, nowEpochMillis) ?: return@withLock null
            replace(queued)
            queued
        }

        override suspend fun pauseQueuedPreservingOffsets(
            nowEpochMillis: Long,
            pauseCause: DownloadPauseCause?,
        ): List<Download> = mutex.withLock {
            val updated = ArrayList<Download>()
            _downloads.value = _downloads.value.map { current ->
                val next = PauseQueuedMutation.apply(current, nowEpochMillis, pauseCause) ?: return@map current
                updated += next
                next
            }
            updated
        }

        override suspend fun requeueInterruptedActive(
            id: String,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = RecoverInterruptedActiveMutation.apply(current, nowEpochMillis)
                ?: return@withLock null
            replace(queued)
            queued
        }

        override suspend fun requeueNetworkPolicyPaused(nowEpochMillis: Long): List<Download> = mutex.withLock {
            val updated = ArrayList<Download>()
            _downloads.value = _downloads.value.map { current ->
                val next = RequeueNetworkPausedMutation.apply(current, nowEpochMillis) ?: return@map current
                updated += next
                next
            }
            updated
        }

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = error("unused")

        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ): Download = error("unused")

        private fun replace(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
    }
}
