package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.DownloadCancelMutation
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.PauseQueuedMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DownloadBulkOperationsTest {
    @Test
    fun downloadAllRequeuesEligibleRecordsPreservesCompletedAndSchedulesOnce() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                item("active", DownloadState.CONNECTING),
                item("running", DownloadState.DOWNLOADING, downloadedBytes = 40L),
                item("waiting", DownloadState.QUEUED, priority = 0, createdAt = 1, downloadedBytes = 11L),
                item("paused", DownloadState.PAUSED, priority = 5, createdAt = 2, downloadedBytes = 1_234L),
                item("failed", DownloadState.FAILED, createdAt = 3, downloadedBytes = 50L, error = "stale"),
                item("cancelled", DownloadState.CANCELLED, createdAt = 4, downloadedBytes = 9L),
                item(
                    "done",
                    DownloadState.COMPLETED,
                    downloadedBytes = 100L,
                    totalBytes = 100L,
                    completedAt = 8_000L,
                ),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter)

        scheduler.downloadAll()

        assertEquals(DownloadState.CONNECTING, repo.get("active")!!.state)
        assertEquals(DownloadState.DOWNLOADING, repo.get("running")!!.state)
        assertEquals(DownloadState.QUEUED, repo.get("waiting")!!.state)
        assertEquals(11L, repo.get("waiting")!!.downloadedBytes)
        assertEquals(DownloadState.QUEUED, repo.get("paused")!!.state)
        assertEquals(1_234L, repo.get("paused")!!.downloadedBytes)
        assertEquals(null, repo.get("paused")!!.error)
        assertEquals(DownloadState.QUEUED, repo.get("failed")!!.state)
        assertEquals(50L, repo.get("failed")!!.downloadedBytes)
        assertEquals(null, repo.get("failed")!!.error)
        assertEquals(DownloadState.QUEUED, repo.get("cancelled")!!.state)
        assertEquals(9L, repo.get("cancelled")!!.downloadedBytes)
        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertEquals(100L, repo.get("done")!!.downloadedBytes)
        assertEquals(listOf("paused"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("waiting")!!.state)
    }

    @Test
    fun pauseAllPausesQueuedBeforeActiveDispatchAndLeavesTerminalStates() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                item("waiting-a", DownloadState.QUEUED, createdAt = 1, downloadedBytes = 21L),
                item("waiting-b", DownloadState.QUEUED, createdAt = 2, downloadedBytes = 22L),
                item("connecting", DownloadState.CONNECTING, downloadedBytes = 3L),
                item("running", DownloadState.DOWNLOADING, downloadedBytes = 4L),
                item("already-paused", DownloadState.PAUSED, downloadedBytes = 5L),
                item("failed", DownloadState.FAILED, error = "x", downloadedBytes = 6L),
                item("cancelled", DownloadState.CANCELLED, downloadedBytes = 7L),
                item(
                    "done",
                    DownloadState.COMPLETED,
                    downloadedBytes = 100L,
                    totalBytes = 100L,
                    completedAt = 8_000L,
                ),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)
        scheduler.schedule()
        val pausedActive = CopyOnWriteArrayList<String>()

        scheduler.pauseAll { pausedActive += it }

        assertEquals(DownloadState.PAUSED, repo.get("waiting-a")!!.state)
        assertEquals(21L, repo.get("waiting-a")!!.downloadedBytes)
        assertEquals(DownloadState.PAUSED, repo.get("waiting-b")!!.state)
        assertEquals(22L, repo.get("waiting-b")!!.downloadedBytes)
        assertEquals(DownloadState.CONNECTING, repo.get("connecting")!!.state)
        assertEquals(DownloadState.DOWNLOADING, repo.get("running")!!.state)
        assertEquals(DownloadState.PAUSED, repo.get("already-paused")!!.state)
        assertEquals(5L, repo.get("already-paused")!!.downloadedBytes)
        assertEquals(DownloadState.FAILED, repo.get("failed")!!.state)
        assertEquals("x", repo.get("failed")!!.error)
        assertEquals(DownloadState.CANCELLED, repo.get("cancelled")!!.state)
        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertEquals(setOf("connecting", "running"), pausedActive.toSet())
        assertTrue(repo.downloads.value.none { it.state == DownloadState.QUEUED })
    }

    @Test
    fun pauseAllPrunesClaimsSoLaterDownloadAllCanUseFreedSlots() = runBlocking {
        val repo = FakeDownloadRepository(
            List(3) { index -> item("q$index", DownloadState.QUEUED, createdAt = index.toLong() + 1) },
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)
        scheduler.schedule()
        assertEquals(listOf("q0", "q1"), starter.startedIds())

        scheduler.pauseAll { }
        assertTrue(repo.downloads.value.all { it.state == DownloadState.PAUSED })

        scheduler.downloadAll()
        assertEquals(listOf("q0", "q1", "q0", "q1"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("q2")!!.state)
    }

    @Test
    fun pauseAllStillPausesAClaimedItemThatBecomesConnectingDuringQueuedPause() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                item("claimed", DownloadState.QUEUED, createdAt = 1),
                item("waiting", DownloadState.QUEUED, createdAt = 2),
            ),
            promoteToConnectingDuringQueuedPause = setOf("claimed"),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)
        scheduler.schedule()
        val pausedActive = CopyOnWriteArrayList<String>()

        scheduler.pauseAll { pausedActive += it }

        assertEquals(DownloadState.CONNECTING, repo.get("claimed")!!.state)
        assertEquals(DownloadState.PAUSED, repo.get("waiting")!!.state)
        assertEquals(listOf("claimed"), pausedActive.toList())
        scheduler.schedule()
        assertEquals(listOf("claimed"), starter.startedIds())
        assertTrue(repo.downloads.value.none { it.state == DownloadState.QUEUED })
    }

    @Test
    fun rapidConcurrentDownloadAllDoesNotDuplicateStartsOrExceedConcurrency() = runBlocking {
        val repo = FakeDownloadRepository(
            List(6) { index ->
                item("item-$index", DownloadState.PAUSED, createdAt = index.toLong(), downloadedBytes = index.toLong())
            } + item("done", DownloadState.COMPLETED, downloadedBytes = 1L, totalBytes = 1L, completedAt = 1L),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        (1..24).map { async(Dispatchers.Default) { scheduler.downloadAll() } }.awaitAll()
        scheduler.downloadAll()

        assertEquals(listOf("item-0", "item-1"), starter.startedIds())
        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertEquals(1L, repo.get("done")!!.downloadedBytes)
        assertEquals(DownloadState.QUEUED, repo.get("item-2")!!.state)
    }

    @Test
    fun rapidConcurrentPauseAndDownloadAllKeepCompletedAndNeverStartIt() = runBlocking {
        val repo = FakeDownloadRepository(
            List(4) { index ->
                item("item-$index", DownloadState.PAUSED, createdAt = index.toLong(), downloadedBytes = (index + 1).toLong())
            } + item("done", DownloadState.COMPLETED, downloadedBytes = 1L, totalBytes = 1L, completedAt = 1L),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        (1..20).map { index ->
            async(Dispatchers.Default) {
                if (index % 2 == 0) scheduler.downloadAll() else scheduler.pauseAll { }
            }
        }.awaitAll()

        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertEquals(1L, repo.get("done")!!.downloadedBytes)
        assertTrue("done" !in starter.startedIds())
        repo.downloads.value.filter { it.id != "done" }.forEach { download ->
            assertTrue(download.state == DownloadState.QUEUED || download.state == DownloadState.PAUSED)
            assertEquals(null, download.error)
        }
    }

    @Test
    fun concurrentScheduleDuringPauseAllCannotRefillQueuedSlots() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                item("waiting-a", DownloadState.QUEUED, createdAt = 1),
                item("waiting-b", DownloadState.QUEUED, createdAt = 2),
                item("waiting-c", DownloadState.QUEUED, createdAt = 3),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        val pause = async(Dispatchers.Default) { scheduler.pauseAll { } }
        val schedules = (1..16).map { async(Dispatchers.Default) { scheduler.schedule() } }
        pause.await()
        schedules.awaitAll()

        assertTrue(repo.downloads.value.none { it.state == DownloadState.QUEUED })
        assertTrue(repo.downloads.value.all { it.state == DownloadState.PAUSED })
        assertTrue(starter.startedIds().size <= 2)
        assertTrue(starter.startedIds().toSet().size == starter.startedIds().size)
    }

    private fun item(
        id: String,
        state: DownloadState,
        priority: Int = 0,
        createdAt: Long = 1_000L,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 10_000L,
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
        priority = priority,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        completedAtEpochMillis = completedAt,
    )

    private class RecordingStarter : QueuedTransferStarter {
        private val started = CopyOnWriteArrayList<String>()

        override fun startQueued(download: Download) {
            started += download.id
        }

        fun startedIds(): List<String> = started.toList()
    }

    private class FakeDownloadRepository(
        initial: List<Download> = emptyList(),
        private val promoteToConnectingDuringQueuedPause: Set<String> = emptySet(),
    ) : DownloadRepository {
        private val mutex = Mutex()
        private val _downloads = MutableStateFlow(initial)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? = mutex.withLock {
            _downloads.value.find { it.id == id }
        }

        override suspend fun schedulingSnapshot(): List<Download> = mutex.withLock {
            _downloads.value
        }

        override suspend fun insert(download: Download) {
            mutex.withLock { replace(download) }
        }

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
        ): Download = mutex.withLock {
            val current = _downloads.value.first { it.id == id }
            val updated = current.copy(downloadedBytes = downloadedBytes, updatedAtEpochMillis = nowEpochMillis)
            replace(updated)
            updated
        }

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis)
            if (paused != current) replace(paused)
            paused
        }

        override suspend fun cancelAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val cancelled = DownloadCancelMutation.apply(current, fileLengthBytes, nowEpochMillis)
            if (cancelled != current) replace(cancelled)
            cancelled
        }

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = DownloadResumeMutation.apply(current, nowEpochMillis) ?: return@withLock null
            replace(queued)
            queued
        }

        override suspend fun requeueForDownloadAll(nowEpochMillis: Long): List<Download> = mutex.withLock {
            val updated = ArrayList<Download>()
            _downloads.value = _downloads.value.map { current ->
                val next = DownloadAllMutation.apply(current, nowEpochMillis) ?: return@map current
                updated += next
                next
            }
            updated
        }

        override suspend fun pauseQueuedPreservingOffsets(nowEpochMillis: Long): List<Download> = mutex.withLock {
            if (promoteToConnectingDuringQueuedPause.isNotEmpty()) {
                _downloads.value = _downloads.value.map { current ->
                    if (current.id in promoteToConnectingDuringQueuedPause && current.state == DownloadState.QUEUED) {
                        DownloadStateMachine.transition(
                            current,
                            DownloadState.CONNECTING,
                            maxOf(nowEpochMillis, current.updatedAtEpochMillis),
                        )
                    } else {
                        current
                    }
                }
            }
            val updated = ArrayList<Download>()
            _downloads.value = _downloads.value.map { current ->
                val next = PauseQueuedMutation.apply(current, nowEpochMillis) ?: return@map current
                updated += next
                next
            }
            updated
        }

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val next = com.espitman.sdm.domain.DownloadPriorityMutation.toggle(current, nowEpochMillis)
            if (next != current) replace(next)
            next
        }

        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ): Download = mutex.withLock {
            val current = _downloads.value.first { it.id == id }
            val updated = DownloadFreshRestartMutation.apply(current, nowEpochMillis, etag, lastModified, totalBytes)
            replace(updated)
            updated
        }

        private fun replace(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
    }
}
