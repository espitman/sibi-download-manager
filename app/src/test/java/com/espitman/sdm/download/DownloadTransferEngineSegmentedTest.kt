package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadTransferEngineSegmentedTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val payload = ByteArray((1024 * 1024) + 17) { (it % 251).toByte() }
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

    private fun engine() = DownloadTransferEngine(
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.IO,
        progressUpdateIntervalBytes = 64 * 1024L,
    )
}
