package com.espitman.sdm.download

import com.espitman.sdm.domain.*
import com.espitman.sdm.network.DownloadMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RefreshedLinkTransferTest {
    @Test fun refreshedUrlResumesActualPartialBytesWithoutCorruption() = runBlocking {
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("sdm-refresh-transfer").toFile()
        try {
            val bytes = ByteArray(100) { it.toByte() }
            val destination = java.io.File(directory, "file.bin")
            val part = DownloadPartFile.forDestination(destination); part.writeBytes(bytes.copyOfRange(0, 40))
            val original = Download(id = "refresh", url = server.url("/expired").toString(), fileName = "file.bin",
                destinationPath = destination.path, totalBytes = 100, downloadedBytes = 40, state = DownloadState.FAILED,
                error = "HTTP 410", etag = "\"same\"", createdAtEpochMillis = 1)
            val metadata = DownloadMetadata(url = server.url("/fresh?token=2").toString(), contentLength = 100,
                etag = "\"same\"", acceptsRanges = true)
            val replacement = DownloadLinkRefresh.replacement(original, metadata, false, 2)
                .copy(state = DownloadState.QUEUED)
            val repository = DownloadTransferEngineTest.FakeDownloadRepository(listOf(replacement))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 40-99/100")
                .setHeader("ETag", "\"same\"").setBody(Buffer().write(bytes.copyOfRange(40, 100))))
            DownloadTransferEngine().executeTransfer(replacement.id, replacement.url, part, repository)
            val request = server.takeRequest()
            assertEquals("bytes=40-", request.getHeader("Range")); assertEquals("\"same\"", request.getHeader("If-Range"))
            assertEquals(DownloadState.COMPLETED, repository.get(replacement.id)?.state)
            assertArrayEquals(bytes, destination.readBytes())
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
