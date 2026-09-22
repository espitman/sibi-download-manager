package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DownloadTransferEngineSpeedLimitTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "sdm_speed_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun throttleHappensBeforeWrite() = runBlocking {
        val payload = ByteArray(32 * 1024) { 0x21 }
        enqueue(payload)
        val gate = CompletableDeferred<Unit>()
        val acquired = AtomicInteger(0)
        val limiter = SpeedLimiter { byteCount ->
            acquired.addAndGet(byteCount)
            gate.await()
            byteCount
        }
        val dest = File(tempDir, "gated.bin")
        val part = File(tempDir, "gated.part")
        val repo = ContractDownloadRepository(
            listOf(record("gated", server.url("/gated.bin").toString(), dest.absolutePath, payload.size.toLong())),
        )
        val job = async(Dispatchers.IO) {
            engine(limiter).executeTransfer("gated", server.url("/gated.bin").toString(), part, repo)
        }
        withTimeout(5_000) {
            while (acquired.get() == 0) delay(5)
        }
        delay(40)
        assertEquals(0L, if (part.exists()) part.length() else 0L)
        assertFalse(dest.exists())
        gate.complete(Unit)
        withTimeout(5_000) { job.await() }
        assertEquals(payload.size.toLong(), dest.length())
        assertEquals(DownloadState.COMPLETED, repo.get("gated")!!.state)
    }

    @Test
    fun cancelDuringThrottleKeepsExactOffset() = runBlocking {
        val payload = ByteArray(64 * 1024) { 0x22 }
        enqueue(payload)
        val waiting = CompletableDeferred<Unit>()
        val limiter = SpeedLimiter { _ ->
            waiting.complete(Unit)
            delay(60_000)
            1
        }
        val dest = File(tempDir, "paused.bin")
        val part = File(tempDir, "paused.part")
        val repo = ContractDownloadRepository(
            listOf(record("paused", server.url("/paused.bin").toString(), dest.absolutePath, payload.size.toLong())),
        )
        val job = async(Dispatchers.IO) {
            engine(limiter).executeTransfer(
                downloadId = "paused",
                url = server.url("/paused.bin").toString(),
                tempFile = part,
                repository = repo,
                pauseRequested = { true },
            )
        }
        withTimeout(5_000) { waiting.await() }
        job.cancel()
        assertThrows(CancellationException::class.java) {
            runBlocking { withTimeout(2_000) { job.await() } }
        }
        assertFalse(dest.exists())
        val offset = if (part.exists()) part.length() else 0L
        val paused = repo.get("paused")!!
        assertEquals(DownloadState.PAUSED, paused.state)
        assertEquals(offset, paused.downloadedBytes)
    }

    @Test
    fun limitedThroughputStaysNearAggregateCapAndUnlimitedIsFaster() = runBlocking {
        val payload = ByteArray(MEASURED_BYTES) { (it % 251).toByte() }
        val limited = measureTransfer(
            id = "limited",
            payload = payload,
            limiter = realLimiter(bytesPerSecond = CAP_BYTES_PER_SECOND),
        )
        val unlimited = measureTransfer(
            id = "unlimited",
            payload = payload,
            limiter = SpeedLimiter.Unlimited,
        )
        val limitedRate = limited.bytes.toDouble() / limited.elapsedMs.coerceAtLeast(1L) * 1_000.0
        val unlimitedRate = unlimited.bytes.toDouble() / unlimited.elapsedMs.coerceAtLeast(1L) * 1_000.0
        val cap = CAP_BYTES_PER_SECOND.toDouble()
        assertEquals(MEASURED_BYTES.toLong(), limited.bytes)
        assertEquals(MEASURED_BYTES.toLong(), unlimited.bytes)
        assertTrue(
            "limitedRate=$limitedRate cap=$cap elapsed=${limited.elapsedMs}",
            limitedRate <= cap * MAX_RATE_MULTIPLE,
        )
        assertTrue(
            "limited should finish in a bounded time elapsed=${limited.elapsedMs}",
            limited.elapsedMs < 2_000L,
        )
        assertTrue(
            "unlimitedRate=$unlimitedRate limitedRate=$limitedRate unlimitedMs=${unlimited.elapsedMs} limitedMs=${limited.elapsedMs}",
            unlimited.elapsedMs * 3L <= limited.elapsedMs || unlimitedRate > limitedRate * 3.0,
        )
    }

    @Test
    fun concurrentTransfersShareOneLimiter() = runBlocking {
        val firstPayload = ByteArray(CONCURRENT_BYTES) { 0x31 }
        val secondPayload = ByteArray(CONCURRENT_BYTES) { 0x32 }
        enqueue(firstPayload)
        enqueue(secondPayload)
        val limiter = realLimiter(bytesPerSecond = CAP_BYTES_PER_SECOND)
        val engine = engine(limiter)
        val firstDest = File(tempDir, "agg-a.bin")
        val secondDest = File(tempDir, "agg-b.bin")
        val firstPart = File(tempDir, "agg-a.part")
        val secondPart = File(tempDir, "agg-b.part")
        val repo = ContractDownloadRepository(
            listOf(
                record("agg-a", server.url("/a.bin").toString(), firstDest.absolutePath, firstPayload.size.toLong()),
                record("agg-b", server.url("/b.bin").toString(), secondDest.absolutePath, secondPayload.size.toLong()),
            ),
        )
        val started = System.nanoTime()
        val first = async(Dispatchers.IO) {
            engine.executeTransfer("agg-a", server.url("/a.bin").toString(), firstPart, repo)
        }
        val second = async(Dispatchers.IO) {
            engine.executeTransfer("agg-b", server.url("/b.bin").toString(), secondPart, repo)
        }
        withTimeout(8_000) {
            first.await()
            second.await()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        val combined = firstDest.length() + secondDest.length()
        val rate = combined.toDouble() / elapsedMs.coerceAtLeast(1L) * 1_000.0
        val cap = CAP_BYTES_PER_SECOND.toDouble()
        assertEquals((CONCURRENT_BYTES * 2).toLong(), combined)
        assertEquals(DownloadState.COMPLETED, repo.get("agg-a")!!.state)
        assertEquals(DownloadState.COMPLETED, repo.get("agg-b")!!.state)
        assertTrue(
            "aggregateRate=$rate cap=$cap combined=$combined elapsed=$elapsedMs",
            rate <= cap * MAX_RATE_MULTIPLE,
        )
        assertTrue("aggregate elapsed=$elapsedMs", elapsedMs < 2_000L)
    }

    private suspend fun measureTransfer(
        id: String,
        payload: ByteArray,
        limiter: SpeedLimiter,
    ): Measured {
        enqueue(payload)
        val dest = File(tempDir, "$id.bin")
        val part = File(tempDir, "$id.part")
        val repo = ContractDownloadRepository(
            listOf(record(id, server.url("/$id.bin").toString(), dest.absolutePath, payload.size.toLong())),
        )
        val started = System.nanoTime()
        engine(limiter).executeTransfer(id, server.url("/$id.bin").toString(), part, repo)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(DownloadState.COMPLETED, repo.get(id)!!.state)
        return Measured(dest.length(), elapsedMs)
    }

    private fun enqueue(payload: ByteArray) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload)),
        )
    }

    private fun engine(limiter: SpeedLimiter) = DownloadTransferEngine(
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.IO,
        bufferSizeBytes = 8 * 1024,
        progressUpdateIntervalBytes = 8 * 1024L,
        speedLimiter = limiter,
    )

    private fun realLimiter(bytesPerSecond: Long) = AggregateSpeedLimiter(
        effectiveBytesPerSecond = { bytesPerSecond },
        maxBurstBytes = 8 * 1024L,
        maxWaitSliceMillis = 10L,
    )

    private fun record(id: String, url: String, destinationPath: String, totalBytes: Long) = Download(
        id = id,
        url = url,
        fileName = "$id.bin",
        destinationPath = destinationPath,
        totalBytes = totalBytes,
        state = DownloadState.QUEUED,
        createdAtEpochMillis = 1_000L,
    )

    private data class Measured(val bytes: Long, val elapsedMs: Long)

    companion object {
        private const val CAP_BYTES_PER_SECOND = 2_000_000L
        private const val MEASURED_BYTES = 800 * 1024
        private const val CONCURRENT_BYTES = 400 * 1024
        private const val MAX_RATE_MULTIPLE = 1.35
    }
}
