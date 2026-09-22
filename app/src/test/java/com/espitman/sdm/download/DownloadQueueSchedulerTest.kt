package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadCancelMutation
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadRetryFailedMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DownloadQueueSchedulerTest {
    @Test
    fun startsHigherPriorityThenCreationOrderUpToLimit() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("low", priority = 0, createdAt = 1),
                queued("high-late", priority = 4, createdAt = 30),
                queued("mid-early", priority = 2, createdAt = 5),
                queued("mid-late", priority = 2, createdAt = 6),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        scheduler.schedule()

        assertEquals(listOf("high-late", "mid-early"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("mid-late")!!.state)
        assertEquals(DownloadState.QUEUED, repo.get("low")!!.state)
    }

    @Test
    fun neverExceedsConfiguredConcurrency() = runBlocking {
        val repo = FakeDownloadRepository(
            List(8) { index -> queued("q$index", priority = 0, createdAt = index.toLong()) },
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 3 }, starter)

        scheduler.schedule()
        scheduler.schedule()

        assertEquals(3, starter.startedIds().size)
        assertEquals(listOf("q0", "q1", "q2"), starter.startedIds())
    }

    @Test
    fun refillsFreedSlotsAfterCompletionFailurePauseAndCancel() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("first", priority = 3, createdAt = 1),
                queued("second", priority = 2, createdAt = 2),
                queued("third", priority = 1, createdAt = 3),
                queued("fourth", priority = 0, createdAt = 4),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)

        scheduler.schedule()
        assertEquals(listOf("first"), starter.startedIds())

        repo.complete("first")
        scheduler.releaseSlot("first")
        assertEquals(listOf("first", "second"), starter.startedIds())

        repo.fail("second")
        scheduler.releaseSlot("second")
        assertEquals(listOf("first", "second", "third"), starter.startedIds())

        repo.pause("third")
        scheduler.releaseSlot("third")
        assertEquals(listOf("first", "second", "third", "fourth"), starter.startedIds())

        repo.cancel("fourth")
        scheduler.releaseSlot("fourth")
        assertEquals(listOf("first", "second", "third", "fourth"), starter.startedIds())
        assertTrue(repo.downloads.value.none { it.state == DownloadState.QUEUED })
    }

    @Test
    fun concurrentTriggersStartEachDownloadAtMostOnceAndHonorLimit() = runBlocking {
        val repo = FakeDownloadRepository(
            List(6) { index -> queued("race-$index", createdAt = index.toLong()) },
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        val results = (1..24).map {
            async(Dispatchers.Default) { scheduler.schedule() }
        }
        results.awaitAll()

        assertEquals(listOf("race-0", "race-1"), starter.startedIds())
        assertEquals(2, starter.startedIds().toSet().size)
    }

    @Test
    fun downloadNowAndResumeShareCapacityWithQueuedWork() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("active-1").copy(state = DownloadState.DOWNLOADING),
                queued("active-2").copy(state = DownloadState.CONNECTING),
                queued("waiting", priority = 0, createdAt = 1),
                queued("paused", createdAt = 2).copy(state = DownloadState.PAUSED, downloadedBytes = 4),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        repo.insert(queued("download-now", priority = 0, createdAt = 3))
        scheduler.schedule()
        assertTrue(starter.startedIds().isEmpty())

        scheduler.resume("paused")
        assertEquals(DownloadState.QUEUED, repo.get("paused")!!.state)
        assertEquals(0, repo.get("paused")!!.automaticRetryCount)
        assertTrue(starter.startedIds().isEmpty())

        repo.complete("active-1")
        scheduler.releaseSlot("active-1")
        assertEquals(listOf("waiting"), starter.startedIds())

        repo.fail("active-2")
        scheduler.releaseSlot("active-2")
        assertEquals(listOf("waiting", "paused"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("download-now")!!.state)
    }

    @Test
    fun restartReconciliationUsesRepositoryStateWithoutInMemoryLaunchSet() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("orphan-connecting").copy(state = DownloadState.CONNECTING),
                queued("orphan-downloading").copy(state = DownloadState.DOWNLOADING),
                queued("keep-high", priority = 5, createdAt = 10),
                queued("keep-low", priority = 0, createdAt = 11),
                queued("keep-later", priority = 0, createdAt = 12),
            ),
        )
        val starter = RecordingStarter()
        DownloadQueueScheduler(repo, { 2 }, starter).schedule()
        assertTrue(starter.startedIds().isEmpty())

        DownloadInterruptionRecovery.recover(
            repository = repo,
            clock = Clock.SystemClock,
            trigger = DownloadInterruptionTrigger.PROCESS_RESTART,
        )
        assertEquals(DownloadState.FAILED, repo.get("orphan-connecting")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("orphan-downloading")!!.state)

        val recovered = RecordingStarter()
        DownloadQueueScheduler(repo, { 2 }, recovered).schedule()
        assertEquals(listOf("keep-high", "keep-low"), recovered.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("keep-later")!!.state)
    }

    @Test
    fun startFailureLeavesQueuedRecordAndFillsRemainingSlots() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("boom", priority = 9, createdAt = 1),
                queued("ok", priority = 0, createdAt = 2),
            ),
        )
        val starter = RecordingStarter(failIds = setOf("boom"))
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)

        scheduler.schedule()

        assertEquals(listOf("ok"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("boom")!!.state)
    }

    @Test
    fun raisingPriorityOfAWaitingItemWinsTheNextFreedSlot() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("running", createdAt = 1).copy(state = DownloadState.DOWNLOADING),
                queued("older-low", priority = 0, createdAt = 2),
                queued("newer-low", priority = 0, createdAt = 3),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)

        scheduler.schedule()
        assertTrue(starter.startedIds().isEmpty())

        val raised = scheduler.togglePriority("newer-low")
        assertEquals(1, raised!!.priority)
        assertTrue(starter.startedIds().isEmpty())

        repo.complete("running")
        scheduler.releaseSlot("running")
        assertEquals(listOf("newer-low"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("older-low")!!.state)
    }

    @Test
    fun cancelledClaimDoesNotLeakASlotWithoutRelease() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("claimed", createdAt = 1),
                queued("next", createdAt = 2),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)
        scheduler.schedule()
        assertEquals(listOf("claimed"), starter.startedIds())

        repo.cancel("claimed")
        scheduler.schedule()
        assertEquals(listOf("claimed", "next"), starter.startedIds())
    }

    @Test
    fun releaseClaimFreesCapacityWithoutStartingTheNextItem() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("claimed", createdAt = 1),
                queued("next", createdAt = 2),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)
        scheduler.schedule()
        assertEquals(listOf("claimed"), starter.startedIds())

        repo.cancel("claimed")
        scheduler.releaseClaim("claimed")
        assertEquals(listOf("claimed"), starter.startedIds())

        scheduler.schedule()
        assertEquals(listOf("claimed", "next"), starter.startedIds())
    }

    @Test
    fun togglePriorityPersistsAndIgnoresCompletedAndCancelled() = runBlocking {
        val completed = queued("done", createdAt = 1).copy(
            state = DownloadState.COMPLETED,
            completedAtEpochMillis = 1_000L,
        )
        val cancelled = queued("gone", createdAt = 2).copy(state = DownloadState.CANCELLED)
        val waiting = queued("wait", createdAt = 3)
        val repo = FakeDownloadRepository(listOf(completed, cancelled, waiting))
        val scheduler = DownloadQueueScheduler(repo, { 3 }, RecordingStarter())

        assertEquals(0, scheduler.togglePriority("done")!!.priority)
        assertEquals(0, scheduler.togglePriority("gone")!!.priority)
        assertEquals(1, scheduler.togglePriority("wait")!!.priority)
        assertEquals(1, repo.get("wait")!!.priority)
        assertEquals(0, scheduler.togglePriority("wait")!!.priority)
        assertEquals(0, repo.get("wait")!!.priority)
        assertNull(scheduler.togglePriority("missing"))
    }

    @Test
    fun resumeFailedRequeuesManuallyResettingAutomaticRetryCountThenSchedules() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("failed", createdAt = 1).copy(
                    state = DownloadState.FAILED,
                    error = "HTTP 503: Service Unavailable",
                    downloadedBytes = 9L,
                    automaticRetryCount = 2,
                ),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)

        scheduler.resume("failed")

        val retried = repo.get("failed")!!
        assertEquals(DownloadState.QUEUED, retried.state)
        assertEquals(0, retried.automaticRetryCount)
        assertEquals(9L, retried.downloadedBytes)
        assertEquals(listOf("failed"), starter.startedIds())
    }

    @Test
    fun resumeLeavesNonPausedNonFailedRecordsUnchanged() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("waiting", createdAt = 1),
                queued("running", createdAt = 2).copy(state = DownloadState.DOWNLOADING),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter)

        scheduler.resume("waiting")
        scheduler.resume("running")

        assertEquals(DownloadState.QUEUED, repo.get("waiting")!!.state)
        assertEquals(DownloadState.DOWNLOADING, repo.get("running")!!.state)
        assertTrue(starter.startedIds().isEmpty())
    }

    @Test
    fun blockedAllowanceDoesNotClaimOrSpinQueuedRecords() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("first", createdAt = 1),
                queued("second", createdAt = 2),
            ),
        )
        val starter = RecordingStarter()
        val allowance = MutableTransferAllowance(initiallyAllowed = false)
        val scheduler = DownloadQueueScheduler(repo, { 2 }, starter, transferAllowance = allowance)

        repeat(5) { scheduler.schedule() }
        assertTrue(starter.startedIds().isEmpty())
        assertEquals(DownloadState.QUEUED, repo.get("first")!!.state)

        allowance.setAllowed(true)
        scheduler.schedule()
        assertEquals(listOf("first", "second"), starter.startedIds())
    }

    @Test
    fun explicitQueuedStartMovesTheSelectedRecordAheadWithinItsPriorityTier() = runBlocking {
        val repo = FakeDownloadRepository(
            listOf(
                queued("older", createdAt = 1),
                queued("selected", createdAt = 2),
            ),
        )
        val starter = RecordingStarter()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, starter)

        scheduler.startQueued("selected")

        assertEquals(listOf("selected"), starter.startedIds())
        assertEquals(DownloadState.QUEUED, repo.get("older")!!.state)
    }

    private fun queued(
        id: String,
        priority: Int = 0,
        createdAt: Long = 1_000L,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        priority = priority,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )

    private class RecordingStarter(
        private val failIds: Set<String> = emptySet(),
    ) : QueuedTransferStarter {
        private val started = CopyOnWriteArrayList<String>()

        override fun startQueued(download: Download) {
            if (download.id in failIds) {
                throw IllegalStateException("start failed for ${download.id}")
            }
            started += download.id
        }

        fun startedIds(): List<String> = started.toList()
    }

    private class FakeDownloadRepository(
        initial: List<Download> = emptyList(),
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
            mutex.withLock {
                _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
            }
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
            _downloads.value = _downloads.value.filterNot { it.id == id } + updated
            updated
        }

        override suspend fun updateProgress(
            id: String,
            downloadedBytes: Long,
            nowEpochMillis: Long,
        ): Download = mutex.withLock {
            val current = _downloads.value.first { it.id == id }
            val updated = current.copy(downloadedBytes = downloadedBytes, updatedAtEpochMillis = nowEpochMillis)
            _downloads.value = _downloads.value.filterNot { it.id == id } + updated
            updated
        }

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = pauseAtExactOffset(id, fileLengthBytes, nowEpochMillis, pauseCause = null)

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
            pauseCause: com.espitman.sdm.domain.DownloadPauseCause?,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis, pauseCause)
            if (paused != current) {
                _downloads.value = _downloads.value.filterNot { it.id == id } + paused
            }
            paused
        }

        override suspend fun pauseQueuedPreservingOffsets(
            nowEpochMillis: Long,
            pauseCause: com.espitman.sdm.domain.DownloadPauseCause?,
        ): List<Download> = mutex.withLock {
            val updated = ArrayList<Download>()
            _downloads.value = _downloads.value.map { current ->
                val next = com.espitman.sdm.domain.PauseQueuedMutation.apply(
                    current,
                    nowEpochMillis,
                    pauseCause,
                ) ?: return@map current
                updated += next
                next
            }
            updated
        }

        override suspend fun cancelAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val cancelled = DownloadCancelMutation.apply(current, fileLengthBytes, nowEpochMillis)
            if (cancelled != current) {
                _downloads.value = _downloads.value.filterNot { it.id == id } + cancelled
            }
            cancelled
        }

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = com.espitman.sdm.domain.DownloadResumeMutation.apply(current, nowEpochMillis)
                ?: return@withLock null
            _downloads.value = _downloads.value.filterNot { it.id == id } + queued
            queued
        }

        override suspend fun retryFailed(
            id: String,
            automatic: Boolean,
            nowEpochMillis: Long,
        ): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val queued = DownloadRetryFailedMutation.apply(current, automatic, nowEpochMillis)
                ?: return@withLock null
            _downloads.value = _downloads.value.filterNot { it.id == id } + queued
            queued
        }

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            val updated = com.espitman.sdm.domain.DownloadPriorityMutation.toggle(current, nowEpochMillis)
            if (updated != current) {
                _downloads.value = _downloads.value.filterNot { it.id == id } + updated
            }
            updated
        }

        override suspend fun moveToTop(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
            val current = _downloads.value.find { it.id == id } ?: return@withLock null
            if (current.state != DownloadState.QUEUED) return@withLock current
            val firstOrder = _downloads.value
                .filter { it.state == DownloadState.QUEUED && it.priority == current.priority }
                .minOfOrNull { it.sortOrder }
                ?: current.sortOrder
            val updated = current.copy(sortOrder = firstOrder - 1L, updatedAtEpochMillis = nowEpochMillis)
            _downloads.value = _downloads.value.filterNot { it.id == id } + updated
            updated
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
            _downloads.value = _downloads.value.filterNot { it.id == id } + updated
            updated
        }

        suspend fun complete(id: String) {
            val current = get(id)!!
            val connecting = if (current.state == DownloadState.QUEUED) {
                transition(id, DownloadState.CONNECTING, current.updatedAtEpochMillis + 1)
            } else {
                current
            }
            val downloading = if (connecting.state == DownloadState.CONNECTING) {
                transition(id, DownloadState.DOWNLOADING, connecting.updatedAtEpochMillis + 1)
            } else {
                connecting
            }
            transition(id, DownloadState.COMPLETED, downloading.updatedAtEpochMillis + 1)
        }

        suspend fun fail(id: String) {
            val current = get(id)!!
            val connecting = if (current.state == DownloadState.QUEUED) {
                transition(id, DownloadState.CONNECTING, current.updatedAtEpochMillis + 1)
            } else {
                current
            }
            transition(id, DownloadState.FAILED, connecting.updatedAtEpochMillis + 1, "failed")
        }

        suspend fun pause(id: String) {
            val current = get(id)!!
            val connecting = if (current.state == DownloadState.QUEUED) {
                transition(id, DownloadState.CONNECTING, current.updatedAtEpochMillis + 1)
            } else {
                current
            }
            pauseAtExactOffset(id, connecting.downloadedBytes, connecting.updatedAtEpochMillis + 1)
        }

        suspend fun cancel(id: String) {
            val current = get(id)!!
            cancelAtExactOffset(id, current.downloadedBytes, current.updatedAtEpochMillis + 1)
        }
    }
}
