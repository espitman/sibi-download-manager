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
import okio.buffer
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/large.bin").toString(),
                    fileName = "large.bin",
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

        assertTrue(tempFile.exists())
        assertEquals(totalBytes.toLong(), tempFile.length())

        val actualDigest = MessageDigest.getInstance("SHA-256").digest(tempFile.readBytes())
        assertArrayEquals(expectedDigest, actualDigest)

        // Verify state sequence: QUEUED -> CONNECTING -> DOWNLOADING
        assertEquals(2, repo.transitions.size)
        assertEquals(DownloadState.CONNECTING, repo.transitions[0].second)
        assertEquals(DownloadState.DOWNLOADING, repo.transitions[1].second)

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
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/bounded.bin").toString(),
                    fileName = "bounded.bin",
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
        assertEquals(payload.size.toLong(), tempFile.length())
        assertArrayEquals(payload, tempFile.readBytes())
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
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/seq.bin").toString(),
                    fileName = "seq.bin",
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

        // Check transitions
        assertEquals(2, repo.transitions.size)
        assertEquals(DownloadState.CONNECTING, repo.transitions[0].second)
        assertEquals(DownloadState.DOWNLOADING, repo.transitions[1].second)

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
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/missing.bin").toString(),
                    fileName = "missing.bin",
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

        // Temp file must be empty or absent
        assertTrue(!tempFile.exists() || tempFile.length() == 0L)
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
        val repo = FakeDownloadRepository(
            listOf(
                Download(
                    id = downloadId,
                    url = server.url("/cancel.bin").toString(),
                    fileName = "cancel.bin",
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
        transferJob.cancel()

        // Expect CancellationException rethrown
        assertThrows(CancellationException::class.java) {
            runBlocking {
                transferJob.await()
            }
        }

        // Check temp file was preserved and is non-empty partial
        assertTrue(tempFile.exists())
        assertTrue("Expected partial bytes preserved > 0 but was ${tempFile.length()}", tempFile.length() > 0)
        assertTrue("Expected partial bytes < total but was ${tempFile.length()}", tempFile.length() < payload.size)

        // Resources are closed, so we can freely read or delete tempFile
        assertTrue(tempFile.canRead())
    }
}
