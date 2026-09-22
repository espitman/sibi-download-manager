package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadRetryFailedMutation
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DownloadAutoRetryRunnerTest {
    @Test
    fun networkLossRetriesTwiceWithOneThenTwoSecondDelays() = runRetryableBound("UnknownHostException")

    @Test
    fun timeoutRetriesTwiceWithOneThenTwoSecondDelays() = runRetryableBound("SocketTimeoutException: timeout")

    @Test
    fun transientHttp5xxRetriesTwiceWithOneThenTwoSecondDelays() =
        runRetryableBound("HTTP 503: Service Unavailable")

    @Test
    fun expiredLinkDoesNotAutoRetry() = runNonRetryable("HTTP 410: Gone")

    @Test
    fun forbiddenDoesNotAutoRetry() = runNonRetryable("HTTP 403: Forbidden")

    @Test
    fun insufficientStorageDoesNotAutoRetry() = runNonRetryable("ENOSPC")

    @Test
    fun persistedStartingCountAllowsOnlyRemainingRetries() = runBlocking {
        val repo = MemoryDownloads(queued(automaticRetryCount = 1))
        val delays = mutableListOf<Long>()
        val runner = runner(repo, delays)
        var attempts = 0

        runner.run("item") {
            attempts++
            repo.persistFailed("Failed to connect to example.com")
        }

        assertEquals(2, attempts)
        assertEquals(listOf(2_000L), delays)
        assertEquals(listOf(true), repo.automaticFlags)
        assertEquals(2, repo.get("item")!!.automaticRetryCount)
        assertEquals(DownloadState.FAILED, repo.get("item")!!.state)
    }

    @Test
    fun successfulTransferStopsWithoutRetry() = runBlocking {
        val repo = MemoryDownloads(queued())
        val delays = mutableListOf<Long>()
        val runner = runner(repo, delays)
        var attempts = 0

        runner.run("item") {
            attempts++
            repo.persistCompleted()
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
        assertTrue(repo.automaticFlags.isEmpty())
        assertEquals(DownloadState.COMPLETED, repo.get("item")!!.state)
        assertEquals(0, repo.get("item")!!.automaticRetryCount)
    }

    @Test
    fun cancellationDuringBackoffPropagatesWithoutRequeue() {
        val repo = MemoryDownloads(queued())
        val delays = mutableListOf<Long>()
        val runner = DownloadAutoRetryRunner(
            repository = repo,
            clock = FixedClock(5_000L),
            delayMillis = { waitMs ->
                delays += waitMs
                throw CancellationException("cancelled during backoff")
            },
        )
        var attempts = 0

        val cancelled = assertThrows(CancellationException::class.java) {
            runBlocking {
                runner.run("item") {
                    attempts++
                    repo.persistFailed("Network is unreachable")
                }
            }
        }

        val persisted = repo.downloads.value.single()
        assertEquals("cancelled during backoff", cancelled.message)
        assertEquals(1, attempts)
        assertEquals(listOf(1_000L), delays)
        assertTrue(repo.automaticFlags.isEmpty())
        assertEquals(DownloadState.FAILED, persisted.state)
        assertEquals(0, persisted.automaticRetryCount)
    }

    private fun runRetryableBound(error: String) = runBlocking {
        val repo = MemoryDownloads(queued())
        val delays = mutableListOf<Long>()
        val runner = runner(repo, delays)
        var attempts = 0

        runner.run("item") {
            attempts++
            repo.persistFailed(error)
        }

        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(listOf(true, true), repo.automaticFlags)
        assertEquals(2, repo.get("item")!!.automaticRetryCount)
        assertEquals(DownloadState.FAILED, repo.get("item")!!.state)
    }

    private fun runNonRetryable(error: String) = runBlocking {
        val repo = MemoryDownloads(queued())
        val delays = mutableListOf<Long>()
        val runner = runner(repo, delays)
        var attempts = 0

        runner.run("item") {
            attempts++
            repo.persistFailed(error)
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
        assertTrue(repo.automaticFlags.isEmpty())
        assertEquals(DownloadState.FAILED, repo.get("item")!!.state)
        assertEquals(0, repo.get("item")!!.automaticRetryCount)
    }

    private fun runner(repo: MemoryDownloads, delays: MutableList<Long>) = DownloadAutoRetryRunner(
        repository = repo,
        clock = FixedClock(5_000L),
        delayMillis = { waitMs -> delays += waitMs },
    )

    private fun queued(automaticRetryCount: Int = 0) = Download(
        id = "item",
        url = "https://example.com/item.bin",
        fileName = "item.bin",
        destinationPath = "/tmp/item.bin",
        totalBytes = 10L,
        createdAtEpochMillis = 1_000L,
        automaticRetryCount = automaticRetryCount,
    )

    private class FixedClock(private val now: Long) : Clock {
        override fun currentTimeMillis(): Long = now
    }

    private class MemoryDownloads(initial: Download) : DownloadRepository {
        private val _downloads = MutableStateFlow(listOf(initial))
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        val automaticFlags = mutableListOf<Boolean>()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }

        override suspend fun insert(download: Download) {
            replace(download)
        }

        override suspend fun delete(id: String): Boolean = error("unused")

        override suspend fun transition(
            id: String,
            to: DownloadState,
            nowEpochMillis: Long,
            error: String?,
        ): Download = error("unused")

        override suspend fun updateProgress(
            id: String,
            downloadedBytes: Long,
            nowEpochMillis: Long,
        ): Download = error("unused")

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = error("unused")

        override suspend fun cancelAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = error("unused")

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? = error("unused")

        override suspend fun retryFailed(
            id: String,
            automatic: Boolean,
            nowEpochMillis: Long,
        ): Download? {
            automaticFlags += automatic
            val current = get(id) ?: return null
            val queued = DownloadRetryFailedMutation.apply(current, automatic, nowEpochMillis) ?: return null
            replace(queued)
            return queued
        }

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = error("unused")

        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ): Download = error("unused")

        override suspend fun transferredBytesForLocalDay(nowEpochMillis: Long, zoneId: ZoneId): Long = 0L

        fun persistFailed(error: String) {
            val current = _downloads.value.first()
            replace(
                current.copy(
                    state = DownloadState.FAILED,
                    error = error,
                    updatedAtEpochMillis = current.updatedAtEpochMillis + 1,
                    completedAtEpochMillis = null,
                ),
            )
        }

        fun persistCompleted() {
            val current = _downloads.value.first()
            replace(
                current.copy(
                    state = DownloadState.COMPLETED,
                    error = null,
                    downloadedBytes = current.totalBytes ?: current.downloadedBytes,
                    updatedAtEpochMillis = current.updatedAtEpochMillis + 1,
                    completedAtEpochMillis = current.updatedAtEpochMillis + 1,
                ),
            )
        }

        private fun replace(download: Download) {
            _downloads.value = listOf(download)
        }
    }
}
