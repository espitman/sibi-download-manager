package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFreshRestartMutation
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadPausePersistenceTest {

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
            val current = get(id) ?: throw IllegalArgumentException("Download not found: $id")
            require(current.state == DownloadState.DOWNLOADING)
            require(downloadedBytes >= current.downloadedBytes)
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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "sdm_pause_tests_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun userPausePersistsExactPartialLengthPreservesPartAndNeverFails() = runBlocking {
        val payload = ByteArray(512 * 1024) { 0x55 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
                .throttleBody(8 * 1024, 50, TimeUnit.MILLISECONDS),
        )
        val downloadId = "pause-stream"
        val clock = FakeClock(2_000L)
        val destFile = File(tempDir, "dest_pause.bin")
        val repo = FakeDownloadRepository(
            listOf(
                queued(
                    id = downloadId,
                    url = server.url("/pause.bin").toString(),
                    destinationPath = destFile.absolutePath,
                    totalBytes = payload.size.toLong(),
                ),
            ),
        )
        val tempFile = File(tempDir, "pause.part")
        val pauseRequested = AtomicBoolean(false)
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
                url = server.url("/pause.bin").toString(),
                tempFile = tempFile,
                repository = repo,
                pauseRequested = { pauseRequested.get() },
            )
        }
        withTimeout(5_000L) {
            while (!tempFile.exists() || tempFile.length() <= 0L) delay(10)
        }
        pauseRequested.set(true)
        val cancelStart = System.currentTimeMillis()
        transferJob.cancel()
        assertThrows(CancellationException::class.java) {
            runBlocking { withTimeout(2_000L) { transferJob.await() } }
        }
        val cancelDuration = System.currentTimeMillis() - cancelStart
        assertTrue("Pause should finish promptly, took $cancelDuration ms", cancelDuration < 2_000L)

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0L)
        assertTrue(tempFile.length() < payload.size)
        assertFalse(destFile.exists())

        val paused = repo.get(downloadId)!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(tempFile.length(), paused.downloadedBytes)
        assertNull(paused.error)
        assertEquals(2_000L, paused.updatedAtEpochMillis)
        assertFalse(repo.transitions.any { it.second == DownloadState.FAILED })
        assertFalse(repo.transitions.any { it.second == DownloadState.COMPLETED })
        assertEquals(DownloadState.PAUSED, repo.transitions.last().second)
    }

    @Test
    fun persistPausedOffsetIsIdempotentAndIgnoresNonActiveStates() = runBlocking {
        val clock = FakeClock(5_000L)
        val engine = DownloadTransferEngine(clock = clock)
        val tempFile = File(tempDir, "idle.part")
        tempFile.writeBytes(ByteArray(128))

        val paused = queued(id = "already-paused", destinationPath = File(tempDir, "p.bin").absolutePath)
            .let { DownloadStateMachine.transition(it, DownloadState.CONNECTING, 1_000L) }
            .let { DownloadStateMachine.transition(it, DownloadState.DOWNLOADING, 1_000L) }
            .let { DownloadStateMachine.transition(it, DownloadState.PAUSED, 1_000L) }
        val completed = queued(id = "completed", destinationPath = File(tempDir, "c.bin").absolutePath)
            .copy(downloadedBytes = 10, totalBytes = 10, state = DownloadState.COMPLETED, completedAtEpochMillis = 1_000L)
        val failed = queued(id = "failed", destinationPath = File(tempDir, "f.bin").absolutePath)
            .copy(state = DownloadState.FAILED, error = "boom")
        val cancelled = queued(id = "cancelled", destinationPath = File(tempDir, "x.bin").absolutePath)
            .let { DownloadStateMachine.transition(it, DownloadState.CANCELLED, 1_000L) }
        val repo = FakeDownloadRepository(listOf(paused, completed, failed, cancelled))

        engine.persistPausedOffset("missing", tempFile, repo)
        engine.persistPausedOffset("already-paused", tempFile, repo)
        engine.persistPausedOffset("already-paused", tempFile, repo)
        engine.persistPausedOffset("completed", tempFile, repo)
        engine.persistPausedOffset("failed", tempFile, repo)
        engine.persistPausedOffset("cancelled", tempFile, repo)

        assertTrue(repo.transitions.isEmpty())
        assertEquals(DownloadState.PAUSED, repo.get("already-paused")!!.state)
        assertEquals(0L, repo.get("already-paused")!!.downloadedBytes)
        assertEquals(DownloadState.COMPLETED, repo.get("completed")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("failed")!!.state)
        assertEquals(DownloadState.CANCELLED, repo.get("cancelled")!!.state)
        assertTrue(tempFile.exists())
        assertEquals(128L, tempFile.length())
    }

    @Test
    fun persistPausedOffsetUsesExactFileLengthBoundedByTotalBytes() = runBlocking {
        val clock = FakeClock(500L)
        val engine = DownloadTransferEngine(clock = clock)
        val tempFile = File(tempDir, "bounded.part")
        tempFile.writeBytes(ByteArray(8_000))
        val download = queued(
            id = "active",
            destinationPath = File(tempDir, "bounded.bin").absolutePath,
            totalBytes = 4_096L,
        ).let { DownloadStateMachine.transition(it, DownloadState.CONNECTING, 1_000L) }
            .let { DownloadStateMachine.transition(it, DownloadState.DOWNLOADING, 1_000L) }
            .copy(downloadedBytes = 1_024L, updatedAtEpochMillis = 1_000L)
        val repo = FakeDownloadRepository(listOf(download))

        engine.persistPausedOffset("active", tempFile, repo)

        val paused = repo.get("active")!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(4_096L, paused.downloadedBytes)
        assertEquals(1_000L, paused.updatedAtEpochMillis)
        assertNull(paused.error)
        assertTrue(tempFile.exists())
        assertEquals(8_000L, tempFile.length())
        assertEquals(listOf(DownloadState.PAUSED), repo.transitions.map { it.second })
    }

    @Test
    fun persistPausedOffsetFromConnectingLeavesEmptyPartAndDoesNotFail() = runBlocking {
        val clock = FakeClock(3_000L)
        val engine = DownloadTransferEngine(clock = clock)
        val tempFile = File(tempDir, "connecting.part")
        assertTrue(tempFile.createNewFile())
        val download = queued(
            id = "connecting",
            destinationPath = File(tempDir, "connecting.bin").absolutePath,
        ).let { DownloadStateMachine.transition(it, DownloadState.CONNECTING, 1_000L) }
        val repo = FakeDownloadRepository(listOf(download))

        engine.persistPausedOffset("connecting", tempFile, repo)

        val paused = repo.get("connecting")!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(0L, paused.downloadedBytes)
        assertNull(paused.error)
        assertTrue(tempFile.exists())
        assertEquals(0L, tempFile.length())
        assertFalse(repo.transitions.any { it.second == DownloadState.FAILED })
    }

    @Test
    fun persistPausedOffsetRewindsStaleProgressToMatchPartFile() = runBlocking {
        val clock = FakeClock(4_000L)
        val engine = DownloadTransferEngine(clock = clock)
        val tempFile = File(tempDir, "stale.part")
        tempFile.writeBytes(ByteArray(3_000))
        val download = queued(
            id = "stale",
            destinationPath = File(tempDir, "stale.bin").absolutePath,
            totalBytes = 10_000L,
        ).let { DownloadStateMachine.transition(it, DownloadState.CONNECTING, 1_000L) }
            .let { DownloadStateMachine.transition(it, DownloadState.DOWNLOADING, 1_000L) }
            .copy(downloadedBytes = 5_000L, updatedAtEpochMillis = 1_000L)
        val repo = FakeDownloadRepository(listOf(download))

        engine.persistPausedOffset("stale", tempFile, repo)

        val paused = repo.get("stale")!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(tempFile.length(), paused.downloadedBytes)
        assertEquals(3_000L, paused.downloadedBytes)
        assertEquals(4_000L, paused.updatedAtEpochMillis)
        assertNull(paused.error)
        assertTrue(tempFile.exists())
        assertEquals(listOf(DownloadState.PAUSED), repo.transitions.map { it.second })
    }

    private fun queued(
        id: String,
        url: String = "https://example.test/$id",
        destinationPath: String,
        totalBytes: Long? = null,
    ) = Download(
        id = id,
        url = url,
        fileName = "$id.bin",
        destinationPath = destinationPath,
        totalBytes = totalBytes,
        state = DownloadState.QUEUED,
        createdAtEpochMillis = 1_000L,
    )
}
