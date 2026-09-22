package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.network.DownloadMetadata
import com.espitman.sdm.network.DownloadMetadataResult
import com.espitman.sdm.network.DownloadMetadataRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DownloadSubmissionCoordinatorTest {

    private lateinit var tempDir: File

    class FakeDownloadRepository : DownloadRepository {
        private val _downloads = MutableStateFlow<List<Download>>(emptyList())
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

        val insertedDownloads = mutableListOf<Download>()
        var insertFailure: Throwable? = null

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? =
            _downloads.value.find { it.id == id }

        override suspend fun insert(download: Download) {
            insertFailure?.let { throw it }
            insertedDownloads.add(download)
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
            if (current.state != DownloadState.PAUSED) return null
            val queued = DownloadStateMachine.transition(current, DownloadState.QUEUED, nowEpochMillis)
            insert(queued)
            return queued
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

    class FakeClock(private var time: Long = 1000L) : Clock {
        override fun currentTimeMillis(): Long = time
    }

    class FakeMetadataRetriever(
        var result: DownloadMetadataResult = DownloadMetadataResult.Success(
            DownloadMetadata(
                url = "https://example.com/test.zip",
                contentLength = 2048L,
                contentType = "application/zip",
                suggestedFilename = "test.zip",
            )
        )
    ) : DownloadMetadataRetriever {
        val calls = mutableListOf<String>()
        var delayDeferred: CompletableDeferred<Unit>? = null

        override suspend fun retrieve(url: String): DownloadMetadataResult {
            calls.add(url)
            delayDeferred?.await()
            return result
        }
    }

    class FakeTransferStarter : TransferStarter {
        val startedTransfers = mutableListOf<Pair<Download, File>>()
        var startFailure: Throwable? = null

        override fun startTransfer(download: Download, tempFile: File) {
            startFailure?.let { throw it }
            startedTransfers.add(download to tempFile)
        }
    }

    class SequentialIdFactory(private val prefix: String = "dl-") : IdFactory {
        private val counter = AtomicInteger(0)
        override fun createId(): String = "$prefix${counter.incrementAndGet()}"
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("coordinator-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun downloadSubmissionPersistsOneRecordAndStartsTransfer() = runBlocking {
        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/archive.zip",
                    contentLength = 4096L,
                    contentType = "application/zip",
                    suggestedFilename = "archive.zip",
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(5000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/archive.zip", startNow = true)
        assertTrue("Expected Success, got $result", result is SubmissionResult.Success)

        val success = result as SubmissionResult.Success
        assertEquals(1, repository.insertedDownloads.size)
        val persisted = repository.insertedDownloads[0]
        assertEquals(success.download.id, persisted.id)
        assertEquals("https://example.com/archive.zip", persisted.url)
        assertEquals("archive.zip", persisted.fileName)
        assertEquals(DownloadState.QUEUED, persisted.state)
        assertEquals(File(tempDir, "archive.zip").absolutePath, persisted.destinationPath)
        assertEquals(4096L, persisted.totalBytes)

        // Final visible file does not exist yet; only .part file was reserved
        assertFalse(File(tempDir, "archive.zip").exists())

        // Transfer engine started exactly once with matching download and temp file
        assertEquals(1, transferStarter.startedTransfers.size)
        assertEquals(persisted.id, transferStarter.startedTransfers[0].first.id)
        val startedTempFile = transferStarter.startedTransfers[0].second
        assertTrue(startedTempFile.exists())
        assertEquals(tempDir.absolutePath, requireNotNull(startedTempFile.parentFile).absolutePath)
        assertTrue(startedTempFile.name.startsWith(".sdm-") && startedTempFile.name.endsWith(".part"))
    }

    @Test
    fun queueSubmissionPersistsOneRecordWithoutStartingTransfer() = runBlocking {
        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/document.pdf",
                    contentLength = 1024L,
                    contentType = "application/pdf",
                    suggestedFilename = "document.pdf",
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(7000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/document.pdf", startNow = false)
        assertTrue("Expected Success, got $result", result is SubmissionResult.Success)

        val success = result as SubmissionResult.Success
        assertEquals(1, repository.insertedDownloads.size)
        val persisted = repository.insertedDownloads[0]
        assertEquals(success.download.id, persisted.id)
        assertEquals(DownloadState.QUEUED, persisted.state)
        assertEquals(File(tempDir, "document.pdf").absolutePath, persisted.destinationPath)

        // Only .part reserved, visible final not created
        assertFalse(File(tempDir, "document.pdf").exists())
        val filesInDir = tempDir.listFiles() ?: emptyArray()
        assertEquals(1, filesInDir.size)
        val reservedPartFile = filesInDir[0]
        assertTrue(reservedPartFile.name.startsWith(".sdm-") && reservedPartFile.name.endsWith(".part"))
        assertTrue(reservedPartFile.exists())

        // Queue must NOT start transfer starter
        assertEquals(0, transferStarter.startedTransfers.size)
    }

    @Test
    fun concurrentDuplicateSubmissionsAreDedupedToOneRecordAndOneStart() = runBlocking {
        val repository = FakeDownloadRepository()
        val gate = CompletableDeferred<Unit>()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/file.bin",
                    contentLength = 8192L,
                    contentType = "application/octet-stream",
                    suggestedFilename = "file.bin",
                )
            )
        )
        .apply { delayDeferred = gate }

        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(9000L),
            idFactory = SequentialIdFactory(),
        )

        val job1 = async(Dispatchers.Default) {
            coordinator.submit("https://example.com/file.bin", startNow = true)
        }
        val job2 = async(Dispatchers.Default) {
            coordinator.submit("https://example.com/file.bin", startNow = true)
        }

        // Release the metadata gate
        gate.complete(Unit)

        val results = awaitAll(job1, job2)
        assertTrue(results.all { it is SubmissionResult.Success })

        // Both jobs return the same persisted download
        val res1 = results[0] as SubmissionResult.Success
        val res2 = results[1] as SubmissionResult.Success
        assertEquals(res1.download.id, res2.download.id)

        // Metadata was fetched only once
        assertEquals(1, retriever.calls.size)

        // Exactly one database record inserted
        assertEquals(1, repository.insertedDownloads.size)

        // Exactly one transfer started
        assertEquals(1, transferStarter.startedTransfers.size)
    }

    @Test
    fun metadataFailureLeavesNoFileAndNoRecord() = runBlocking {
        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Failure.HttpError(
                url = "https://example.com/missing.mp4",
                statusCode = 404,
                statusMessage = "Not Found",
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/missing.mp4", startNow = true)
        assertTrue("Expected Failure, got $result", result is SubmissionResult.Failure)

        val failure = result as SubmissionResult.Failure
        assertEquals("HTTP 404: Not Found", failure.message)

        // No records inserted
        assertEquals(0, repository.insertedDownloads.size)
        // No transfer started
        assertEquals(0, transferStarter.startedTransfers.size)
        // No files left in tempDir
        assertEquals(emptyList<String>(), tempDir.list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun insertFailureDeletesReservedFileAndDoesNotLeakRecord() = runBlocking {
        val repository = FakeDownloadRepository().apply {
            insertFailure = IOException("Disk full or database failure")
        }
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/data.dat",
                    contentLength = 100L,
                    contentType = "application/octet-stream",
                    suggestedFilename = "data.dat",
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/data.dat", startNow = true)
        assertTrue("Expected Failure, got $result", result is SubmissionResult.Failure)

        val failure = result as SubmissionResult.Failure
        assertEquals("Disk full or database failure", failure.message)

        // No records inserted into repository
        assertEquals(0, repository.insertedDownloads.size)
        // No transfer started
        assertEquals(0, transferStarter.startedTransfers.size)
        // Reserved temp file deleted on cleanup
        assertFalse(File(tempDir, "data.dat").exists())
        assertEquals(emptyList<String>(), tempDir.list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun transferStarterFailureDeletesInsertedRecordAndCleansUpTemp() = runBlocking {
        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/movie.mp4",
                    contentLength = 5000L,
                    contentType = "video/mp4",
                    suggestedFilename = "movie.mp4",
                )
            )
        )
        val transferStarter = FakeTransferStarter().apply {
            startFailure = IllegalStateException("Transfer engine failed to start")
        }
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/movie.mp4", startNow = true)
        assertTrue("Expected Failure, got $result", result is SubmissionResult.Failure)

        val failure = result as SubmissionResult.Failure
        assertEquals("Transfer engine failed to start", failure.message)

        // The inserted record should have been cleaned up (deleted) so no orphan record remains
        assertEquals(0, repository.downloads.value.size)
        // Reserved temp file deleted on cleanup
        assertFalse(File(tempDir, "movie.mp4").exists())
        assertEquals(emptyList<String>(), tempDir.list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun maxByteFilenameCanBeReservedSuccessfully() = runBlocking {
        val longName = "a".repeat(251) + ".zip" // exactly 255 bytes UTF-8
        assertEquals(255, longName.toByteArray(Charsets.UTF_8).size)

        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/$longName",
                    contentLength = 1024L,
                    contentType = "application/zip",
                    suggestedFilename = longName,
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { tempDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/$longName", startNow = true)
        assertTrue("Expected Success, got $result", result is SubmissionResult.Success)

        val success = result as SubmissionResult.Success
        assertEquals(longName, success.download.fileName)
        assertEquals(1, transferStarter.startedTransfers.size)
        val reservedTemp = transferStarter.startedTransfers[0].second
        assertTrue(reservedTemp.name.length <= 255)
        assertTrue(reservedTemp.exists())
    }

    @Test
    fun reservationFailsWhenTargetIsNotADirectory() = runBlocking {
        val fileNotDir = File(tempDir, "aFileInsteadOfDir.txt")
        fileNotDir.createNewFile()

        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/file.zip",
                    contentLength = 100L,
                    contentType = "application/zip",
                    suggestedFilename = "file.zip",
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { fileNotDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/file.zip", startNow = true)
        assertTrue("Expected Failure, got $result", result is SubmissionResult.Failure)

        val failure = result as SubmissionResult.Failure
        assertTrue(
            "Expected directory error message, got: ${failure.message}",
            failure.message.contains("not a directory", ignoreCase = true)
        )

        assertEquals(0, repository.insertedDownloads.size)
        assertEquals(0, transferStarter.startedTransfers.size)
    }

    @Test
    fun reservationPromptlyFailsOnIoErrorWithoutInfiniteLoop() = runBlocking {
        // Read-only directory causes IOException on createNewFile or reservation failure
        val readOnlyDir = File(tempDir, "readonly").apply { mkdirs() }
        readOnlyDir.setReadOnly()

        val repository = FakeDownloadRepository()
        val retriever = FakeMetadataRetriever(
            DownloadMetadataResult.Success(
                DownloadMetadata(
                    url = "https://example.com/file.zip",
                    contentLength = 100L,
                    contentType = "application/zip",
                    suggestedFilename = "file.zip",
                )
            )
        )
        val transferStarter = FakeTransferStarter()
        val coordinator = DownloadSubmissionCoordinator(
            metadataRetriever = retriever,
            repository = repository,
            directoryProvider = { readOnlyDir },
            transferStarter = transferStarter,
            clock = FakeClock(1000L),
            idFactory = SequentialIdFactory(),
        )

        val result = coordinator.submit("https://example.com/file.zip", startNow = true)
        assertTrue("Expected Failure, got $result", result is SubmissionResult.Failure)

        val failure = result as SubmissionResult.Failure
        assertTrue(
            "Expected failure due to I/O error or reservation, got: ${failure.message}",
            failure.cause is IOException || failure.message.contains("Failed to reserve", ignoreCase = true)
        )

        assertEquals(0, repository.insertedDownloads.size)
        assertEquals(0, transferStarter.startedTransfers.size)

        // Restore write permissions for teardown
        readOnlyDir.setWritable(true)
        Unit
    }
}
