package com.espitman.sdm.ui

import com.espitman.sdm.network.DownloadFilenameResolver
import com.espitman.sdm.network.ScopedRequestContext

internal data class BrowserDownloadRequest(
    val url: String,
    val suggestedFileName: String,
    val mimeType: String?,
    val contentLength: Long?,
    val userAgent: String?,
    val requestContext: ScopedRequestContext? = null,
) {
    companion object {
        fun fromWebView(
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ) = BrowserDownloadRequest(
            url = url,
            suggestedFileName = DownloadFilenameResolver.resolveFilename(contentDisposition, url),
            mimeType = mimeType?.trim()?.takeIf(String::isNotEmpty),
            contentLength = contentLength.takeIf { it >= 0L },
            userAgent = userAgent?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}

internal object BrowserDownloadHandoffPolicy {
    fun fromUserSelectedWebViewDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ): BrowserDownloadRequest = BrowserDownloadRequest.fromWebView(
        url,
        userAgent,
        contentDisposition,
        mimeType,
        contentLength,
    )
}
