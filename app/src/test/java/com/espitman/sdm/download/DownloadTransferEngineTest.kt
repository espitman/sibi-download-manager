package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFailure
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadProgressAlignment
import com.espitman.sdm.domain.DownloadRetryFailedMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.storage.DownloadDestinationPublisher
import com.espitman.sdm.storage.PublishedDownloadDestination
import com.espitman.sdm.storage.StorageCapacity
import com.espitman.sdm.storage.StorageCapacityProbe
import com.espitman.sdm.storage.TransferSpacePreflight
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
import javax.net.ssl.SSLHandshakeException
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

        override suspend fun alignDownloadedBytes(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download {
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val aligned = DownloadProgressAlignment.apply(
                current,
                fileLengthBytes,
                nowEpochMillis,
            )
            if (aligned != current) insert(aligned)
            return aligned
        }

        override suspend fun retryFailed(
            id: String,
            automatic: Boolean,
            nowEpochMillis: Long,
        ): Download? {
            val current = get(id) ?: return null
            val queued = DownloadRetryFailedMutation.apply(
                current,
                automatic,
                nowEpochMillis,
            ) ?: return null
            transitions.add(Triple(id, DownloadState.QUEUED, null))
            insert(queued)
            return queued
        }

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
        ): Download? = pauseAtExactOffset(id, fileLengthBytes, nowEpochMillis, pauseCause = null)

        override suspend fun pauseAtExactOffset(
            id: String,
            fileLengthBytes: Long,
            nowEpochMillis: Long,
            pauseCause: com.espitman.sdm.domain.DownloadPauseCause?,
        ): Download? {
            val current = get(id) ?: return null
            val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis, pauseCause)
            if (paused != current) {
                transitions.add(Triple(id, DownloadState.PAUSED, null))
                insert(paused)
            }
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
            if (cancelled != current) {
                transitions.add(Triple(id, DownloadState.CANCELLED, null))
                insert(cancelled)
            }
            return cancelled
        }

        override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? {
            val current = get(id) ?: return null
            if (current.state != DownloadState.PAUSED) return null
            val queued = DownloadStateMachine.transition(current, DownloadState.QUEUED, nowEpochMillis)
            transitions.add(Triple(id, DownloadState.QUEUED, null))
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
            val updated = com.espitman.sdm.domain.DownloadFreshRestartMutation.apply(
                current = current,
                nowEpochMillis = nowEpochMillis,
                etag = etag,
                lastModified = lastModified,
                totalBytes = totalBytes,
            )
            insert(updated)
            return updated
        }

        override suspend fun updateDestination(
            id: String,
            destinationPath: String,
            destinationTreeUri: String?,
            destinationDisplayLabel: String?,
            fileName: String,
            nowEpochMillis: Long,
        ): Download {
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            val updated = current.copy(
                fileName = fileName,
                destinationPath = destinationPath,
                destinationTreeUri = destinationTreeUri,
                destinationDisplayLabel = destinationDisplayLabel,
                updatedAtEpochMillis = maxOf(nowEpochMillis, current.updatedAtEpochMillis),
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
        assertFalse(finalDownload.error!!.contains(destFile.path))
        assertFalse(finalDownload.error!!.contains(tempFile.path))
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
    fun http200ToRangeRequestRestartsOnSeparateTempWithoutAppending() = runBlocking {
        val prefix = byteArrayOf(3, 3, 3, 3)
        val fresh = ByteArray(8) { 9 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v2\"")
                .setHeader(HttpRangeResume.HEADER_LAST_MODIFIED, "Thu, 22 Oct 2015 07:28:00 GMT")
                .setBody(Buffer().write(fresh))
        )

        val downloadId = "resume-status-200"
        val clock = FakeClock(1000L)
        val destFile = File(tempDir, "status.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val restartFile = DownloadPartFile.restartForDestination(destFile)

        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/status.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
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

        val recorded = server.takeRequest()
        assertEquals("bytes=4-", recorded.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals("\"file-v1\"", recorded.getHeader(HttpRangeResume.HEADER_IF_RANGE))
        assertEquals(1, server.requestCount)

        assertFalse(tempFile.exists())
        assertFalse(restartFile.exists())
        assertTrue(destFile.exists())
        assertArrayEquals(fresh, destFile.readBytes())
        assertFalse(destFile.readBytes().contentEquals(prefix + fresh))

        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.COMPLETED, finalDownload.state)
        assertEquals("\"file-v2\"", finalDownload.etag)
        assertEquals("Thu, 22 Oct 2015 07:28:00 GMT", finalDownload.lastModified)
        assertEquals(8L, finalDownload.totalBytes)
        assertEquals(8L, finalDownload.downloadedBytes)
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.COMPLETED),
            repo.transitions.map { it.second },
        )
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

    @Test
    fun http416TriggersOneFreshNonRangeGetAndDoesNotAppend() = runBlocking {
        val prefix = byteArrayOf(1, 1, 1, 1)
        val fresh = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        server.enqueue(MockResponse().setResponseCode(416).setBody("Range Not Satisfiable"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v2\"")
                .setHeader(HttpRangeResume.HEADER_LAST_MODIFIED, "Fri, 23 Oct 2015 07:28:00 GMT")
                .setBody(Buffer().write(fresh))
        )

        val downloadId = "resume-416"
        val destFile = File(tempDir, "range-416.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val restartFile = DownloadPartFile.restartForDestination(destFile)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/range-416.bin").toString(),
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
            clock = FakeClock(1000L),
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/range-416.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        val ranged = server.takeRequest()
        val freshGet = server.takeRequest()
        assertEquals("bytes=4-", ranged.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals(null, freshGet.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals(2, server.requestCount)

        assertFalse(tempFile.exists())
        assertFalse(restartFile.exists())
        assertArrayEquals(fresh, destFile.readBytes())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.COMPLETED, finalDownload.state)
        assertEquals("\"file-v2\"", finalDownload.etag)
        assertEquals("Fri, 23 Oct 2015 07:28:00 GMT", finalDownload.lastModified)
        assertEquals(8L, finalDownload.downloadedBytes)
    }

    @Test
    fun resumeFallbackFailurePreservesOriginalPart() = runBlocking {
        val prefix = byteArrayOf(4, 5, 6, 7)
        server.enqueue(MockResponse().setResponseCode(416).setBody("Range Not Satisfiable"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable"))

        val downloadId = "resume-fallback-fail"
        val destFile = File(tempDir, "fallback-fail.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val restartFile = DownloadPartFile.restartForDestination(destFile)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/fallback-fail.bin").toString(),
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
            clock = FakeClock(1000L),
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/fallback-fail.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        assertEquals(2, server.requestCount)
        assertTrue(tempFile.exists())
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(restartFile.exists())
        assertFalse(destFile.exists())
        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, finalDownload.state)
        assertTrue(finalDownload.error!!.contains("500"))
        assertEquals("\"file-v1\"", finalDownload.etag)
        assertEquals(prefix.size.toLong(), finalDownload.downloadedBytes)
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun resumeFallbackCancellationLeavesUsableNewPartial() = runBlocking {
        val prefix = byteArrayOf(1, 2, 3, 4)
        val fresh = ByteArray(256 * 1024) { 0x22 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v2\"")
                .setBody(Buffer().write(fresh))
                .throttleBody(8 * 1024, 50, TimeUnit.MILLISECONDS)
        )

        val downloadId = "resume-fallback-cancel"
        val destFile = File(tempDir, "fallback-cancel.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val restartFile = DownloadPartFile.restartForDestination(destFile)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/fallback-cancel.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = fresh.size.toLong(),
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1000L,
                )
            )
        )
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = FakeClock(1000L),
            bufferSizeBytes = 1024,
            progressUpdateIntervalBytes = 2048L,
        )
        val pauseOnCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val transferJob = async(Dispatchers.IO) {
            engine.executeTransfer(
                downloadId = downloadId,
                url = server.url("/fallback-cancel.bin").toString(),
                tempFile = tempFile,
                repository = repo,
                pauseRequested = { pauseOnCancel.get() },
                pauseCause = { DownloadPauseCause.NETWORK_POLICY },
            )
        }
        withTimeout(5000L) {
            while (!restartFile.exists() || restartFile.length() <= 0L) {
                delay(10)
            }
        }
        pauseOnCancel.set(true)
        transferJob.cancel()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(2000L) { transferJob.await() }
            }
        }

        assertFalse(destFile.exists())
        assertTrue(tempFile.exists())
        val partial = tempFile.readBytes()
        assertTrue(partial.isNotEmpty())
        assertTrue(partial.size < fresh.size)
        assertEquals(0x22.toByte(), partial[0])
        assertFalse(partial.contentEquals(prefix + partial.copyOfRange(0, (partial.size - prefix.size).coerceAtLeast(0))))
        assertArrayEquals(fresh.copyOf(partial.size), partial)
        assertFalse(restartFile.exists())
        val paused = repo.get(downloadId)!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(partial.size.toLong(), paused.downloadedBytes)
        assertEquals("\"file-v2\"", paused.etag)
        assertEquals(DownloadPauseCause.NETWORK_POLICY, paused.pauseCause)
    }

    @Test
    fun resumeFallbackPersistsNewValidatorsAndChecksumOfFreshBody() = runBlocking {
        val prefix = byteArrayOf(7, 7, 7, 7)
        val fresh = ByteArray(64) { it.toByte() }
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(fresh)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v9\"")
                .setHeader(HttpRangeResume.HEADER_LAST_MODIFIED, "Sat, 24 Oct 2015 07:28:00 GMT")
                .setBody(Buffer().write(fresh))
        )

        val downloadId = "resume-fallback-checksum"
        val destFile = File(tempDir, "fallback-checksum.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/fallback-checksum.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
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
            clock = FakeClock(1000L),
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/fallback-checksum.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )

        val finalDownload = repo.get(downloadId)!!
        assertEquals(DownloadState.COMPLETED, finalDownload.state)
        assertEquals("\"file-v9\"", finalDownload.etag)
        assertEquals("Sat, 24 Oct 2015 07:28:00 GMT", finalDownload.lastModified)
        assertEquals(fresh.size.toLong(), finalDownload.totalBytes)
        assertArrayEquals(fresh, destFile.readBytes())
        assertArrayEquals(expectedDigest, MessageDigest.getInstance("SHA-256").digest(destFile.readBytes()))
        assertFalse(tempFile.exists())
    }

    @Test
    fun slowTransferPublishesProgressAfterOneSecondBefore64KiB() = runBlocking {
        val payload = ByteArray(10_000) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val downloadId = "slow-progress"
        val clock = FakeClock(10_000L)
        val destFile = File(tempDir, "slow.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/slow.bin").toString(),
                    fileName = "slow.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 10_000L,
                )
            )
        )
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 1_000,
            progressUpdateIntervalBytes = 64 * 1024L,
            onChunkRead = { clock.advance(250L) },
        )

        engine.executeTransfer(
            downloadId = downloadId,
            url = server.url("/slow.bin").toString(),
            tempFile = File(tempDir, "slow.tmp"),
            repository = repo,
        )

        val published = repo.progressUpdates.map { it.second }
        assertTrue(published.any { it < 64 * 1024L && it < payload.size.toLong() })
        assertEquals(4_000L, published.first())
        assertTrue(published.none { it in 1L until 4_000L })
        assertEquals(payload.size.toLong(), published.last())
        assertEquals(DownloadState.COMPLETED, repo.get(downloadId)!!.state)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun progressDoesNotPublishBeforeByteOrOneSecondThreshold() = runBlocking {
        val payload = ByteArray(20_000) { 9 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val downloadId = "quiet-progress"
        val clock = FakeClock(5_000L)
        val destFile = File(tempDir, "quiet.bin")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/quiet.bin").toString(),
                    fileName = "quiet.bin",
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 5_000L,
                )
            )
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            bufferSizeBytes = 5_000,
            progressUpdateIntervalBytes = 64 * 1024L,
            onChunkRead = { clock.advance(100L) },
        ).executeTransfer(
            downloadId = downloadId,
            url = server.url("/quiet.bin").toString(),
            tempFile = File(tempDir, "quiet.tmp"),
            repository = repo,
        )

        assertEquals(listOf(payload.size.toLong()), repo.progressUpdates.map { it.second })
        assertEquals(DownloadState.COMPLETED, repo.get(downloadId)!!.state)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun completedTreePublishUpdatesTheStoredDestinationBeforeCompletion() = runBlocking {
        val payload = ByteArray(32) { 4 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "publish.bin")
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/1"
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "publish",
                    url = server.url("/publish.bin").toString(),
                    fileName = "publish.bin",
                    destinationPath = destFile.absolutePath,
                    destinationTreeUri = treeUri,
                    destinationDisplayLabel = "Download",
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            destinationPublisher = DownloadDestinationPublisher { download, localFile ->
                assertEquals(destFile.canonicalFile, localFile.canonicalFile)
                assertTrue(localFile.isFile)
                PublishedDownloadDestination(
                    destinationPath = documentUri,
                    destinationTreeUri = download.destinationTreeUri,
                    destinationDisplayLabel = download.destinationDisplayLabel,
                    fileName = download.fileName,
                )
            },
        )
        engine.executeTransfer(
            downloadId = "publish",
            url = server.url("/publish.bin").toString(),
            tempFile = File(tempDir, "publish.tmp"),
            repository = repo,
        )
        val completed = repo.get("publish")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertEquals(documentUri, completed.destinationPath)
        assertEquals(treeUri, completed.destinationTreeUri)
        assertEquals("Download", completed.destinationDisplayLabel)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun insufficientFreshSpaceFailsBeforeWritingResponseBytes() = runBlocking {
        val payload = ByteArray(8) { 1 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "fresh-space.bin")
        val tempFile = File(tempDir, "fresh-space.part")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "fresh-space",
                    url = server.url("/fresh-space.bin").toString(),
                    fileName = destFile.name,
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        var written = 0
        val probe = RecordingStorageCapacityProbe(
            local = StorageCapacity.from(totalBytes = 100L, availableBytes = 7L),
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            onChunkRead = { written += it },
            storageCapacity = probe,
        ).executeTransfer(
            downloadId = "fresh-space",
            url = server.url("/fresh-space.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        val failed = repo.get("fresh-space")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify(failed.error),
        )
        assertEquals(TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR, failed.error)
        assertEquals(0, written)
        assertFalse(destFile.exists())
        assertTrue(!tempFile.exists() || tempFile.length() == 0L)
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun exactAvailableBoundaryCompletesAFreshTransfer() = runBlocking {
        val payload = ByteArray(8) { 2 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "exact-space.bin")
        val tempFile = File(tempDir, "exact-space.part")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "exact-space",
                    url = server.url("/exact-space.bin").toString(),
                    fileName = destFile.name,
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            storageCapacity = RecordingStorageCapacityProbe(
                local = StorageCapacity.from(totalBytes = 8L, availableBytes = 8L),
            ),
        ).executeTransfer(
            downloadId = "exact-space",
            url = server.url("/exact-space.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        assertEquals(DownloadState.COMPLETED, repo.get("exact-space")!!.state)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun resumeInsufficientRemainingPreservesExistingPartialAndWritesNothing() = runBlocking {
        val prefix = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 4-7/8")
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v1\"")
                .setBody(Buffer().write(byteArrayOf(5, 6, 7, 8))),
        )
        val destFile = File(tempDir, "resume-space.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "resume-space",
                    url = server.url("/resume-space.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        var written = 0
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            onChunkRead = { written += it },
            storageCapacity = RecordingStorageCapacityProbe(
                local = StorageCapacity.from(totalBytes = 100L, availableBytes = 3L),
            ),
        ).executeTransfer(
            downloadId = "resume-space",
            url = server.url("/resume-space.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        val failed = repo.get("resume-space")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify(failed.error))
        assertEquals(0, written)
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(destFile.exists())
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun safFinalCapacityBlocksWhenProviderExposesTooLittleSpace() = runBlocking {
        val payload = ByteArray(8) { 3 }
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "saf-space.bin")
        val tempFile = File(tempDir, "saf-space.part")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "saf-space",
                    url = server.url("/saf-space.bin").toString(),
                    fileName = destFile.name,
                    destinationPath = destFile.absolutePath,
                    destinationTreeUri = treeUri,
                    destinationDisplayLabel = "Download",
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        var written = 0
        val probe = RecordingStorageCapacityProbe(
            local = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 8L),
            trees = mapOf(treeUri to StorageCapacity.from(totalBytes = 100L, availableBytes = 7L)),
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            onChunkRead = { written += it },
            storageCapacity = probe,
        ).executeTransfer(
            downloadId = "saf-space",
            url = server.url("/saf-space.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        val failed = repo.get("saf-space")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify(failed.error))
        assertEquals(0, written)
        assertEquals(listOf(treeUri), probe.treeUris)
        assertFalse(destFile.exists())
        assertEquals(treeUri, failed.destinationTreeUri)
    }

    @Test
    fun unknownSafCapacityDoesNotBlockWhenLocalStagingFits() = runBlocking {
        val payload = ByteArray(8) { 4 }
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "saf-unknown.bin")
        val tempFile = File(tempDir, "saf-unknown.part")
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "saf-unknown",
                    url = server.url("/saf-unknown.bin").toString(),
                    fileName = destFile.name,
                    destinationPath = destFile.absolutePath,
                    destinationTreeUri = treeUri,
                    destinationDisplayLabel = "Download",
                    totalBytes = payload.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            destinationPublisher = DownloadDestinationPublisher { download, _ ->
                PublishedDownloadDestination(
                    destinationPath = destFile.absolutePath,
                    destinationTreeUri = download.destinationTreeUri,
                    destinationDisplayLabel = download.destinationDisplayLabel,
                    fileName = download.fileName,
                )
            },
            storageCapacity = RecordingStorageCapacityProbe(
                local = StorageCapacity.from(totalBytes = 1_000L, availableBytes = 8L),
            ),
        ).executeTransfer(
            downloadId = "saf-unknown",
            url = server.url("/saf-unknown.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        assertEquals(DownloadState.COMPLETED, repo.get("saf-unknown")!!.state)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun freshRestartInsufficientSpaceUsesNewSizeAndLeavesOriginalPart() = runBlocking {
        val prefix = byteArrayOf(3, 3, 3, 3)
        val fresh = ByteArray(8) { 9 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v2\"")
                .setBody(Buffer().write(fresh)),
        )
        val destFile = File(tempDir, "restart-space.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val restartFile = DownloadPartFile.restartForDestination(destFile)
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = "restart-space",
                    url = server.url("/restart-space.bin").toString(),
                    fileName = destFile.name,
                    etag = "\"file-v1\"",
                    destinationPath = destFile.absolutePath,
                    totalBytes = 8L,
                    downloadedBytes = prefix.size.toLong(),
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1_000L,
                ),
            ),
        )
        var written = 0
        val probe = RecordingStorageCapacityProbe(
            local = StorageCapacity.from(totalBytes = 100L, availableBytes = 7L),
        )
        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            onChunkRead = { written += it },
            storageCapacity = probe,
        ).executeTransfer(
            downloadId = "restart-space",
            url = server.url("/restart-space.bin").toString(),
            tempFile = tempFile,
            repository = repo,
        )
        val failed = repo.get("restart-space")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify(failed.error))
        assertEquals(0, written)
        assertArrayEquals(prefix, tempFile.readBytes())
        assertFalse(restartFile.exists())
        assertFalse(destFile.exists())
        assertEquals("\"file-v1\"", failed.etag)
        assertTrue(probe.localPaths.any { it.name == restartFile.name })
        assertEquals(
            listOf(DownloadState.CONNECTING, DownloadState.FAILED),
            repo.transitions.map { it.second },
        )
    }

    @Test
    fun tlsHandshakeFailureKeepsDestinationAbsentAndReportsReason() = runBlocking {
        val destination = File(tempDir, "tls-failure.bin")
        val part = File(tempDir, "tls-failure.part")
        val url = server.url("/tls-failure.bin").toString()
        val repo = FakeDownloadRepository(listOf(
            Download(
                id = "tls-failure",
                url = url,
                fileName = destination.name,
                destinationPath = destination.absolutePath,
                state = DownloadState.QUEUED,
                createdAtEpochMillis = 1_000L,
            ),
        ))
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { throw SSLHandshakeException("certificate validation failed") }
            .build()

        DownloadTransferEngine(failingClient, ioDispatcher = Dispatchers.IO).executeTransfer(
            downloadId = "tls-failure",
            url = url,
            tempFile = part,
            repository = repo,
        )

        val failed = repo.get("tls-failure")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertTrue(failed.error.orEmpty().contains("certificate validation failed"))
        assertFalse(destination.exists())
        assertFalse(part.exists())
    }

    @Test
    fun missingDestinationDirectoryBlockedByFileKeepsDownloadedPartForRecovery() = runBlocking {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val blockedParent = File(tempDir, "removed-folder")
        blockedParent.writeText("folder was replaced by a file")
        val destination = File(blockedParent, "recovery.bin")
        val part = File(tempDir, "recovery.part")
        val url = server.url("/recovery.bin").toString()
        val repo = FakeDownloadRepository(listOf(
            Download(
                id = "missing-folder",
                url = url,
                fileName = destination.name,
                destinationPath = destination.absolutePath,
                totalBytes = payload.size.toLong(),
                state = DownloadState.QUEUED,
                createdAtEpochMillis = 1_000L,
            ),
        ))

        val engine = DownloadTransferEngine(OkHttpClient(), ioDispatcher = Dispatchers.IO)
        engine.executeTransfer(
            downloadId = "missing-folder",
            url = url,
            tempFile = part,
            repository = repo,
        )

        val failed = repo.get("missing-folder")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertTrue(failed.error.orEmpty().contains("not a directory"))
        assertFalse(failed.error.orEmpty().contains(blockedParent.path))
        assertArrayEquals(payload, part.readBytes())
        assertFalse(destination.exists())

        assertTrue(blockedParent.delete())
        assertTrue(blockedParent.mkdirs())
        repo.transition(
            "missing-folder",
            DownloadState.QUEUED,
            nowEpochMillis = failed.updatedAtEpochMillis + 1L,
        )
        engine.executeTransfer(
            downloadId = "missing-folder",
            url = url,
            tempFile = part,
            repository = repo,
        )
        assertEquals(DownloadState.COMPLETED, repo.get("missing-folder")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertFalse(part.exists())
        assertEquals(1, server.requestCount)
    }

    private class RecordingStorageCapacityProbe(
        var local: StorageCapacity,
        var trees: Map<String, StorageCapacity> = emptyMap(),
    ) : StorageCapacityProbe {
        val localPaths = mutableListOf<File>()
        val treeUris = mutableListOf<String>()

        override fun queryLocalPath(path: File): StorageCapacity {
            localPaths.add(path)
            return local
        }

        override fun queryTree(treeUri: String): StorageCapacity {
            treeUris.add(treeUri)
            return trees[treeUri] ?: StorageCapacity.Unknown
        }
    }
}
