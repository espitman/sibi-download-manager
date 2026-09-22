package com.espitman.sdm.network

import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.domain.ErrorReportSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

interface DownloadMetadataRetriever {
    suspend fun retrieve(url: String): DownloadMetadataResult
    suspend fun retrieve(url: String, requestContext: ScopedRequestContext?): DownloadMetadataResult = retrieve(url)
}

class HttpDownloadMetadataRetriever(
    okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) : DownloadMetadataRetriever {

    init {
        require(maxRedirects >= 0) { "maxRedirects cannot be negative: $maxRedirects" }
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun retrieve(url: String): DownloadMetadataResult = retrieve(url, requestContext = null)

    override suspend fun retrieve(
        url: String,
        requestContext: ScopedRequestContext?,
    ): DownloadMetadataResult = withContext(ioDispatcher) {
        val validatedUrl = when (val validation = DownloadUrl.validate(url)) {
            is DownloadUrlResult.Valid -> validation.url
            is DownloadUrlResult.Invalid -> {
                return@withContext DownloadMetadataResult.Failure.InvalidUrl(
                    url = url,
                    urlError = validation.error,
                )
            }
        }

        // Optimization: attempt HEAD request first
        val headStep = executeHopChain(validatedUrl, method = "HEAD", requestContext)
        when (headStep) {
            is HopStep.NetworkFailure -> headStep.failure
            is HopStep.RedirectFailure -> headStep.failure
            is HopStep.Terminal -> {
                if (headStep.statusCode == 405 || headStep.statusCode == 501) {
                    // HEAD explicitly unsupported -> safely fall back to minimal GET
                    executeGetFallback(validatedUrl, fallbackFromHead = headStep, requestContext)
                } else if (headStep.statusCode in 200..299) {
                    if (headStep.contentLength == null || headStep.contentLength <= 0) {
                        // HEAD lacks usable metadata (e.g. missing Content-Length) -> fall back to minimal GET
                        executeGetFallback(validatedUrl, fallbackFromHead = headStep, requestContext)
                    } else {
                        // HEAD succeeded with usable metadata
                        DownloadMetadataResult.Success(headStep.toMetadata())
                    }
                } else {
                    // HTTP error (e.g. 404, 403, 500)
                    DownloadMetadataResult.Failure.HttpError(
                        url = headStep.url,
                        statusCode = headStep.statusCode,
                        statusMessage = headStep.statusMessage,
                    )
                }
            }
        }
    }

    private fun executeGetFallback(
        validatedUrl: String,
        fallbackFromHead: HopStep.Terminal,
        requestContext: ScopedRequestContext?,
    ): DownloadMetadataResult {
        val getStep = executeHopChain(validatedUrl, method = "GET", requestContext)
        return when (getStep) {
            is HopStep.NetworkFailure -> getStep.failure
            is HopStep.RedirectFailure -> getStep.failure
            is HopStep.Terminal -> {
                if (getStep.statusCode in 200..299) {
                    val metadata = DownloadMetadata(
                        url = getStep.url,
                        contentLength = getStep.contentLength ?: fallbackFromHead.contentLength?.takeIf { it > 0 },
                        contentType = getStep.contentType ?: fallbackFromHead.contentType,
                        contentDisposition = getStep.contentDisposition ?: fallbackFromHead.contentDisposition,
                        etag = getStep.etag ?: fallbackFromHead.etag,
                        lastModified = getStep.lastModified ?: fallbackFromHead.lastModified,
                        acceptsRanges = getStep.acceptsRanges || fallbackFromHead.acceptsRanges,
                        statusCode = getStep.statusCode,
                        referenceSha256 = getStep.referenceSha256 ?: fallbackFromHead.referenceSha256,
                    )
                    DownloadMetadataResult.Success(metadata)
                } else {
                    DownloadMetadataResult.Failure.HttpError(
                        url = getStep.url,
                        statusCode = getStep.statusCode,
                        statusMessage = getStep.statusMessage,
                    )
                }
            }
        }
    }

    private fun executeHopChain(
        initialUrl: String,
        method: String,
        requestContext: ScopedRequestContext?,
    ): HopStep {
        var currentUrl = initialUrl
        var currentMethod = method
        var redirectCount = 0
        val visitedUrls = mutableSetOf(initialUrl)

        while (true) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .method(currentMethod, null)
                .header("Accept-Encoding", "identity")
            requestContext?.headersFor(currentUrl)?.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            if (currentMethod == "GET") {
                requestBuilder.header("Range", "bytes=0-0")
            }

            val request = requestBuilder.build()

            val call = client.newCall(request)
            val response: Response = try {
                call.execute()
            } catch (e: IOException) {
                return HopStep.NetworkFailure(
                    DownloadMetadataResult.Failure.NetworkError(
                        url = currentUrl,
                        message = ErrorReportSanitizer.sanitize(e.message)
                            .ifBlank { "Network error connecting" },
                        cause = e,
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return HopStep.NetworkFailure(
                    DownloadMetadataResult.Failure.NetworkError(
                        url = currentUrl,
                        message = ErrorReportSanitizer.sanitize(e.message)
                            .ifBlank { "Unexpected error connecting" },
                        cause = e,
                    )
                )
            }

            response.use { resp ->
                if (resp.isRedirect) {
                    val locationHeader = resp.header("Location")
                    if (locationHeader.isNullOrBlank()) {
                        return HopStep.RedirectFailure(
                            DownloadMetadataResult.Failure.RedirectError(
                                url = currentUrl,
                                redirectCount = redirectCount,
                                message = "Redirect response ${resp.code} missing Location header",
                            )
                        )
                    }

                    val resolvedHttpUrl = resp.request.url.resolve(locationHeader.trim())
                    if (resolvedHttpUrl == null) {
                        return HopStep.RedirectFailure(
                            DownloadMetadataResult.Failure.RedirectError(
                                url = currentUrl,
                                redirectCount = redirectCount,
                                message = "Invalid or unsupported redirect location",
                            )
                        )
                    }

                    val resolvedUrlString = resolvedHttpUrl.toString()
                    when (val valid = DownloadUrl.validate(resolvedUrlString)) {
                        is DownloadUrlResult.Invalid -> {
                            return HopStep.RedirectFailure(
                                DownloadMetadataResult.Failure.RedirectError(
                                    url = currentUrl,
                                    redirectCount = redirectCount,
                                    message = "Redirect to unsupported or invalid URL (${DownloadUrl.errorMessage(valid.error)})",
                                )
                            )
                        }
                        is DownloadUrlResult.Valid -> {
                            redirectCount++
                            if (redirectCount > maxRedirects) {
                                return HopStep.RedirectFailure(
                                    DownloadMetadataResult.Failure.RedirectError(
                                        url = valid.url,
                                        redirectCount = redirectCount,
                                        message = "Too many redirects (limit reached at $redirectCount)",
                                    )
                                )
                            }
                            if (valid.url in visitedUrls) {
                                return HopStep.RedirectFailure(
                                    DownloadMetadataResult.Failure.RedirectError(
                                        url = valid.url,
                                        redirectCount = redirectCount,
                                        message = "Redirect cycle detected",
                                    )
                                )
                            }
                            visitedUrls.add(valid.url)
                            currentUrl = valid.url
                            if (resp.code == 303) {
                                currentMethod = "GET"
                            }
                        }
                    }
                } else {
                    return HopStep.Terminal(
                        url = resp.request.url.toString(),
                        statusCode = resp.code,
                        statusMessage = resp.message.ifBlank { "HTTP ${resp.code}" },
                        contentLength = parseContentLength(resp),
                        contentType = resp.header("Content-Type")?.trim()?.ifBlank { null },
                        contentDisposition = resp.header("Content-Disposition")?.trim()?.ifBlank { null },
                        etag = resp.header("ETag")?.trim()?.ifBlank { null },
                        lastModified = resp.header("Last-Modified")?.trim()?.ifBlank { null },
                        acceptsRanges = resp.code == 206 ||
                            resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true ||
                            resp.header("Content-Range") != null,
                        referenceSha256 = parseReferenceSha256(resp),
                    )
                }
            }
        }
    }

    private fun parseContentLength(response: Response): Long? {
        if (response.code == 206) {
            val contentRangeHeader = response.header("Content-Range")
            if (!contentRangeHeader.isNullOrBlank()) {
                val totalPart = contentRangeHeader.substringAfterLast('/', "").trim()
                if (totalPart != "*") {
                    val length = totalPart.toLongOrNull()
                    if (length != null && length >= 0) return length
                }
            }
            return null
        }

        val contentLengthHeader = response.header("Content-Length")
        if (!contentLengthHeader.isNullOrBlank()) {
            val length = contentLengthHeader.split(',').firstOrNull()?.trim()?.toLongOrNull()
            if (length != null && length >= 0) return length
        }

        val contentRangeHeader = response.header("Content-Range")
        if (!contentRangeHeader.isNullOrBlank()) {
            val totalPart = contentRangeHeader.substringAfterLast('/', "").trim()
            if (totalPart != "*") {
                val length = totalPart.toLongOrNull()
                if (length != null && length >= 0) return length
            }
        }

        return null
    }

    private fun parseReferenceSha256(response: Response): String? =
        ReferenceSha256Parser.fromHeaders(
            xChecksumSha256 = joinedHeader(response, "X-Checksum-Sha256"),
            contentDigest = joinedHeader(response, "Content-Digest"),
            digest = joinedHeader(response, "Digest"),
        )

    private fun joinedHeader(response: Response, name: String): String? {
        val values = response.headers(name)
        if (values.isEmpty()) return null
        return values.joinToString(",")
    }

    private sealed interface HopStep {
        data class Terminal(
            val url: String,
            val statusCode: Int,
            val statusMessage: String,
            val contentLength: Long?,
            val contentType: String?,
            val contentDisposition: String?,
            val etag: String?,
            val lastModified: String?,
            val acceptsRanges: Boolean,
            val referenceSha256: String?,
        ) : HopStep {
            fun toMetadata() = DownloadMetadata(
                url = url,
                contentLength = contentLength,
                contentType = contentType,
                contentDisposition = contentDisposition,
                etag = etag,
                lastModified = lastModified,
                acceptsRanges = acceptsRanges,
                statusCode = statusCode,
                referenceSha256 = referenceSha256,
            )
        }

        data class RedirectFailure(val failure: DownloadMetadataResult.Failure.RedirectError) : HopStep
        data class NetworkFailure(val failure: DownloadMetadataResult.Failure.NetworkError) : HopStep
    }

    companion object {
        const val DEFAULT_MAX_REDIRECTS = 20

        val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}
