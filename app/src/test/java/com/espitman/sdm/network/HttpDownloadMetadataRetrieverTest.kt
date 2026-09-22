package com.espitman.sdm.network

import com.espitman.sdm.domain.DownloadUrlError
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HttpDownloadMetadataRetrieverTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun normalHeadReturnsCompleteMetadataWithoutGet() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1048576")
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Disposition", "attachment; filename=\"archive.zip\"")
                .setHeader("ETag", "\"tag-12345\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2025 07:28:00 GMT")
                .setHeader("Accept-Ranges", "bytes")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/archive.zip").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(url, metadata?.url)
        assertEquals(1048576L, metadata?.contentLength)
        assertEquals("application/zip", metadata?.contentType)
        assertEquals("attachment; filename=\"archive.zip\"", metadata?.contentDisposition)
        assertEquals("\"tag-12345\"", metadata?.etag)
        assertEquals("Wed, 21 Oct 2025 07:28:00 GMT", metadata?.lastModified)
        assertTrue(metadata?.acceptsRanges == true)
        assertEquals(200, metadata?.statusCode)
        assertNull(metadata?.referenceSha256)

        assertEquals(1, server.requestCount)
        val recordedRequest = server.takeRequest()
        assertEquals("HEAD", recordedRequest.method)
        assertEquals("/archive.zip", recordedRequest.path)
        assertEquals("identity", recordedRequest.getHeader("Accept-Encoding"))
        assertNull("HEAD request must not include Range", recordedRequest.getHeader("Range"))
    }

    @Test
    fun redirectsFollowHopChainAndPreserveFinalUrl() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "/step2")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/final-file.iso")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "5242880")
                .setHeader("Content-Type", "application/x-iso9660-image")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val initialUrl = server.url("/start").toString()
        val result = retriever.retrieve(initialUrl)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(server.url("/final-file.iso").toString(), metadata?.url)
        assertEquals(5242880L, metadata?.contentLength)
        assertEquals("application/x-iso9660-image", metadata?.contentType)
        assertEquals(200, metadata?.statusCode)

        assertEquals(3, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun browserCredentialsAreAppliedToOriginAndStrippedFromCrossOriginRedirect() = runBlocking {
        val unrelated = MockWebServer()
        unrelated.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", unrelated.url("/protected.bin")),
            )
            unrelated.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "64"))
            val initialUrl = server.url("/start").toString()
            val context = ScopedRequestContext(
                originUrl = initialUrl,
                cookie = "session=secret",
                userAgent = "SDM Browser",
                referer = server.url("/page").toString(),
            )

            val result = HttpDownloadMetadataRetriever().retrieve(initialUrl, context)
            assertTrue(result.isSuccess)

            val originRequest = server.takeRequest()
            assertEquals("session=secret", originRequest.getHeader("Cookie"))
            assertEquals("SDM Browser", originRequest.getHeader("User-Agent"))
            val redirectedRequest = unrelated.takeRequest()
            assertNull(redirectedRequest.getHeader("Cookie"))
            assertNull(redirectedRequest.getHeader("Referer"))
            assertEquals("SDM Browser", redirectedRequest.getHeader("User-Agent"))
        } finally {
            unrelated.shutdown()
        }
    }

    @Test
    fun redirect303SwitchesMethodToGetAndSendsMinimalRange() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(303)
                .setHeader("Location", "/see-other")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "2048")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/start").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)
        assertNull(headReq.getHeader("Range"))

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun head405MethodNotAllowedFallsBackToMinimalGet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "8192")
                .setHeader("Content-Type", "application/pdf")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/document.pdf").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(8192L, metadata?.contentLength)
        assertEquals("application/pdf", metadata?.contentType)
        assertEquals(200, metadata?.statusCode)

        assertEquals(2, server.requestCount)
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)
        assertNull(headReq.getHeader("Range"))

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun head501NotImplementedFallsBackToMinimalGet() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(501))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "4096")
                .setHeader("Content-Type", "image/png")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/image.png").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(4096L, metadata?.contentLength)
        assertEquals("image/png", metadata?.contentType)
        assertEquals(200, metadata?.statusCode)

        assertEquals(2, server.requestCount)
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)
        assertNull(headReq.getHeader("Range"))

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun headMissingContentLengthFallsBackToGet() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/zip")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "65536")
                .setHeader("Content-Type", "application/zip")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/archive.zip").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(65536L, metadata?.contentLength)
        assertEquals("application/zip", metadata?.contentType)

        assertEquals(2, server.requestCount)
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)
        assertNull(headReq.getHeader("Range"))

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun missingContentLengthOnBothHeadAndGetYieldsNullLength() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: stream\n\n", 1024)
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/stream").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertNull(metadata?.contentLength)
        assertEquals("text/event-stream", metadata?.contentType)
        assertEquals(200, metadata?.statusCode)

        assertEquals(2, server.requestCount)
        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun fallbackGetParses206ContentRangeTotal() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/123456789")
                .setHeader("Content-Length", "1")
                .setHeader("Content-Type", "video/mp4")
                .setBody("X")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/video.mp4").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(123456789L, metadata?.contentLength)
        assertEquals("video/mp4", metadata?.contentType)
        assertTrue(metadata?.acceptsRanges == true)
        assertEquals(206, metadata?.statusCode)

        assertEquals(2, server.requestCount)
        server.takeRequest()
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun fallbackGetHandlesServerIgnoringRangeWith200() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "65536")
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("ETag", "\"full-file-etag\"")
                .setBody(Buffer().write(ByteArray(65536) { 0x31 }))
        )

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/full.bin").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(65536L, metadata?.contentLength)
        assertEquals("application/octet-stream", metadata?.contentType)
        assertEquals("\"full-file-etag\"", metadata?.etag)
        assertEquals(200, metadata?.statusCode)

        assertEquals(2, server.requestCount)
        server.takeRequest()
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("bytes=0-0", getReq.getHeader("Range"))
        assertEquals("identity", getReq.getHeader("Accept-Encoding"))
    }

    @Test
    fun httpErrorsArePreservedAsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/missing").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.HttpError
        assertNotNull(error)
        assertEquals(404, error?.statusCode)
        assertEquals(url, error?.url)
        assertTrue(error?.message?.contains("404") == true)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun http500InternalServerErrorReportsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/server-error").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.HttpError
        assertNotNull(error)
        assertEquals(500, error?.statusCode)
    }

    @Test
    fun http403ForbiddenReportsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val retriever = HttpDownloadMetadataRetriever()
        val url = server.url("/forbidden").toString()
        val result = retriever.retrieve(url)

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.HttpError
        assertNotNull(error)
        assertEquals(403, error?.statusCode)
    }

    @Test
    fun redirectCycleIsDetectedAndBounded() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/loop-b")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/loop-a")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val startUrl = server.url("/loop-a").toString()
        val result = retriever.retrieve(startUrl)

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.RedirectError
        assertNotNull(error)
        assertTrue(error?.message?.contains("cycle", ignoreCase = true) == true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun redirectLimitIsEnforced() = runBlocking {
        val retriever = HttpDownloadMetadataRetriever(maxRedirects = 2)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r1"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r2"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r3"))

        val result = retriever.retrieve(server.url("/r0").toString())

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.RedirectError
        assertNotNull(error)
        assertTrue(error?.message?.contains("Too many redirects") == true)
        assertEquals(3, error?.redirectCount)
    }

    @Test
    fun redirectPreservesGetSemanticsAndRangeAcrossHops() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405)) // HEAD rejected
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/final-get")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1024")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/start-get").toString())

        assertTrue(result.isSuccess)
        assertEquals(3, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)

        val hop1 = server.takeRequest()
        assertEquals("GET", hop1.method)
        assertEquals("bytes=0-0", hop1.getHeader("Range"))
        assertEquals("identity", hop1.getHeader("Accept-Encoding"))

        val hop2 = server.takeRequest()
        assertEquals("GET", hop2.method)
        assertEquals("bytes=0-0", hop2.getHeader("Range"))
        assertEquals("identity", hop2.getHeader("Accept-Encoding"))
    }

    @Test
    fun redirectWithoutLocationHeaderFailsCleanly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302))

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/no-location").toString())

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.RedirectError
        assertNotNull(error)
        assertTrue(error?.message?.contains("missing Location header") == true)
    }

    @Test
    fun redirectToUnsupportedSchemeIsRejected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "ftp://files.example.com/file.zip")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/ftp-redirect").toString())

        assertTrue(result.isFailure)
        val error = result.failureOrNull() as? DownloadMetadataResult.Failure.RedirectError
        assertNotNull(error)
        assertTrue(error?.message?.contains("unsupported", ignoreCase = true) == true)
    }

    @Test
    fun invalidUrlIsRejectedWithoutNetworkCalls() = runBlocking {
        val retriever = HttpDownloadMetadataRetriever()

        val emptyResult = retriever.retrieve("   ")
        assertTrue(emptyResult.isFailure)
        val emptyError = emptyResult.failureOrNull() as? DownloadMetadataResult.Failure.InvalidUrl
        assertEquals(DownloadUrlError.EMPTY, emptyError?.urlError)

        val ftpResult = retriever.retrieve("ftp://example.com/file.zip")
        assertTrue(ftpResult.isFailure)
        val ftpError = ftpResult.failureOrNull() as? DownloadMetadataResult.Failure.InvalidUrl
        assertEquals(DownloadUrlError.UNSUPPORTED_SCHEME, ftpError?.urlError)

        val userResult = retriever.retrieve("https://user:pass@example.com/file.zip")
        assertTrue(userResult.isFailure)
        val userError = userResult.failureOrNull() as? DownloadMetadataResult.Failure.InvalidUrl
        assertEquals(DownloadUrlError.CREDENTIALS_NOT_ALLOWED, userError?.urlError)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun validatesMaxRedirectsIsNonnegative() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpDownloadMetadataRetriever(maxRedirects = -1)
        }
        // maxRedirects = 0 is valid and halts on first redirect
        val zeroRedirectRetriever = HttpDownloadMetadataRetriever(maxRedirects = 0)
        assertNotNull(zeroRedirectRetriever)
    }

    @Test
    fun resourceClosureClosesEveryCallWithoutApplicationReadingBody() = runBlocking {
        val callEndCount = AtomicInteger(0)
        val applicationBytesRead = AtomicLong(0)

        val testClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callEnd(call: Call) {
                    callEndCount.incrementAndGet()
                }
            })
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                if (body != null) {
                    val countingSource = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val read = super.read(sink, byteCount)
                            if (read > 0) applicationBytesRead.addAndGet(read)
                            return read
                        }
                    }
                    response.newBuilder()
                        .body(countingSource.buffer().asResponseBody(body.contentType(), body.contentLength()))
                        .build()
                } else {
                    response
                }
            }
            .build()

        val largePayload = Buffer().write(ByteArray(512 * 1024) { 0x42 })
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "524288")
                .setBody(largePayload)
        )

        val retriever = HttpDownloadMetadataRetriever(okHttpClient = testClient)
        val result = retriever.retrieve(server.url("/large-file.bin").toString())

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
        assertEquals("Both HEAD and GET calls must trigger callEnd", 2, callEndCount.get())
        assertEquals("Metadata retrieval must not read response body bytes", 0L, applicationBytesRead.get())
    }

    @Test
    fun parsesContentRangeOnDirect206PartialContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/987654321")
                .setHeader("Content-Type", "video/mp4")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/video.mp4").toString())

        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(987654321L, metadata?.contentLength)
        assertEquals("video/mp4", metadata?.contentType)
        assertTrue(metadata?.acceptsRanges == true)
    }

    @Test
    fun headReturnsNormalizedXChecksumWithoutGet() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1048576")
                .setHeader("X-Checksum-Sha256", EMPTY_SHA256_HEX.uppercase())
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/archive.zip").toString())

        assertTrue(result.isSuccess)
        assertEquals(EMPTY_SHA256_HEX, result.getOrNull()?.referenceSha256)
        assertEquals(1, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun fallbackGetChecksumPreferredOverHeadFallback() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("X-Checksum-Sha256", ZERO_SHA256_HEX)
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "16")
                .setHeader("Digest", "sha-256=$EMPTY_SHA256_BASE64")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/prefer-get.bin").toString())

        assertTrue(result.isSuccess)
        assertEquals(EMPTY_SHA256_HEX, result.getOrNull()?.referenceSha256)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fallbackMergesHeadChecksumWhenGetOmitsIt() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/zip")
                .setHeader("Content-Digest", "sha-256=:$EMPTY_SHA256_BASE64:")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "65536")
                .setHeader("Content-Type", "application/zip")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/archive.zip").toString())

        assertTrue(result.isSuccess)
        assertEquals(EMPTY_SHA256_HEX, result.getOrNull()?.referenceSha256)
        assertEquals(65536L, result.getOrNull()?.contentLength)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedAndNonSha256ChecksumHeadersStayUnavailable() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "8")
                .setHeader("X-Checksum-Sha256", "not-a-sha256")
                .setHeader("Digest", "sha-512=$EMPTY_SHA256_BASE64")
        )

        val retriever = HttpDownloadMetadataRetriever()
        val result = retriever.retrieve(server.url("/no-checksum.bin").toString())

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull()?.referenceSha256)
    }

    companion object {
        private const val EMPTY_SHA256_HEX =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private const val EMPTY_SHA256_BASE64 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
        private const val ZERO_SHA256_HEX =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
