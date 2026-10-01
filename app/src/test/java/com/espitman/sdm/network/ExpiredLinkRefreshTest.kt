package com.espitman.sdm.network

import com.espitman.sdm.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExpiredLinkRefreshTest {
    @Test fun expiredSignedUrlAndRedirectedReplacementValidateIdentity() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val retriever = HttpDownloadMetadataRetriever()
            server.enqueue(MockResponse().setResponseCode(410))
            assertTrue(retriever.retrieve(server.url("/file?expired=1").toString()) is DownloadMetadataResult.Failure)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/new-file"))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "100")
                .setHeader("ETag", "\"same\"").setHeader("Accept-Ranges", "bytes"))
            val metadata = (retriever.retrieve(server.url("/file?fresh=1").toString()) as DownloadMetadataResult.Success).metadata
            val old = Download(url = server.url("/file?expired=1").toString(), fileName = "file.bin", totalBytes = 100,
                downloadedBytes = 40, etag = "\"same\"", state = DownloadState.FAILED, error = "HTTP 410", createdAtEpochMillis = 1)
            assertTrue(DownloadLinkRefresh.canPreserve(old, metadata))
            assertEquals(40L, DownloadLinkRefresh.replacement(old, metadata, false, 2).downloadedBytes)
        } finally { server.shutdown() }
    }
}
