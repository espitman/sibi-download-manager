package com.espitman.sdm.download

import com.espitman.sdm.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.nio.file.Files

class IndividualSpeedLimitTest {
    @Test fun validatesDecimalByteUnitsAndOverflow() {
        assertEquals(500_000L, IndividualSpeedLimit.parse("500", false))
        assertEquals(1_250_000L, IndividualSpeedLimit.parse("1.25", true))
        listOf("", "0", "-1", "NaN", "999999999999999999999999", "0.00001").forEach {
            assertNull(IndividualSpeedLimit.parse(it, false))
        }
    }
    @Test fun sharesOnlyWithinFileAndWakesOnLiveUnlimitedChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val record = Download(id = "a", url = "https://example.org/a", fileName = "a", createdAtEpochMillis = 1, speedLimitBytesPerSecond = 1)
            val records = MutableStateFlow(listOf(record, record.copy(id = "b", speedLimitBytesPerSecond = null)))
            val limits = IndividualSpeedLimiters(records, scope)
            assertSame(limits.forDownload("a"), limits.forDownload("a"))
            assertNotSame(limits.forDownload("a"), limits.forDownload("b"))
            assertEquals(10000, limits.forDownload("b").acquire(10000))
            val waiting = async { limits.forDownload("a").acquire(10000) }
            delay(50)
            records.value = listOf(record.copy(speedLimitBytesPerSecond = null))
            assertEquals(10000, withTimeout(1000) { waiting.await() })
            // The same bucket is kept across paused/resumed state changes.
            val bucket = limits.forDownload("a")
            records.value = listOf(record.copy(state = DownloadState.PAUSED, speedLimitBytesPerSecond = null))
            assertSame(bucket, limits.forDownload("a"))
        } finally { scope.cancel() }
    }
    @Test fun transferPassesBothLimitersAndPublishesActualWrittenBytes() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("sdm-individual-rate").toFile()
        try {
            val bytes = ByteArray(4096) { it.toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val destination = java.io.File(directory, "file.bin")
            val record = Download(id = "limited", url = server.url("/file").toString(), fileName = "file.bin", destinationPath = destination.path, createdAtEpochMillis = 1)
            val repository = DownloadTransferEngineTest.FakeDownloadRepository(listOf(record))
            val global = AtomicLong(); val individual = AtomicLong()
            val engine = DownloadTransferEngine(
                speedLimiter = SpeedLimiter { n -> minOf(n, 7).also { global.addAndGet(it.toLong()) } },
                individualSpeedLimiter = { id -> assertEquals(record.id, id); SpeedLimiter { n -> minOf(n, 11).also { individual.addAndGet(it.toLong()) } } })
            engine.executeTransfer(record.id, record.url, DownloadPartFile.forDestination(destination), repository)
            assertArrayEquals(bytes, destination.readBytes())
            assertEquals(bytes.size.toLong(), global.get())
            assertTrue(individual.get() >= global.get())
            assertEquals(bytes.size.toLong(), repository.get(record.id)?.downloadedBytes)
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
