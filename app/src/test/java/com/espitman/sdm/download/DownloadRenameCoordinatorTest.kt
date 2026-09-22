package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadRenameMutation
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRenameCoordinatorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("rename-coordinator-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun completedRenameMovesDestinationAndUpdatesRecord() = runBlocking {
        val source = File(tempDir, "movie.mkv")
        source.writeText("completed-bytes")
        val repository = FakeRepository(record(
            id = "done",
            fileName = "movie.mkv",
            destination = source,
            state = DownloadState.COMPLETED,
        ))

        val result = DownloadRenameCoordinator.rename(
            downloadId = "done",
            rawFilename = "renamed.mkv",
            repository = repository,
            clock = FakeClock(9_000L),
        )

        val success = result as DownloadRenameResult.Success
        val target = File(tempDir, "renamed.mkv")
        assertEquals("renamed.mkv", success.download.fileName)
        assertEquals(target.absolutePath, success.download.destinationPath)
        assertEquals(9_000L, success.download.updatedAtEpochMillis)
        assertEquals("renamed.mkv", repository.get("done")!!.fileName)
        assertTrue(target.exists())
        assertEquals("completed-bytes", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun queuedPartialAndRestartMoveToNewHashPaths() = runBlocking {
        val source = File(tempDir, "archive.zip")
        val oldPart = DownloadPartFile.forDestination(source)
        val oldRestart = DownloadPartFile.restartForDestination(source)
        oldPart.writeText("partial")
        oldRestart.writeText("restart")
        val repository = FakeRepository(record(
            id = "queued",
            fileName = "archive.zip",
            destination = source,
            state = DownloadState.QUEUED,
        ))

        val withPartials = DownloadRenameCoordinator.rename(
            downloadId = "queued",
            rawFilename = "bundle.zip",
            repository = repository,
            clock = FakeClock(4_000L),
        ) as DownloadRenameResult.Success

        val target = File(tempDir, "bundle.zip")
        val newPart = DownloadPartFile.forDestination(target)
        val newRestart = DownloadPartFile.restartForDestination(target)
        assertEquals(target.absolutePath, withPartials.download.destinationPath)
        assertEquals("bundle.zip", withPartials.download.fileName)
        assertEquals("partial", newPart.readText())
        assertEquals("restart", newRestart.readText())
        assertFalse(oldPart.exists())
        assertFalse(oldRestart.exists())
        assertFalse(target.exists())

        val terminalSource = File(tempDir, "failed.bin")
        val terminal = FakeRepository(record(
            id = "failed",
            fileName = "failed.bin",
            destination = terminalSource,
            state = DownloadState.FAILED,
            error = "network",
        ))
        val withoutPartials = DownloadRenameCoordinator.rename(
            downloadId = "failed",
            rawFilename = "failed-renamed.bin",
            repository = terminal,
            clock = FakeClock(5_000L),
        ) as DownloadRenameResult.Success
        assertEquals("failed-renamed.bin", withoutPartials.download.fileName)
        assertEquals(File(tempDir, "failed-renamed.bin").absolutePath, withoutPartials.download.destinationPath)
        assertFalse(DownloadPartFile.forDestination(File(tempDir, "failed-renamed.bin")).exists())
    }

    @Test
    fun rejectsBlankUnsafeAndCollidingNames() = runBlocking {
        val source = File(tempDir, "report.pdf")
        source.writeText("pdf")
        File(tempDir, "taken.pdf").writeText("other")
        val repository = FakeRepository(record(
            id = "doc",
            fileName = "report.pdf",
            destination = source,
            state = DownloadState.COMPLETED,
        ))

        val blank = DownloadRenameCoordinator.rename("doc", "   ", repository, FakeClock())
        assertEquals("Filename cannot be blank", (blank as DownloadRenameResult.Failure).message)
        assertEquals("report.pdf", repository.get("doc")!!.fileName)

        val separator = DownloadRenameCoordinator.rename("doc", "sub/dir.pdf", repository, FakeClock())
        assertEquals(
            "Filename cannot contain path separators",
            (separator as DownloadRenameResult.Failure).message,
        )

        val unsafe = DownloadRenameCoordinator.rename("doc", "my:file.pdf", repository, FakeClock())
        assertEquals("Filename is unsafe", (unsafe as DownloadRenameResult.Failure).message)

        val collision = DownloadRenameCoordinator.rename("doc", "taken.pdf", repository, FakeClock())
        assertEquals("A file with that name already exists", (collision as DownloadRenameResult.Failure).message)
        assertTrue(source.exists())
        assertEquals("pdf", source.readText())
        assertEquals("report.pdf", repository.get("doc")!!.fileName)
    }

    @Test
    fun rejectsActiveDownloadsUntilPaused() = runBlocking {
        val connectingFile = File(tempDir, "connecting.bin")
        val downloadingFile = File(tempDir, "downloading.bin")
        val connectingRepo = FakeRepository(record(
            id = "c",
            fileName = "connecting.bin",
            destination = connectingFile,
            state = DownloadState.CONNECTING,
        ))
        val downloadingRepo = FakeRepository(record(
            id = "d",
            fileName = "downloading.bin",
            destination = downloadingFile,
            state = DownloadState.DOWNLOADING,
        ))

        val connecting = DownloadRenameCoordinator.rename("c", "next.bin", connectingRepo, FakeClock())
        val downloading = DownloadRenameCoordinator.rename("d", "next.bin", downloadingRepo, FakeClock())
        val pauseConnecting = connecting as DownloadRenameResult.PauseRequired
        val pauseDownloading = downloading as DownloadRenameResult.PauseRequired
        assertEquals("Pause the download before renaming", pauseConnecting.message)
        assertEquals("Pause the download before renaming", pauseDownloading.message)
        assertEquals("connecting.bin", connectingRepo.get("c")!!.fileName)
        assertEquals("downloading.bin", downloadingRepo.get("d")!!.fileName)
        assertEquals(0, connectingRepo.renameCalls)
        assertEquals(0, downloadingRepo.renameCalls)
    }

    @Test
    fun failsWhenCompletedSourceIsMissing() = runBlocking {
        val source = File(tempDir, "gone.iso")
        val repository = FakeRepository(record(
            id = "iso",
            fileName = "gone.iso",
            destination = source,
            state = DownloadState.COMPLETED,
        ))

        val result = DownloadRenameCoordinator.rename("iso", "next.iso", repository, FakeClock())
        assertEquals("Completed file is missing", (result as DownloadRenameResult.Failure).message)
        assertEquals("gone.iso", repository.get("iso")!!.fileName)
        assertEquals(source.absolutePath, repository.get("iso")!!.destinationPath)
        assertEquals(0, repository.renameCalls)
        assertFalse(File(tempDir, "next.iso").exists())
    }

    @Test
    fun rollsBackFilesystemWhenRepositoryRenameFails() = runBlocking {
        val source = File(tempDir, "clip.mp4")
        source.writeText("video")
        val repository = FakeRepository(
            record(
                id = "clip",
                fileName = "clip.mp4",
                destination = source,
                state = DownloadState.COMPLETED,
            ),
            renameFailure = IllegalStateException("db locked"),
        )

        val result = DownloadRenameCoordinator.rename("clip", "clip-new.mp4", repository, FakeClock(8_000L))
        val failure = result as DownloadRenameResult.Failure
        assertEquals("Could not update download record", failure.message)
        assertTrue(failure.cause is IllegalStateException)
        assertEquals("clip.mp4", repository.get("clip")!!.fileName)
        assertEquals(source.absolutePath, repository.get("clip")!!.destinationPath)
        assertTrue(source.exists())
        assertEquals("video", source.readText())
        assertFalse(File(tempDir, "clip-new.mp4").exists())
        assertEquals(1, repository.renameCalls)
    }

    private fun record(
        id: String,
        fileName: String,
        destination: File,
        state: DownloadState,
        error: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        destinationPath = destination.absolutePath,
        totalBytes = 16,
        downloadedBytes = if (state == DownloadState.COMPLETED) 16 else 4,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 1_000L else null,
    )

    class FakeClock(private val time: Long = 1_000L) : Clock {
        override fun currentTimeMillis(): Long = time
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

        override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = error("unused")

        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ): Download = error("unused")

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
