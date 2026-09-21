package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseMutation
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

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? {
            val current = get(id) ?: return null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis)
            if (paused != current) {
                transitions.add(Triple(id, DownloadState.PAUSED, null))
                insert(paused)
            }
            return paused
        }

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? {
            val current = get(id) ?: return null
            if (current.state != DownloadState.PAUSED) return null
            val queued = DownloadStateMachine.transition(current, DownloadState.QUEUED, nowEpochMillis)
            transitions.add(Triple(id, DownloadState.QUEUED, null))
            insert(queued)
            return queued
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
    fun httpTerminalFailureDeletesEmptyReservedPartFile() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        val downloadId = "http-terminal-empty-part-test"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "dest_terminal_fail.bin")
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

        val tempFile = File(tempDir, "reserved.part")
        assertTrue(tempFile.createNewFile())
        assertTrue(tempFile.exists())
        assertEquals(0L, tempFile.length())

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

        // Empty reserved .part file must be deleted on terminal HTTP failure
        assertFalse("Empty reserved .part file must be deleted on HTTP terminal failure", tempFile.exists())
        assertFalse("Destination must not be exposed", destFile.exists())
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

    @Test
    fun resumeWithValidatedPartialContentAppendsIntoExistingPartAndCompletes() = runBlocking {
        val prefix = byteArrayOf(1, 2, 3, 4)
        val suffix = byteArrayOf(5, 6, 7, 8)
        val etag = "\"file-v1\""
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 4-7/8")
                .setHeader(HttpRangeResume.HEADER_ETAG, etag)
                .setHeader(HttpRangeResume.HEADER_LAST_MODIFIED, lastModified)
                .setBody(Buffer().write(suffix))
        )

        val downloadId = "resume-success"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "resume.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/resume.bin").toString(),
                    fileName = destFile.name,
                    etag = etag,
                    lastModified = lastModified,
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
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
            url = server.url("/resume.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        val recorded = server.takeRequest()
        assertEquals("bytes=4-", recorded.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals(etag, recorded.getHeader(HttpRangeResume.HEADER_IF_RANGE))

        assertFalse(tempFile.exists())
        assertTrue(destFile.exists())
        assertArrayEquals(prefix + suffix, destFile.readBytes())

        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.COMPLETED, finalDownload!!.state)
        assertEquals(8L, finalDownload.downloadedBytes)
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun resumeContentRangeMismatchPreservesExistingPartAndFails() = runBlocking {
        val prefix = byteArrayOf(9, 8, 7, 6)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 0-3/8")
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v1\"")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4)))
        )

        val downloadId = "resume-range-mismatch"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "mismatch.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/mismatch.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/mismatch.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())

        val finalDownload = repo.get(downloadId)
        assertNotNull(finalDownload)
        assertEquals(DownloadState.FAILED, finalDownload!!.state)
        assertTrue(finalDownload.error!!.contains("Content-Range"))
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
        assertEquals("bytes=4-", server.takeRequest().getHeader(HttpRangeResume.HEADER_RANGE))
    }

    @Test
    fun resumeValidatorMismatchPreservesExistingPartAndFails() = runBlocking {
        val prefix = byteArrayOf(1, 1, 1, 1)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 4-7/8")
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"other-version\"")
                .setBody(Buffer().write(byteArrayOf(2, 2, 2, 2)))
        )

        val downloadId = "resume-etag-mismatch"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "etag.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/etag.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/etag.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, finalDownload.state)
        assertTrue(finalDownload.error!!.contains("ETag"))
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun resumeNonPartialStatusPreservesExistingPartAndDoesNotTruncate() = runBlocking {
        val prefix = byteArrayOf(3, 3, 3, 3)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v1\"")
                .setBody(Buffer().write(ByteArray(8) { 9 }))
        )

        val downloadId = "resume-status-200"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "status.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/status.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/status.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, finalDownload.state)
        assertTrue(finalDownload.error!!.contains("206"))
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
        val recorded = server.takeRequest()
        assertEquals("bytes=4-", recorded.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals("\"file-v1\"", recorded.getHeader(HttpRangeResume.HEADER_IF_RANGE))
    }

    @Test
    fun resumeWithoutStoredValidatorsPreservesExistingPartAndFails() = runBlocking {
        val prefix = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 4-7/8")
                .setBody(Buffer().write(byteArrayOf(5, 6, 7, 8)))
        )

        val downloadId = "resume-no-validators"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "no-validators.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/no-validators.bin").toString(),
                    fileName = destFile.name,
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/no-validators.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertEquals(0, server.requestCount)
        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, finalDownload.state)
        assertTrue(finalDownload.error!!.contains("ETag") || finalDownload.error!!.contains("Last-Modified"))
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun resumeBodyLengthMismatchPreservesExistingPartAndFails() = runBlocking {
        val prefix = byteArrayOf(9, 8, 7, 6)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 4-7/8")
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v1\"")
                .setBody(Buffer().write(ByteArray(8) { 1 }))
        )

        val downloadId = "resume-body-mismatch"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "body-mismatch.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/body-mismatch.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/body-mismatch.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, finalDownload.state)
        assertTrue(
            finalDownload.error!!.contains("Content-Length") ||
                finalDownload.error!!.contains("Content-Range") ||
                finalDownload.error!!.contains("body length"),
        )
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }
}
