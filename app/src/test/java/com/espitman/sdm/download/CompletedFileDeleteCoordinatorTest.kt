package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadRenameMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.CompletedDestinationPresence
import com.espitman.sdm.storage.CompletedFileUserMessages
import com.espitman.sdm.storage.ContentDocumentMutation
import com.espitman.sdm.storage.ContentDocumentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CompletedFileDeleteCoordinatorTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("delete-coordinator").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun localDeleteRemovesFileAndRecord() = runBlocking {
        val file = File(tempDir, "keep.bin").apply { writeText("gone-soon") }
        val repository = FakeRepository(record("keep", file.absolutePath, "keep.bin"))

        val result = CompletedFileDeleteCoordinator.delete("keep", repository)

        assertTrue(result is CompletedFileDeleteResult.Deleted)
        assertEquals(CompletedFileUserMessages.DELETED, result.message)
        assertFalse(file.exists())
        assertNull(repository.get("keep"))
    }

    @Test
    fun alreadyMissingLocalFileRemovesStaleRecord() = runBlocking {
        val missing = File(tempDir, "missing.bin")
        val repository = FakeRepository(record("missing", missing.absolutePath, "missing.bin"))

        val result = CompletedFileDeleteCoordinator.delete("missing", repository)

        assertTrue(result is CompletedFileDeleteResult.AlreadyMissing)
        assertEquals(CompletedFileUserMessages.ALREADY_DELETED, result.message)
        assertNull(repository.get("missing"))
    }

    @Test
    fun revokedSafAccessKeepsRecord() = runBlocking {
        val store = FakeContentStore(presence = CompletedDestinationPresence.AccessUnavailable)
        val repository = FakeRepository(
            record("saf", "content://docs/document/locked", "locked.bin"),
        )

        val result = CompletedFileDeleteCoordinator.delete("saf", repository, store)

        assertEquals(
            CompletedFileUserMessages.ACCESS_UNAVAILABLE,
            (result as CompletedFileDeleteResult.Failure).message,
        )
        assertEquals("locked.bin", repository.get("saf")!!.fileName)
        assertFalse(store.deleteCalled)
    }

    @Test
    fun safDeleteSuccessRemovesRecord() = runBlocking {
        val store = FakeContentStore(presence = CompletedDestinationPresence.Readable)
        val repository = FakeRepository(
            record("saf", "content://docs/document/done", "done.bin"),
        )

        val result = CompletedFileDeleteCoordinator.delete("saf", repository, store)

        assertTrue(result is CompletedFileDeleteResult.Deleted)
        assertTrue(store.deleteCalled)
        assertNull(repository.get("saf"))
    }

    @Test
    fun storageRefusalKeepsRecord() = runBlocking {
        val store = FakeContentStore(
            presence = CompletedDestinationPresence.Readable,
            deleteFailure = ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Refused),
        )
        val repository = FakeRepository(
            record("saf", "content://docs/document/stay", "stay.bin"),
        )

        val result = CompletedFileDeleteCoordinator.delete("saf", repository, store)

        assertEquals(CompletedFileUserMessages.DELETE_FAILED, (result as CompletedFileDeleteResult.Failure).message)
        assertEquals("stay.bin", repository.get("saf")!!.fileName)
    }

    @Test
    fun dbFailureAfterStorageDeleteIsConsistencyError() = runBlocking {
        val file = File(tempDir, "orphan.bin").apply { writeText("bytes") }
        val repository = FakeRepository(
            record("orphan", file.absolutePath, "orphan.bin"),
            deleteFailure = IllegalStateException("db locked"),
        )

        val result = CompletedFileDeleteCoordinator.delete("orphan", repository)

        assertEquals(
            CompletedFileUserMessages.DELETE_INCONSISTENT,
            (result as CompletedFileDeleteResult.Failure).message,
        )
        assertFalse(file.exists())
        assertEquals("orphan.bin", repository.get("orphan")!!.fileName)
    }

    @Test
    fun safAlreadyMissingRemovesStaleRecord() = runBlocking {
        val store = FakeContentStore(presence = CompletedDestinationPresence.Missing)
        val repository = FakeRepository(
            record("gone", "content://docs/document/gone", "gone.bin"),
        )

        val result = CompletedFileDeleteCoordinator.delete("gone", repository, store)

        assertTrue(result is CompletedFileDeleteResult.AlreadyMissing)
        assertFalse(store.deleteCalled)
        assertNull(repository.get("gone"))
    }

    private fun record(id: String, destination: String, fileName: String) = Download(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        destinationPath = destination,
        destinationTreeUri = if (destination.startsWith("content:")) "content://docs/tree/root" else null,
        destinationDisplayLabel = if (destination.startsWith("content:")) "Shared" else null,
        totalBytes = 8,
        downloadedBytes = 8,
        state = DownloadState.COMPLETED,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

    class FakeContentStore(
        private val presence: CompletedDestinationPresence,
        private val deleteFailure: ContentDocumentMutation.Failure? = null,
    ) : ContentDocumentStore {
        var deleteCalled = false
            private set

        override fun presence(documentUri: String, treeUri: String?) = presence
        override fun rename(documentUri: String, treeUri: String?, displayName: String) =
            ContentDocumentMutation.Success(documentUri, displayName)

        override fun delete(documentUri: String, treeUri: String?): ContentDocumentMutation {
            deleteCalled = true
            return deleteFailure ?: ContentDocumentMutation.Success(documentUri, "")
        }
    }

    class FakeRepository(
        initial: Download,
        private val deleteFailure: Throwable? = null,
    ) : DownloadRepository {
        private val _downloads = MutableStateFlow(listOf(initial))
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

        override suspend fun awaitInitialized() {}
        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }
        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
        override suspend fun delete(id: String): Boolean {
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
