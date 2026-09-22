package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeFailureTest {
    @Test
    fun missingPartPersistsFailedThroughTheStateMachine() = runBlocking {
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(
                Download(
                    id = "dl-1",
                    url = "https://example.com/file.bin",
                    fileName = "file.bin",
                    destinationPath = "/tmp/file.bin",
                    downloadedBytes = 12L,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ).let { DownloadStateMachine.transition(it, DownloadState.CONNECTING, 2_000L) },
                DownloadState.DOWNLOADING,
                3_000L,
            ).copy(downloadedBytes = 12L),
            DownloadState.PAUSED,
            4_000L,
        )
        val repo = FakeDownloadRepository(listOf(paused))
        val resolved = DownloadResumePart.resolve(paused.destinationPath)
        check(resolved is DownloadResumePart.Result.Failed)

        DownloadResumeFailure.persist(
            repository = repo,
            downloadId = paused.id,
            error = resolved.error,
            nowEpochMillis = 5_000L,
        )

        val failed = repo.get(paused.id)!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals("Incomplete download part is missing", failed.error)
        assertEquals(
            listOf(DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
        assertTrue(failed.downloadedBytes == 12L)
    }

    private class FakeDownloadRepository(
        initialDownloads: List<Download>,
    ) : DownloadRepository {
        private val _downloads = MutableStateFlow(initialDownloads)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        val transitions = mutableListOf<Pair<String, DownloadState>>()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }

        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }

        override suspend fun delete(id: String): Boolean {
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
            transitions.add(id to to)
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

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? {
            val current = get(id) ?: return null
            val queued = DownloadResumeMutation.apply(current, nowEpochMillis) ?: return null
            transitions.add(id to DownloadState.QUEUED)
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
    }
}
