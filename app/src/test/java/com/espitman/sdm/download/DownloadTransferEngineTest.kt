package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DownloadTransferEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    class FakeClock(private var currentTime: Long = 1000L) : Clock {
        override fun currentTimeMillis(): Long = currentTime

        fun advance(millis: Long) {
            currentTime += millis
        }
    }

    class FakeDownloadRepository(initialDownloads: List<Download> = emptyList()) : DownloadRepository {
        private val _downloads = MutableStateFlow(initialDownloads)
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

        val transitions = mutableListOf<Triple<String, DownloadState, String?>>()
        val progressUpdates = mutableListOf<Pair<String, Long>>()

        override suspend fun awaitInitialized() {}

        override suspend fun get(id: String): Download? =
            _downloads.value.find { it.id == id }

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
            transitions.add(Triple(id, to, error))
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
            progressUpdates.add(id to downloadedBytes)
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val updated = current.copy(
                downloadedBytes = downloadedBytes,
                updatedAtEpochMillis = nowEpochMillis,
            )
            insert(updated)
            return updated
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "sdm_engine_tests_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun multiMegabyteByteForByteStreaming() = runBlocking {
        // Create 3 MB of deterministic pseudo-random bytes
        val totalBytes = 3 * 1024 * 1024
        val payload = ByteArray(totalBytes) { (it % 251).toByte() }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(payload)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val downloadId = "test-stream-dl"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_stream.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/large.bin").toString(),
                    fileName = "large.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = totalBytes.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "stream.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 8192,
            progressUpdateIntervalBytes = 64 * 1024L,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/large.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Assert temp is gone
        assertFalse(tempFile.exists())

        // Assert destination exists byte-for-byte
        assertTrue(destFile.exists())
        assertEquals(totalBytes.toLong(), destFile.length())
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(destFile.readBytes())
        assertArrayEquals(expectedDigest, actualDigest)

        // Final repository state COMPLETED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.COMPLETED, finalDownload!!.state)

        // Transition sequence includes COMPLETED
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED), repo.transitions.map { it.second })

        // Verify progress updates are monotonically increasing and final count is exact
        assertTrue(repo.progressUpdates.isNotEmpty())
        var last = 0L
        for (update in repo.progressUpdates) {
            assertTrue("Progress was not monotonically increasing: ${update.second} vs $last", update.second > last)
            last = update.second
        }
        assertEquals(totalBytes.toLong(), repo.progressUpdates.last().second)
    }

    @Test
    fun maxReadChunkBounded() = runBlocking {
        val maxChunk = 4096
        val payload = ByteArray(16384) { 0x42 }

        val observedChunks = mutableListOf<Int>()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val downloadId = "bounded-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_bounded.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/bounded.bin").toString(),
                    fileName = "bounded.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "bounded.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = maxChunk,
            progressUpdateIntervalBytes = 2048L,
            onChunkRead = { bytesRead ->
                observedChunks.add(bytesRead)
            },
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/bounded.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertTrue(observedChunks.isNotEmpty())
        for (chunk in observedChunks) {
            assertTrue("Observed chunk $chunk must be > 0", chunk > 0)
            assertTrue(
                "Observed read chunk $chunk exceeded max bounded buffer $maxChunk",
                chunk <= maxChunk
            )
        }

        // Temp is gone, destination exists byte-for-byte
        assertFalse(tempFile.exists())
        assertTrue(destFile.exists())
        assertEquals(payload.size.toLong(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())

        // Final repository state COMPLETED and transition sequence includes COMPLETED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.COMPLETED, finalDownload!!.state)
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED), repo.transitions.map { it.second })
    }

    @Test
    fun stateAndProgressSequence() = runBlocking {
        val payload = ByteArray(1000) { 1 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val downloadId = "seq-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_seq.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/seq.bin").toString(),
                    fileName = "seq.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "seq.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 128,
            progressUpdateIntervalBytes = 256L,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/seq.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Temp is gone, destination exists byte-for-byte
        assertFalse(tempFile.exists())
        assertTrue(destFile.exists())
        assertEquals(payload.size.toLong(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())

        // Final repository state COMPLETED and transition sequence includes COMPLETED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.COMPLETED, finalDownload!!.state)
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED), repo.transitions.map { it.second })

        // Progress updates
        val expected = listOf(256L, 512L, 768L, 1000L)
        assertEquals(expected, repo.progressUpdates.map { it.second })
    }

    @Test
    fun httpErrorTransitionsToFailedWithEmptyOrAbsentTemp() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        val downloadId = "http-err-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_missing.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/missing.bin").toString(),
                    fileName = "missing.bin",
                    destinationPath = destFile.absolutePath,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "missing.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/missing.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Verify transitions: QUEUED -> CONNECTING -> FAILED
        assertEquals(2, repo.transitions.size)
        assertEquals(DownloadState.CONNECTING, repo.transitions[0].second)
        assertEquals(DownloadState.FAILED, repo.transitions[1].second)
        assertTrue(repo.transitions[1].third!!.contains("404"))

        // Temp file must be empty or absent, destination absent
        assertTrue(!tempFile.exists() || tempFile.length() == 0L)
        assertFalse(destFile.exists())
    }

    @Test
    fun cancellationClosesResourcesAndPreservesPartialTemp() = runBlocking {
        // Prepare a large streamed response throttled deterministically (8KiB per 50ms)
        val payload = ByteArray(512 * 1024) { 0x55 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
                .throttleBody(8 * 1024, 50, TimeUnit.MILLISECONDS)
        )

        val downloadId = "cancel-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_cancel.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/cancel.bin").toString(),
                    fileName = "cancel.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "cancel.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 1024,
            progressUpdateIntervalBytes = 2048L,
        )

        val transferJob = async(Dispatchers.IO) {
            engine.executeTransfer(
                downloadId = downloadId,
                url = server.url("/cancel.bin").toString(),
                tempFile = tempFile,
                repository = repo,
            )
        }

        // Wait with a bounded timeout until temp length > 0
        withTimeout(5000L) {
            while (!tempFile.exists() || tempFile.length() <= 0L) {
                delay(10)
            }
        }

        // Cancel the job
        val cancelStart = System.currentTimeMillis()
        transferJob.cancel()

        // Expect CancellationException rethrown promptly
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(2000L) {
                    transferJob.await()
                }
            }
        }
        val cancelDuration = System.currentTimeMillis() - cancelStart
        assertTrue("Cancellation should finish promptly, took $cancelDuration ms", cancelDuration < 2000L)

        // Check temp file was preserved and is non-empty partial
        assertTrue(tempFile.exists())
        assertTrue("Expected partial bytes preserved > 0 but was ${tempFile.length()}", tempFile.length() > 0)
        assertTrue("Expected partial bytes < total but was ${tempFile.length()}", tempFile.length() < payload.size)

        // Destination must not be exposed
        assertFalse(destFile.exists())

        // Resources are closed, so we can freely read or delete tempFile
        assertTrue(tempFile.canRead())
    }

    @Test
    fun unknownTotalSuccessfullyFinalizesAndCompletes() = runBlocking {
        val payload = ByteArray(4096) { 0x33 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setChunkedBody(Buffer().write(payload), 512)
        )

        val downloadId = "unknown-total-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_unknown.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/chunked.bin").toString(),
                    fileName = "chunked.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = null, // unknown total
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "unknown.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 512,
            progressUpdateIntervalBytes = 1024L,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/chunked.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Temp is gone, destination exists byte-for-byte
        assertFalse(tempFile.exists())
        assertTrue(destFile.exists())
        assertEquals(payload.size.toLong(), destFile.length())
        assertArrayEquals(payload, destFile.readBytes())

        // Repository state COMPLETED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.COMPLETED, finalDownload!!.state)
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED), repo.transitions.map { it.second })
    }

    @Test
    fun knownTotalShortResponseFailsWithMismatchWhileTempRemainsAndDestinationAbsent() = runBlocking {
        val expectedTotal = 8192L
        val shortPayload = ByteArray(2048) { 0x11 }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(shortPayload))
        )

        val downloadId = "short-response-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_short.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/short.bin").toString(),
                    fileName = "short.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = expectedTotal,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "short.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/short.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Assert temp remains with partial content
        assertTrue(tempFile.exists())
        assertEquals(shortPayload.size.toLong(), tempFile.length())

        // Destination is absent
        assertFalse(destFile.exists())

        // Final state FAILED with mismatch error
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.FAILED, finalDownload!!.state)
        assertTrue(finalDownload.error!!.contains("mismatch"))
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.FAILED), repo.transitions.map { it.second })
    }

    @Test
    fun preexistingDestinationIsNeverOverwrittenNoNetworkTempRemainsStateFailed() = runBlocking {
        val downloadId = "preexist-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_exists.bin")
        val existingContent = "original content".toByteArray()
        destFile.writeBytes(existingContent)

        val tempFile = File(tempDir, "preexist.tmp")
        val tempContent = "temp partial content".toByteArray()
        tempFile.writeBytes(tempContent)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/existing.bin").toString(),
                    fileName = "existing.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 100L,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/existing.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // No network request made
        assertEquals(0, server.requestCount)

        // Pre-existing destination is never overwritten
        assertTrue(destFile.exists())
        assertArrayEquals(existingContent, destFile.readBytes())

        // Temp remains intact
        assertTrue(tempFile.exists())
        assertArrayEquals(tempContent, tempFile.readBytes())

        // Final state FAILED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.FAILED, finalDownload!!.state)
        assertTrue(finalDownload.error!!.contains("already exists"))
    }

    @Test
    fun blankOrNullDestinationRecordsFailedWithoutNetwork() = runBlocking {
        val downloadId = "blank-dest-test"
        val clock = FakeClock(1000L)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/blank.bin").toString(),
                    fileName = "blank.bin",
                    destinationPath = null, // null destination
                    totalBytes = 100L,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "blank.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/blank.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // No network request made
        assertEquals(0, server.requestCount)

        // Final state FAILED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.FAILED, finalDownload!!.state)
        assertTrue(finalDownload.error!!.contains("Destination path"))
    }

    @Test
    fun moveFinalizationErrorRecordsFailedAndDoesNotExposePartialDestination() = runBlocking {
        val payload = ByteArray(1024) { 0x77 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
        )

        val downloadId = "move-error-test"
        val clock = FakeClock(1000L)

        // Create a parent path that is a regular file so destination mkdirs/move deterministically fails
        val blockingFile = File(tempDir, "blocking_file")
        blockingFile.writeText("I am a file, not a directory")
        val impossibleDestFile = File(blockingFile, "dest_fail.bin")

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/move_err.bin").toString(),
                    fileName = "move_err.bin",
                    destinationPath = impossibleDestFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        val tempFile = File(tempDir, "move_err.tmp")
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/move_err.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        // Partial destination must not be exposed
        assertFalse(impossibleDestFile.exists())

        // Final state FAILED
        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.FAILED, finalDownload!!.state)
        assertTrue(finalDownload.error!!.isNotBlank())
        assertEquals(listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.FAILED), repo.transitions.map { it.second })
    }
}
