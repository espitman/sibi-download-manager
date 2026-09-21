package com.espitman.sdm.network

import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlError

data class DownloadMetadata(
    val url: String,
    val contentLength: Long? = null,
    val contentType: String? = null,
    val contentDisposition: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptsRanges: Boolean = false,
    val statusCode: Int = 200,
    val suggestedFilename: String = DownloadFilenameResolver.resolveFilename(contentDisposition, url),
) {
    init {
        require(url.isNotBlank()) { "Metadata URL cannot be blank" }
        require(contentLength == null || contentLength >= 0) { "Content length cannot be negative" }
        require(contentType == null || contentType.isNotBlank()) { "Content type cannot be blank" }
        require(contentDisposition == null || contentDisposition.isNotBlank()) { "Content disposition cannot be blank" }
        require(etag == null || etag.isNotBlank()) { "ETag cannot be blank" }
        require(lastModified == null || lastModified.isNotBlank()) { "Last-Modified cannot be blank" }
        require(statusCode in 100..599) { "Status code must be a valid HTTP status code: $statusCode" }
        require(suggestedFilename.isNotBlank()) { "Suggested filename cannot be blank" }
    }
}

sealed interface DownloadMetadataResult {
    data class Success(
        val metadata: DownloadMetadata,
    ) : DownloadMetadataResult

    sealed interface Failure : DownloadMetadataResult {
        val url: String
        val message: String
        val cause: Throwable?

        data class HttpError(
            override val url: String,
            val statusCode: Int,
            val statusMessage: String,
            override val cause: Throwable? = null,
        ) : Failure {
            override val message: String
                get() = "HTTP $statusCode: $statusMessage"
        }

        data class RedirectError(
            override val url: String,
            val redirectCount: Int,
            override val message: String,
            override val cause: Throwable? = null,
        ) : Failure

        data class NetworkError(
            override val url: String,
            override val message: String,
            override val cause: Throwable? = null,
        ) : Failure

        data class InvalidUrl(
            override val url: String,
            val urlError: DownloadUrlError,
            override val message: String = DownloadUrl.errorMessage(urlError),
            override val cause: Throwable? = null,
        ) : Failure
    }

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrNull(): DownloadMetadata? =
        (this as? Success)?.metadata

    fun failureOrNull(): Failure? =
        this as? Failure

    fun errorMessageOrNull(): String? =
        (this as? Failure)?.message
}
