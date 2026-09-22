package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.RecoverInterruptedActiveMutation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DownloadInterruptionRecoveryTest {

    @Test
    fun processRestartAndDeviceBootUseDistinctStableMessages() {
        assertEquals(
            "Interrupted when the app process stopped",
            DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage,
        )
        assertEquals(
            "Interrupted when the device restarted",
            DownloadInterruptionTrigger.DEVICE_BOOT.errorMessage,
        )
        assertTrue(
            DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage !=
                DownloadInterruptionTrigger.DEVICE_BOOT.errorMessage,
        )
    }

    @Test
    fun onlyConnectingAndDownloadingAreRecovered() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(
                download(id = "queued", state = DownloadState.QUEUED),
                download(id = "connecting", state = DownloadState.CONNECTING, downloadedBytes = 0),
                download(id = "downloading", state = DownloadState.DOWNLOADING, downloadedBytes = 40, totalBytes = 100),
                download(id = "paused", state = DownloadState.PAUSED, downloadedBytes = 10),
                download(id = "completed", state = DownloadState.COMPLETED, downloadedBytes = 100, totalBytes = 100, completedAt = 2_000),
                download(id = "failed", state = DownloadState.FAILED, error = "already failed"),
                download(id = "cancelled", state = DownloadState.CANCELLED),
            ),
        )

        DownloadInterruptionRecovery.recover(repo, clock, DownloadInterruptionTrigger.PROCESS_RESTART)

        assertEquals(
            listOf("connecting", "downloading"),
            repo.transitions.map { it.id },
        )
        assertEquals(DownloadState.QUEUED, repo.require("queued").state)
        assertEquals(DownloadState.PAUSED, repo.require("paused").state)
        assertEquals(DownloadState.COMPLETED, repo.require("completed").state)
        assertEquals(DownloadState.FAILED, repo.require("failed").state)
        assertEquals("already failed", repo.require("failed").error)
        assertEquals(DownloadState.CANCELLED, repo.require("cancelled").state)
        assertEquals(DownloadState.FAILED, repo.require("connecting").state)
        assertEquals(DownloadState.FAILED, repo.require("downloading").state)
        assertEquals(
            DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage,
            repo.require("connecting").error,
        )
        assertEquals(
            DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage,
            repo.require("downloading").error,
        )
    }

    @Test
    fun snapshotPlusRereadSkipsMissingAndNoLongerActiveRecords() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(
                download(id = "missing", state = DownloadState.CONNECTING),
                download(id = "paused-after-snapshot", state = DownloadState.CONNECTING),
                download(id = "still-active", state = DownloadState.DOWNLOADING, downloadedBytes = 12, totalBytes = 100),
            ),
        )
        repo.overrideGet["missing"] = { null }
        repo.overrideGet["paused-after-snapshot"] = {
            download(id = "paused-after-snapshot", state = DownloadState.PAUSED)
        }

        DownloadInterruptionRecovery.recover(repo, clock, DownloadInterruptionTrigger.DEVICE_BOOT)

        assertEquals(listOf("still-active"), repo.transitions.map { it.id })
        assertEquals(DownloadState.FAILED, repo.require("still-active").state)
        assertEquals(
            DownloadInterruptionTrigger.DEVICE_BOOT.errorMessage,
            repo.require("still-active").error,
        )
    }

    @Test
    fun recoveryIsIdempotentAfterActiveWorkIsFailed() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(download(id = "active", state = DownloadState.DOWNLOADING, downloadedBytes = 8, totalBytes = 50)),
        )

        DownloadInterruptionRecovery.recover(repo, clock, DownloadInterruptionTrigger.PROCESS_RESTART)
        DownloadInterruptionRecovery.recover(repo, clock, DownloadInterruptionTrigger.DEVICE_BOOT)

        assertEquals(1, repo.transitions.size)
        assertEquals(DownloadState.FAILED, repo.require("active").state)
        assertEquals(
            DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage,
            repo.require("active").error,
        )
    }

    @Test
    fun downloadedBytesArePreservedAndFilesAreNotTouched() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(
                download(
                    id = "partial",
                    state = DownloadState.DOWNLOADING,
                    downloadedBytes = 77,
                    totalBytes = 200,
                    destinationPath = "/tmp/partial.bin",
                ),
            ),
        )

        DownloadInterruptionRecovery.recover(repo, clock, DownloadInterruptionTrigger.PROCESS_RESTART)

        val recovered = repo.require("partial")
        assertEquals(77L, recovered.downloadedBytes)
        assertEquals("/tmp/partial.bin", recovered.destinationPath)
        assertEquals(0, repo.deletes)
    }

    @Test
    fun transitionTimestampIsMaxOfClockAndUpdatedAt() = runBlocking {
        val clockBehind = FakeClock(1_500)
        val behindRepo = FakeDownloadRepository(
            listOf(download(id = "behind", state = DownloadState.CONNECTING, updatedAt = 4_000)),
        )
        DownloadInterruptionRecovery.recover(behindRepo, clockBehind, DownloadInterruptionTrigger.PROCESS_RESTART)
        assertEquals(4_000L, behindRepo.require("behind").updatedAtEpochMillis)
        assertEquals(4_000L, behindRepo.transitions.single().nowEpochMillis)

        val clockAhead = FakeClock(9_000)
        val aheadRepo = FakeDownloadRepository(
            listOf(download(id = "ahead", state = DownloadState.DOWNLOADING, updatedAt = 4_000, downloadedBytes = 1, totalBytes = 10)),
        )
        DownloadInterruptionRecovery.recover(aheadRepo, clockAhead, DownloadInterruptionTrigger.DEVICE_BOOT)
        assertEquals(9_000L, aheadRepo.require("ahead").updatedAtEpochMillis)
        assertEquals(9_000L, aheadRepo.transitions.single().nowEpochMillis)
    }

    @Test
    fun transitionFailureIsToleratedOnlyWhenRereadIsMissingOrInactive() = runBlocking {
        val clock = FakeClock(5_000)
        val tolerated = FakeDownloadRepository(
            listOf(download(id = "raced", state = DownloadState.CONNECTING)),
        )
        tolerated.transitionFailure = IllegalStateException("concurrent update")
        tolerated.overrideGetAfterTransition["raced"] = {
            download(id = "raced", state = DownloadState.PAUSED)
        }

        DownloadInterruptionRecovery.recover(tolerated, clock, DownloadInterruptionTrigger.PROCESS_RESTART)

        val propagated = FakeDownloadRepository(
            listOf(download(id = "stuck", state = DownloadState.DOWNLOADING, downloadedBytes = 3, totalBytes = 10)),
        )
        val failure = IllegalStateException("still active")
        propagated.transitionFailure = failure

        try {
            DownloadInterruptionRecovery.recover(propagated, clock, DownloadInterruptionTrigger.PROCESS_RESTART)
            fail("expected transition failure to propagate")
        } catch (thrown: IllegalStateException) {
            assertSame(failure, thrown)
        }
    }

    @Test
    fun autoResumeRequeuesStaleActiveWithoutFailureOrRetryBudget() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(
                download(id = "queued", state = DownloadState.QUEUED),
                download(id = "connecting", state = DownloadState.CONNECTING, downloadedBytes = 0),
                download(
                    id = "downloading",
                    state = DownloadState.DOWNLOADING,
                    downloadedBytes = 40,
                    totalBytes = 100,
                    destinationPath = "/tmp/partial.bin",
                    automaticRetryCount = 2,
                ),
                download(id = "paused", state = DownloadState.PAUSED, downloadedBytes = 10),
                download(
                    id = "completed",
                    state = DownloadState.COMPLETED,
                    downloadedBytes = 100,
                    totalBytes = 100,
                    completedAt = 2_000,
                ),
                download(id = "failed", state = DownloadState.FAILED, error = "HTTP 500: Internal Server Error"),
                download(id = "cancelled", state = DownloadState.CANCELLED),
            ),
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )

        assertEquals(listOf("connecting", "downloading"), repo.transitions.map { it.id })
        assertEquals(DownloadState.QUEUED, repo.require("queued").state)
        assertEquals(DownloadState.PAUSED, repo.require("paused").state)
        assertNull(repo.require("paused").pauseCause)
        assertEquals(DownloadState.COMPLETED, repo.require("completed").state)
        assertEquals(DownloadState.FAILED, repo.require("failed").state)
        assertEquals("HTTP 500: Internal Server Error", repo.require("failed").error)
        assertEquals(DownloadState.CANCELLED, repo.require("cancelled").state)
        assertEquals(DownloadState.QUEUED, repo.require("connecting").state)
        assertEquals(DownloadState.QUEUED, repo.require("downloading").state)
        assertNull(repo.require("connecting").error)
        assertNull(repo.require("downloading").error)
        assertEquals(40L, repo.require("downloading").downloadedBytes)
        assertEquals("/tmp/partial.bin", repo.require("downloading").destinationPath)
        assertEquals(2, repo.require("downloading").automaticRetryCount)
        assertEquals(0, repo.deletes)
        repo.transitions.forEach { recorded ->
            assertEquals(DownloadState.QUEUED, recorded.to)
            assertNull(recorded.error)
        }
    }

    @Test
    fun autoResumeDeviceBootMatchesProcessRestartAndIsIdempotent() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(download(id = "active", state = DownloadState.DOWNLOADING, downloadedBytes = 8, totalBytes = 50)),
        )

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = true,
        )
        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )
        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = false,
        )

        assertEquals(1, repo.transitions.size)
        assertEquals(DownloadState.QUEUED, repo.require("active").state)
        assertNull(repo.require("active").error)
        assertEquals(8L, repo.require("active").downloadedBytes)
    }

    @Test
    fun autoResumeSnapshotPlusRereadSkipsMissingAndNoLongerActiveRecords() = runBlocking {
        val clock = FakeClock(5_000)
        val repo = FakeDownloadRepository(
            listOf(
                download(id = "missing", state = DownloadState.CONNECTING),
                download(id = "paused-after-snapshot", state = DownloadState.CONNECTING),
                download(id = "still-active", state = DownloadState.DOWNLOADING, downloadedBytes = 12, totalBytes = 100),
            ),
        )
        repo.overrideGet["missing"] = { null }
        repo.overrideGet["paused-after-snapshot"] = {
            download(id = "paused-after-snapshot", state = DownloadState.PAUSED)
        }

        DownloadInterruptionRecovery.recover(
            repo,
            clock,
            DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = true,
        )

        assertEquals(listOf("still-active"), repo.transitions.map { it.id })
        assertEquals(DownloadState.QUEUED, repo.require("still-active").state)
        assertNull(repo.require("still-active").error)
    }

    @Test
    fun autoResumeTransitionFailureIsToleratedOnlyWhenRereadIsMissingOrInactive() = runBlocking {
        val clock = FakeClock(5_000)
        val tolerated = FakeDownloadRepository(
            listOf(download(id = "raced", state = DownloadState.CONNECTING)),
        )
        tolerated.transitionFailure = IllegalStateException("concurrent update")
        tolerated.overrideGetAfterTransition["raced"] = {
            download(id = "raced", state = DownloadState.PAUSED)
        }

        DownloadInterruptionRecovery.recover(
            tolerated,
            clock,
            DownloadInterruptionTrigger.PROCESS_RESTART,
            autoResume = true,
        )

        val propagated = FakeDownloadRepository(
            listOf(download(id = "stuck", state = DownloadState.DOWNLOADING, downloadedBytes = 3, totalBytes = 10)),
        )
        val failure = IllegalStateException("still active")
        propagated.transitionFailure = failure

        try {
            DownloadInterruptionRecovery.recover(
                propagated,
                clock,
                DownloadInterruptionTrigger.PROCESS_RESTART,
                autoResume = true,
            )
            fail("expected transition failure to propagate")
        } catch (thrown: IllegalStateException) {
            assertSame(failure, thrown)
        }
    }

    @Test
    fun concurrentGateRunsRecoveryExactlyOnceAndSharesSuccess() = runBlocking {
        val gate = DownloadRecoveryOnceGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger(0)

        val callers = List(8) {
            async {
                gate.runOnce {
                    runs.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        started.await()
        release.complete(Unit)
        callers.awaitAll()
        gate.runOnce { runs.incrementAndGet() }

        assertEquals(1, runs.get())
    }

    @Test
    fun concurrentGateSharesFailureWithoutDeadlock() = runBlocking {
        val gate = DownloadRecoveryOnceGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("recovery failed")
        val runs = AtomicInteger(0)
        val observed = ConcurrentHashMap<Int, Throwable>()

        val callers = List(6) { index ->
            async {
                try {
                    gate.runOnce {
                        runs.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        throw failure
                    }
                    fail("expected shared failure")
                } catch (thrown: Throwable) {
                    observed[index] = thrown
                }
            }
        }
        started.await()
        release.complete(Unit)
        callers.awaitAll()

        try {
            gate.runOnce { runs.incrementAndGet() }
            fail("later caller should reuse the failed completion")
        } catch (thrown: Throwable) {
            observed[99] = thrown
        }

        assertEquals(1, runs.get())
        assertEquals(7, observed.size)
        observed.values.forEach { thrown ->
            assertTrue(thrown is IllegalStateException)
            assertEquals("recovery failed", thrown.message)
        }
    }

    private fun download(
        id: String,
        state: DownloadState,
        downloadedBytes: Long = 0,
        totalBytes: Long? = null,
        error: String? = null,
        destinationPath: String? = null,
        updatedAt: Long = 1_000,
        completedAt: Long? = null,
        automaticRetryCount: Int = 0,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = destinationPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = updatedAt,
        startedAtEpochMillis = if (state == DownloadState.QUEUED) null else 1_000,
        completedAtEpochMillis = completedAt,
        automaticRetryCount = automaticRetryCount,
    )

    private class FakeClock(private val currentTime: Long) : Clock {
        override fun currentTimeMillis(): Long = currentTime
    }

    private data class RecordedTransition(
        val id: String,
        val to: DownloadState,
        val nowEpochMillis: Long,
        val error: String?,
    )

    private class FakeDownloadRepository(
        initial: List<Download>,
    ) : DownloadRepository {
        private val _downloads = MutableStateFlow(initial)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        val transitions = mutableListOf<RecordedTransition>()
        val overrideGet = mutableMapOf<String, () -> Download?>()
        val overrideGetAfterTransition = mutableMapOf<String, () -> Download?>()
        var transitionFailure: Throwable? = null
        var deletes = 0
        private val getCounts = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? {
            val count = getCounts.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
            if (count > 1) {
                overrideGetAfterTransition[id]?.let { return it() }
            }
            overrideGet[id]?.let { return it() }
            return _downloads.value.find { it.id == id }
        }

        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }

        override suspend fun delete(id: String): Boolean {
            deletes += 1
            val existed = _downloads.value.any { it.id == id }
            _downloads.value = _downloads.value.filterNot { it.id == id }
            return existed
        }

        override suspend fun transition(
            id: String,
            to: DownloadState,
            nowEpochMillis: Long,
            error: String?,
        ): Download {
            transitions.add(RecordedTransition(id, to, nowEpochMillis, error))
            transitionFailure?.let { throw it }
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val updated = DownloadStateMachine.transition(current, to, nowEpochMillis, error)
            insert(updated)
            return updated
        }

        override suspend fun updateProgress(
            id: String,
            downloadedBytes: Long,
            nowEpochMillis: Long,
        ): Download {
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val updated = current.copy(downloadedBytes = downloadedBytes, updatedAtEpochMillis = nowEpochMillis)
            insert(updated)
            return updated
        }

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? {
            val current = get(id) ?: return null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis)
            if (paused != current) insert(paused)
            return paused
        }

        override suspend fun cancelAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? {
            val current = get(id) ?: return null
            val cancelled = com.espitman.sdm.domain.DownloadCancelMutation.apply(
                current,
                fileLengthBytes,
                nowEpochMillis,
            )
            if (cancelled != current) insert(cancelled)
            return cancelled
        }

        override suspend fun requeueInterruptedActive(
            id: String,
            nowEpochMillis: Long,
        ): Download? {
            val current = _downloads.value.find { it.id == id } ?: return null
            val queued = RecoverInterruptedActiveMutation.apply(current, nowEpochMillis)
                ?: return null
            return transition(id, DownloadState.QUEUED, queued.updatedAtEpochMillis)
        }

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? {
            val current = get(id) ?: return null
            if (current.state != DownloadState.PAUSED) return null
            val queued = DownloadStateMachine.transition(current, DownloadState.QUEUED, nowEpochMillis)
            insert(queued)
            return queued
        }

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? {
            val current = get(id) ?: return null
            val updated = com.espitman.sdm.domain.DownloadPriorityMutation.toggle(current, nowEpochMillis)
            if (updated != current) insert(updated)
            return updated
        }

        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ): Download {
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val updated = DownloadFreshRestartMutation.apply(
                current, nowEpochMillis, etag, lastModified, totalBytes,
            )
            insert(updated)
            return updated
        }

        fun require(id: String): Download = _downloads.value.first { it.id == id }
    }
}
