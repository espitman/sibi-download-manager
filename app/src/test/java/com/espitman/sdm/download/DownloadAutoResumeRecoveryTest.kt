package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadRetryFailedMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.PauseQueuedMutation
import com.espitman.sdm.domain.RecoverInterruptedActiveMutation
import com.espitman.sdm.domain.RequeueNetworkPausedMutation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

class DownloadAutoResumeRecoveryTest {
    @Test
    fun recoverThenApplyDoesNotStartBeforeAllowanceSnapshot() = runBlocking {
        val repo = FakeRepo(
            listOf(
                active("stale", DownloadState.DOWNLOADING, downloadedBytes = 22L),
                queued("waiting"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = true,
            transport = ValidatedTransport.WIFI,
        )

        assertFalse(allowance.isAllowed())
        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )
        assertEquals(DownloadState.QUEUED, repo.get("stale")!!.state)
        assertEquals(22L, repo.get("stale")!!.downloadedBytes)
        assertEquals(0, repo.retryFailedCalls.get())
        assertFalse(allowance.isAllowed())
        assertTrue(starter.startedIds().isEmpty())

        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertEquals(setOf("stale", "waiting"), starter.startedIds().toSet())
    }

    @Test
    fun recoveredWorkStaysPausedWhenNetworkStillDisallowed() = runBlocking {
        val repo = FakeRepo(
            listOf(active("stale", DownloadState.CONNECTING, downloadedBytes = 5L)),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = true,
            transport = ValidatedTransport.CELLULAR,
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = true,
        )
        assertEquals(DownloadState.QUEUED, repo.get("stale")!!.state)
        assertTrue(starter.startedIds().isEmpty())

        coordinator.apply()
        assertFalse(allowance.isAllowed())
        assertEquals(DownloadState.PAUSED, repo.get("stale")!!.state)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, repo.get("stale")!!.pauseCause)
        assertEquals(5L, repo.get("stale")!!.downloadedBytes)
        assertTrue(starter.startedIds().isEmpty())
    }

    @Test
    fun recoveredWorkRespectsConcurrencyAfterAllowanceIsEstablished() = runBlocking {
        val repo = FakeRepo(
            listOf(
                active("first", DownloadState.DOWNLOADING, downloadedBytes = 1L),
                active("second", DownloadState.CONNECTING, downloadedBytes = 2L),
                active("third", DownloadState.DOWNLOADING, downloadedBytes = 3L),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.CELLULAR,
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(0, repo.retryFailedCalls.get())

        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertEquals(2, starter.startedIds().size)
        assertTrue(starter.startedIds().contains("first"))
        assertTrue(starter.startedIds().contains("second"))
        assertEquals(DownloadState.QUEUED, repo.get("third")!!.state)
        assertEquals(3L, repo.get("third")!!.downloadedBytes)
    }

    @Test
    fun autoResumeOffKeepsStableFailedMessagesAndDoesNotSchedule() = runBlocking {
        val repo = FakeRepo(
            listOf(
                active("stale", DownloadState.DOWNLOADING, downloadedBytes = 11L),
                download("http-failed", DownloadState.FAILED, downloadedBytes = 6L, error = "HTTP 404: Not Found"),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.WIFI,
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = false,
        )
        assertEquals(DownloadState.FAILED, repo.get("stale")!!.state)
        assertEquals(DownloadInterruptionTrigger.DEVICE_BOOT.errorMessage, repo.get("stale")!!.error)
        assertEquals("HTTP 404: Not Found", repo.get("http-failed")!!.error)
        assertTrue(starter.startedIds().isEmpty())

        coordinator.apply()
        assertTrue(allowance.isAllowed())
        assertEquals(DownloadState.FAILED, repo.get("stale")!!.state)
        assertEquals(DownloadInterruptionTrigger.DEVICE_BOOT.errorMessage, repo.get("stale")!!.error)
        assertEquals(DownloadState.FAILED, repo.get("http-failed")!!.state)
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(0, repo.retryFailedCalls.get())
    }

    @Test
    fun nonrecoverableStatesStayPutAcrossRecoveryAndAutoResumeToggle() = runBlocking {
        val repo = FakeRepo(
            listOf(
                download("http-failed", DownloadState.FAILED, downloadedBytes = 7L, error = "HTTP 416: Range Not Satisfiable"),
                download("io-failed", DownloadState.FAILED, downloadedBytes = 3L, error = "java.io.IOException: disk"),
                download("storage-failed", DownloadState.FAILED, downloadedBytes = 1L, error = "Insufficient storage for destination"),
                download("integrity-failed", DownloadState.FAILED, downloadedBytes = 2L, error = "Checksum mismatch"),
                download("cancelled", DownloadState.CANCELLED, downloadedBytes = 4L),
                download(
                    "completed",
                    DownloadState.COMPLETED,
                    downloadedBytes = 100L,
                    totalBytes = 100L,
                    completedAt = 2_000L,
                ),
                paused("manual", downloadedBytes = 9L),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val autoResume = AtomicBoolean(false)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.WIFI,
            autoResume = { autoResume.get() },
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )
        coordinator.apply()
        autoResume.set(true)
        coordinator.apply()

        assertEquals(DownloadState.FAILED, repo.get("http-failed")!!.state)
        assertEquals("HTTP 416: Range Not Satisfiable", repo.get("http-failed")!!.error)
        assertEquals(DownloadState.FAILED, repo.get("io-failed")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("storage-failed")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("integrity-failed")!!.state)
        assertEquals(DownloadState.CANCELLED, repo.get("cancelled")!!.state)
        assertEquals(DownloadState.COMPLETED, repo.get("completed")!!.state)
        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(0, repo.retryFailedCalls.get())
    }

    @Test
    fun manualPauseStaysPausedAcrossConnectivityAndAutoResumeChanges() = runBlocking {
        val repo = FakeRepo(
            listOf(
                paused("manual", downloadedBytes = 18L),
                paused("network").copy(pauseCause = DownloadPauseCause.NETWORK_POLICY, downloadedBytes = 12L),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter, transferAllowance = allowance)
        val wifiOnly = AtomicBoolean(true)
        val autoResume = AtomicBoolean(true)
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
        wifiOnly.set(false)
        coordinator.apply()
        autoResume.set(false)
        coordinator.apply()
        autoResume.set(true)
        coordinator.apply()
        transport.set(ValidatedTransport.NONE)
        coordinator.apply()
        transport.set(ValidatedTransport.WIFI)
        coordinator.apply()

        assertEquals(DownloadState.PAUSED, repo.get("manual")!!.state)
        assertNull(repo.get("manual")!!.pauseCause)
        assertEquals(18L, repo.get("manual")!!.downloadedBytes)
        assertEquals(DownloadState.QUEUED, repo.get("network")!!.state)
        assertEquals(12L, repo.get("network")!!.downloadedBytes)
        assertEquals(listOf("network"), starter.startedIds())
    }

    @Test
    fun concurrentRecoverAndApplyRemainIdempotent() = runBlocking {
        val repo = FakeRepo(
            listOf(active("stale", DownloadState.DOWNLOADING, downloadedBytes = 14L)),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter, transferAllowance = allowance)
        val coordinator = coordinator(
            repo = repo,
            allowance = allowance,
            scheduler = scheduler,
            wifiOnly = false,
            transport = ValidatedTransport.ETHERNET,
        )

        coroutineScope {
            val recoveries = List(4) {
                async {
                    DownloadInterruptionRecovery.recover(
                        repo,
                        clock,
                        DownloadInterruptionTrigger.PROCESS_RESTART,
                        autoResume = true,
                    )
                }
            }
            val applies = List(4) { async { coordinator.apply() } }
            (recoveries + applies).awaitAll()
        }
        coordinator.apply()

        assertEquals(DownloadState.QUEUED, repo.get("stale")!!.state)
        assertNull(repo.get("stale")!!.error)
        assertEquals(14L, repo.get("stale")!!.downloadedBytes)
        assertEquals(0, repo.retryFailedCalls.get())
        assertEquals(1, starter.startedIds().count { it == "stale" })
        assertTrue(allowance.isAllowed())
    }

    private val clock = object : Clock {
        override fun currentTimeMillis(): Long = 8_000L
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
        clock = clock,
        autoResume = autoResume,
    )

    private fun queued(id: String, downloadedBytes: Long = 0L) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        totalBytes = 100L,
        downloadedBytes = downloadedBytes,
        createdAtEpochMillis = 1_000L,
    )

    private fun download(
        id: String,
        state: DownloadState,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 100L,
        error: String? = null,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        completedAtEpochMillis = completedAt,
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
        val retryFailedCalls = AtomicInteger(0)

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

        override suspend fun retryFailed(
            id: String,
            automatic: Boolean,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            retryFailedCalls.incrementAndGet()
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = DownloadRetryFailedMutation.apply(current, automatic, nowEpochMillis)
                ?: return@withLock null
            replace(queued)
            queued
        }

        override suspend fun requeueInterruptedActive(
            id: String,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = RecoverInterruptedActiveMutation.apply(current, nowEpochMillis) ?: return@withLock null
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
