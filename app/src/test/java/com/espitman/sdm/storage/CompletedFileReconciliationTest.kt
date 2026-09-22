package com.espitman.sdm.storage

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadRenameMutation
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedFileReconciliationTest {
    @Test
    fun prunesOnlyConfirmedMissingAndNeverRevokedOrReadable() = runBlocking {
        val readable = completed("readable", "content://docs/document/a")
        val missing = completed("missing", "content://docs/document/b")
        val revoked = completed("revoked", "content://docs/document/c")
        val active = Download(
            id = "active",
            url = "https://example.com/active.bin",
            fileName = "active.bin",
            destinationPath = "/tmp/active.bin",
            state = DownloadState.DOWNLOADING,
            downloadedBytes = 1,
            totalBytes = 8,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
        )
        val repository = FakeRepository(listOf(readable, missing, revoked, active))
        val presence = mapOf(
            "readable" to CompletedDestinationPresence.Readable,
            "missing" to CompletedDestinationPresence.Missing,
            "revoked" to CompletedDestinationPresence.AccessUnavailable,
        )

        val first = CompletedFileReconciliation.reconcile(
            records = repository.downloads.value,
            repository = repository,
            classify = { presence.getValue(it.id) },
        )
        assertEquals(setOf("missing"), first.prunedIds)
        assertEquals(setOf("readable"), first.readableIds)
        assertEquals(setOf("revoked"), first.unavailableIds)
        assertEquals(setOf("readable", "revoked", "active"), repository.downloads.value.map { it.id }.toSet())

        val second = CompletedFileReconciliation.reconcile(
            records = repository.downloads.value.filter { it.state == DownloadState.COMPLETED },
            repository = repository,
            classify = { download ->
                if (download.id == "missing") error("missing should not be classified again")
                presence.getValue(download.id)
            },
        )
        assertTrue(second.prunedIds.isEmpty())
        assertEquals(1, repository.deleteCalls)
        assertFalse(repository.downloads.value.any { it.id == "missing" })
        assertTrue(repository.downloads.value.any { it.id == "revoked" })
    }

    @Test
    fun failedRecordDeleteDoesNotCountAsPruned() = runBlocking {
        val missing = completed("stuck", "content://docs/document/stuck")
        val repository = FakeRepository(listOf(missing), deleteFailure = IllegalStateException("locked"))

        val result = CompletedFileReconciliation.reconcile(
            records = listOf(missing),
            repository = repository,
            classify = { CompletedDestinationPresence.Missing },
        )

        assertTrue(result.prunedIds.isEmpty())
        assertEquals("stuck", repository.get("stuck")!!.id)
    }

    private fun completed(id: String, destination: String) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = destination,
        destinationTreeUri = "content://docs/tree/root",
        destinationDisplayLabel = "Shared",
        totalBytes = 4,
        downloadedBytes = 4,
        state = DownloadState.COMPLETED,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

    class FakeRepository(
        initial: List<Download>,
        private val deleteFailure: Throwable? = null,
    ) : DownloadRepository {
        private val _downloads = MutableStateFlow(initial)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        var deleteCalls = 0
            private set

        override suspend fun awaitInitialized() {}
        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }
        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
        override suspend fun delete(id: String): Boolean {
            deleteCalls += 1
            deleteFailure?.let { throw it }
            val existed = _downloads.value.any { it.id == id }
            _downloads.value = _downloads.value.filterNot { it.id == id }
            return existed
        }
        override suspend fun transition(id: String, to: DownloadState, nowEpochMillis: Long, error: String?) =
            error("unused")
        override suspend fun updateProgress(id: String, downloadedBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun pauseAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun cancelAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun resumePaused(id: String, nowEpochMillis: Long) = error("unused")
        override suspend fun togglePriority(id: String, nowEpochMillis: Long) = error("unused")
        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ) = error("unused")
        override suspend fun renameRecord(
            id: String,
            fileName: String,
            destinationPath: String,
            nowEpochMillis: Long,
        ): Download {
            val current = get(id) ?: error("missing")
            val updated = DownloadRenameMutation.apply(current, fileName, destinationPath, nowEpochMillis)
            insert(updated)
            return updated
        }
    }
}
