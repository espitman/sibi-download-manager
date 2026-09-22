package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.PauseQueuedMutation
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
    ) = coordinator(
        repo = repo,
        allowance = allowance,
        scheduler = scheduler,
        wifiOnly = { wifiOnly },
        connectivity = { ValidatedConnectivity(transport) },
        pauseActive = pauseActive,
    )

    private fun coordinator(
        repo: FakeRepo,
        allowance: MutableTransferAllowance,
        scheduler: DownloadQueueScheduler,
        wifiOnly: () -> Boolean,
        connectivity: () -> ValidatedConnectivity,
        pauseActive: (String) -> Unit = {},
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
