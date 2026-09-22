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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRenameCoordinatorSafTest {
    @Test
    fun safRenameUpdatesUriAndSubmittedNameWithoutRewrite() = runBlocking {
        val store = FakeContentStore(
            uri = "content://docs/document/old",
            fileName = "report.pdf",
            renamedUri = "content://docs/document/new",
        )
        val repository = FakeRepository(record("doc", "report.pdf", "content://docs/document/old"))

        val result = DownloadRenameCoordinator.rename(
            downloadId = "doc",
            rawFilename = "final-notes.PDF",
            repository = repository,
            clock = FakeClock(4_000L),
            contentDocuments = store,
        )

        val success = result as DownloadRenameResult.Success
        assertEquals("final-notes.PDF", success.download.fileName)
        assertEquals("content://docs/document/new", success.download.destinationPath)
        assertEquals(listOf("content://docs/document/old" to "final-notes.PDF"), store.renameCalls)
        assertEquals(1, repository.renameCalls)
    }

    @Test
    fun safCollisionAndMissingAndRevokedStayOnOriginalRecord() = runBlocking {
        val collision = FakeContentStore(
            uri = "content://docs/document/a",
            fileName = "a.bin",
            renameFailure = ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Collision),
        )
        val missing = FakeContentStore(
            uri = "content://docs/document/b",
            fileName = "b.bin",
            presence = CompletedDestinationPresence.Missing,
        )
        val revoked = FakeContentStore(
            uri = "content://docs/document/c",
            fileName = "c.bin",
            presence = CompletedDestinationPresence.AccessUnavailable,
        )

        val collisionResult = DownloadRenameCoordinator.rename(
            "a",
            "taken.bin",
            FakeRepository(record("a", "a.bin", "content://docs/document/a")),
            FakeClock(),
            collision,
        )
        val missingResult = DownloadRenameCoordinator.rename(
            "b",
            "next.bin",
            FakeRepository(record("b", "b.bin", "content://docs/document/b")),
            FakeClock(),
            missing,
        )
        val revokedResult = DownloadRenameCoordinator.rename(
            "c",
            "next.bin",
            FakeRepository(record("c", "c.bin", "content://docs/document/c")),
            FakeClock(),
            revoked,
        )

        assertEquals(CompletedFileUserMessages.COLLISION, (collisionResult as DownloadRenameResult.Failure).message)
        assertEquals(CompletedFileUserMessages.MISSING, (missingResult as DownloadRenameResult.Failure).message)
        assertEquals(
            CompletedFileUserMessages.ACCESS_UNAVAILABLE,
            (revokedResult as DownloadRenameResult.Failure).message,
        )
        assertEquals(listOf("content://docs/document/a" to "taken.bin"), collision.renameCalls)
        assertTrue(missing.renameCalls.isEmpty())
        assertTrue(revoked.renameCalls.isEmpty())
    }

    @Test
    fun safRepositoryFailureAttemptsProviderRollback() = runBlocking {
        val store = FakeContentStore(
            uri = "content://docs/document/clip",
            fileName = "clip.mp4",
            renamedUri = "content://docs/document/clip-new",
        )
        val repository = FakeRepository(
            record("clip", "clip.mp4", "content://docs/document/clip"),
            renameFailure = IllegalStateException("db locked"),
        )

        val result = DownloadRenameCoordinator.rename(
            "clip",
            "clip-new.mp4",
            repository,
            FakeClock(8_000L),
            store,
        )

        val failure = result as DownloadRenameResult.Failure
        assertEquals(CompletedFileUserMessages.RECORD_UPDATE_FAILED, failure.message)
        assertEquals("clip.mp4", repository.get("clip")!!.fileName)
        assertEquals("content://docs/document/clip", repository.get("clip")!!.destinationPath)
        assertEquals(
            listOf(
                "content://docs/document/clip" to "clip-new.mp4",
                "content://docs/document/clip-new" to "clip.mp4",
            ),
            store.renameCalls,
        )
    }

    @Test
    fun localStagingFileWithTreeUriStillRenamesOnDisk() = runBlocking {
        val tempDir = Files.createTempDirectory("rename-tree-local").toFile()
        try {
            val source = File(tempDir, "staged.bin").apply { writeText("bytes") }
            val repository = FakeRepository(
                record("staged", "staged.bin", source.absolutePath).copy(
                    destinationTreeUri = "content://docs/tree/root",
                    destinationDisplayLabel = "SDM-QA",
                ),
            )
            val result = DownloadRenameCoordinator.rename(
                "staged",
                "staged-new.bin",
                repository,
                FakeClock(3_000L),
            ) as DownloadRenameResult.Success
            val target = File(tempDir, "staged-new.bin")
            assertEquals(target.absolutePath, result.download.destinationPath)
            assertEquals("bytes", target.readText())
            assertTrue(!source.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun record(id: String, fileName: String, destination: String) = Download(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        destinationPath = destination,
        destinationTreeUri = "content://docs/tree/root",
        destinationDisplayLabel = "Shared",
        totalBytes = 16,
        downloadedBytes = 16,
        state = DownloadState.COMPLETED,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

    class FakeClock(private val time: Long = 1_000L) : Clock {
        override fun currentTimeMillis(): Long = time
    }

    class FakeContentStore(
        private val uri: String,
        private val fileName: String,
        private val renamedUri: String = uri,
        private val presence: CompletedDestinationPresence = CompletedDestinationPresence.Readable,
        private val renameFailure: ContentDocumentMutation.Failure? = null,
    ) : ContentDocumentStore {
        val renameCalls = ArrayList<Pair<String, String>>()

        override fun presence(documentUri: String, treeUri: String?): CompletedDestinationPresence = presence

        override fun rename(
            documentUri: String,
            treeUri: String?,
            displayName: String,
        ): ContentDocumentMutation {
            renameCalls += documentUri to displayName
            renameFailure?.let { return it }
            val nextUri = if (documentUri == uri && displayName != fileName) renamedUri else documentUri
            return ContentDocumentMutation.Success(nextUri, displayName)
        }

        override fun delete(documentUri: String, treeUri: String?) =
            ContentDocumentMutation.Success(documentUri, "")
    }

    class FakeRepository(
        initial: Download,
        private val renameFailure: Throwable? = null,
    ) : DownloadRepository {
        private val _downloads = MutableStateFlow(listOf(initial))
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        var renameCalls: Int = 0
            private set

        override suspend fun awaitInitialized() {}
        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }
        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
        override suspend fun delete(id: String): Boolean = false
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
            renameCalls += 1
            renameFailure?.let { throw it }
            val current = get(id) ?: error("Download $id does not exist")
            val updated = DownloadRenameMutation.apply(current, fileName, destinationPath, nowEpochMillis)
            insert(updated)
            return updated
        }
    }
}
