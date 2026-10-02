package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.StorageCapacity
import com.espitman.sdm.storage.StorageCapacityProbe
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadTransferEngineSegmentedTest {
    @Test
    fun offlineRequestFailureStaysActiveInsteadOfBecomingError() = runBlocking {
        val (repo, _, part) = fixture("offline-segment")
        server.shutdown()
        try {
            engine(networkUnavailable = { true }).executeTransfer(
                "offline-segment", server.url("/file").toString(), part, repo,
            )
            org.junit.Assert.fail("Expected an offline retry signal")
        } catch (_: OfflineTransferRetryException) {
            assertEquals(DownloadState.DOWNLOADING, repo.get("offline-segment")?.state)
        }
    }

    @Test
    fun manualPauseCancelsSegmentRequestsStalledWithoutAResponse() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }
        val (repo, destination, part) = fixture("segment-stall")
        val pauseRequested = AtomicBoolean(false)
        val transfer = async(Dispatchers.IO) {
            engine().executeTransfer("segment-stall", server.url("/file").toString(), part, repo,
                pauseRequested = pauseRequested::get)
        }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        pauseRequested.set(true)
        withTimeout(2_000L) {
            transfer.cancel()
            transfer.join()
        }
        assertEquals(DownloadState.PAUSED, repo.get("segment-stall")?.state)
        assertFalse(destination.exists())
    }

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private var payload = ByteArray((1024 * 1024) + 17) { (it % 251).toByte() }
    private val requests = CopyOnWriteArrayList<String?>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = createTempDir(prefix = "sdm-segmented-")
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun validRangesMergeByteForByteAndComplete() = runBlocking {
        server.dispatcher = rangeDispatcher(ignoreRanges = false)
        val (repo, destination, part) = fixture("success")

        engine().executeTransfer("success", server.url("/file").toString(), part, repo)

        assertEquals(DownloadState.COMPLETED, repo.get("success")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(
            setOf("bytes=0-524296", "bytes=524297-1048592"),
            requests.filterNotNull().toSet(),
        )
        assertFalse(part.exists())
        assertTrue(directory.listFiles().orEmpty().none { ".segment-" in it.name })
    }

    @Test
    fun existingSingleStreamPrefixIsReusedAcrossParallelRanges() = runBlocking {
        server.dispatcher = rangeDispatcher(ignoreRanges = false)
        val (repo, destination, part) = fixture("converted")
        val prefix = 600_000
        part.writeBytes(payload.copyOfRange(0, prefix))
        engine().executeTransfer("converted", server.url("/file").toString(), part, repo)
        assertEquals(DownloadState.COMPLETED, repo.get("converted")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(setOf("bytes=524296-524296", "bytes=600000-1048592"), requests.toSet())
        assertFalse(FolderSegmentCheckpoint.marker(part).exists())
    }

    @Test
    fun resumedDownloadOpensThirtyTwoStreamsAndRetainsItsPrefix() = runBlocking {
        payload = ByteArray(8 * 1024 * 1024 + 17) { (it % 251).toByte() }
        server.dispatcher = rangeDispatcher(ignoreRanges = false)
        val (repo, destination, part) = fixture("resume-32")
        part.writeBytes(payload.copyOfRange(0, 100))
        val readers = java.util.concurrent.CountDownLatch(32)
        val peak = java.util.concurrent.atomic.AtomicInteger()
        DownloadTransferEngine(
            ioDispatcher = Dispatchers.IO,
            segmentCount = { 32 },
            onChunkRead = {
                peak.accumulateAndGet(HttpConnectionTelemetry.active.value["resume-32"] ?: 0, ::maxOf)
                readers.countDown()
                assertTrue("All configured streams must start", readers.await(5, TimeUnit.SECONDS))
            },
        ).executeTransfer("resume-32", server.url("/file").toString(), part, repo)
        assertEquals(32, peak.get())
        assertEquals(DownloadState.COMPLETED, repo.get("resume-32")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(32, requests.size)
        org.junit.Assert.assertNull(HttpConnectionTelemetry.active.value["resume-32"])
    }

    @Test
    fun convertedPrefixFallsBackSafelyWhenServerRefusesRanges() = runBlocking {
        server.dispatcher = rangeDispatcher(ignoreRanges = true)
        val (repo, destination, part) = fixture("converted-fallback")
        part.writeBytes(payload.copyOfRange(0, 600_000))
        engine().executeTransfer("converted-fallback", server.url("/file").toString(), part, repo)
        assertEquals(DownloadState.COMPLETED, repo.get("converted-fallback")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertTrue(requests.any { it == null })
    }

    @Test
    fun ignoredRangesFallBackToOneFreshGetWithoutCorruptingOutput() = runBlocking {
        server.dispatcher = rangeDispatcher(ignoreRanges = true)
        val (repo, destination, part) = fixture("fallback")

        engine().executeTransfer("fallback", server.url("/file").toString(), part, repo)

        assertEquals(DownloadState.COMPLETED, repo.get("fallback")!!.state)
        assertArrayEquals(payload, destination.readBytes())
        assertTrue(requests.any { it == null })
        assertTrue(requests.any { it != null })
        assertTrue(directory.listFiles().orEmpty().none { ".segment-" in it.name })
    }

    @Test
    fun interruptedContiguousSegmentsRecoverIntoNormalValidatedResume() = runBlocking {
        val (repo, destination, part) = fixture("recover")
        val firstEnd = 262_143
        val secondStart = firstEnd + 1
        File(part.path + ".segment-0-$firstEnd").writeBytes(payload.copyOfRange(0, firstEnd + 1))
        File(part.path + ".segment-$secondStart-524287").writeBytes(
            payload.copyOfRange(secondStart, secondStart + 113),
        )
        val expectedOffset = secondStart + 113
        server.dispatcher = rangeDispatcher(ignoreRanges = false)

        engine().executeTransfer("recover", server.url("/file").toString(), part, repo)

        assertEquals(DownloadState.COMPLETED, repo.get("recover")!!.state)
        assertTrue(requests.contains("bytes=$expectedOffset-524296"))
        assertArrayEquals(payload, destination.readBytes())
        assertTrue(directory.listFiles().orEmpty().none { ".segment-" in it.name })
    }

    @Test
    fun folderCheckpointResumesEverySegmentWithoutDiscardingNonContiguousBytes() = runBlocking {
        server.dispatcher=rangeDispatcher(ignoreRanges=false)
        val (repo,destination,part)=fixture("folder-checkpoint")
        val download=repo.get("folder-checkpoint")!!
        val ranges=SegmentedTransferPolicy.plan(download,0)!!
        val lengths=listOf(17,31)
        ranges.forEachIndexed { i,r -> FolderSegmentCheckpoint.file(part,r).writeBytes(payload.copyOfRange(r.start.toInt(),r.start.toInt()+lengths[i])) }
        FolderSegmentCheckpoint.save(part,ranges,download)
        engine().executeTransfer(download.id,download.url,part,repo)
        assertArrayEquals(payload,destination.readBytes())
        assertEquals(setOf("bytes=17-524296","bytes=524328-1048592"),requests.filterNotNull().toSet())
        assertFalse(FolderSegmentCheckpoint.marker(part).exists())
    }

    @Test
    fun fullySavedFolderCheckpointRevalidatesCompletedSegmentsBeforeMerging() = runBlocking {
        server.dispatcher=rangeDispatcher(ignoreRanges=false)
        val (repo,destination,part)=fixture("folder-complete-segments")
        val download=repo.get("folder-complete-segments")!!
        val ranges=SegmentedTransferPolicy.plan(download,0)!!
        ranges.forEach { r->FolderSegmentCheckpoint.file(part,r).writeBytes(payload.copyOfRange(r.start.toInt(),r.endInclusive.toInt()+1)) }
        FolderSegmentCheckpoint.save(part,ranges,download)
        engine().executeTransfer(download.id,download.url,part,repo)
        assertArrayEquals(payload,destination.readBytes())
        assertEquals(setOf("bytes=524296-524296","bytes=1048592-1048592"),requests.filterNotNull().toSet())
    }

    @Test
    fun segmentedPeakStorageBudgetRejectsBeforeOpeningConnections() = runBlocking {
        val (repo, destination, part) = fixture("space")
        server.dispatcher = rangeDispatcher(ignoreRanges = false)
        val ranges = SegmentedTransferPolicy.plan(repo.get("space")!!, 0)!!
        val required = SegmentedTransferPolicy.requiredLocalBytes(payload.size.toLong(), ranges)
        val probe = object : StorageCapacityProbe {
            override fun queryLocalPath(path: File) = StorageCapacity.from(required, required - 1)
            override fun queryTree(treeUri: String) = StorageCapacity.Unknown
        }

        DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            storageCapacity = probe,
        ).executeTransfer("space", server.url("/file").toString(), part, repo)

        assertEquals(DownloadState.FAILED, repo.get("space")!!.state)
        assertFalse(destination.exists())
        assertEquals(0, server.requestCount)
    }

    private fun rangeDispatcher(ignoreRanges: Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            requests += range
            if (range == null || ignoreRanges) {
                return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(payload.copyOfRange(start, end + 1)))
        }
    }

    private fun fixture(id: String): Triple<DownloadTransferEngineTest.FakeDownloadRepository, File, File> {
        val destination = File(directory, "$id.bin")
        val part = DownloadPartFile.forDestination(destination)
        val repo = DownloadTransferEngineTest.FakeDownloadRepository(
            listOf(
                Download(
                    id = id,
                    url = server.url("/file").toString(),
                    fileName = destination.name,
                    destinationPath = destination.absolutePath,
                    totalBytes = payload.size.toLong(),
                    etag = "\"v1\"",
                    acceptsRanges = true,
                    state = DownloadState.QUEUED,
                    createdAtEpochMillis = 1,
                ),
            ),
        )
        return Triple(repo, destination, part)
    }

    private fun engine(networkUnavailable: () -> Boolean = { false }) = DownloadTransferEngine(
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.IO,
        progressUpdateIntervalBytes = 64 * 1024L,
        networkUnavailable = networkUnavailable,
    )
}
