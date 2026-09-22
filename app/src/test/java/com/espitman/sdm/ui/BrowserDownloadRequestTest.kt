package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserDownloadRequestTest {
    @Test fun webViewDownloadMetadataProducesAnAddRequest() {
        val request = BrowserDownloadRequest.fromWebView(
            url = "https://example.com/raw?id=4",
            userAgent = " SDM Browser ",
            contentDisposition = "attachment; filename*=UTF-8''release%20build.apk",
            mimeType = "application/vnd.android.package-archive",
            contentLength = 42L,
        )
        assertEquals("release build.apk", request.suggestedFileName)
        assertEquals("application/vnd.android.package-archive", request.mimeType)
        assertEquals(42L, request.contentLength)
        assertEquals("SDM Browser", request.userAgent)
    }

    @Test fun unknownLengthAndBlankOptionalMetadataStayUnknown() {
        val request = BrowserDownloadRequest.fromWebView(
            "http://example.com/file.bin", " ", null, "", -1L,
        )
        assertEquals("file.bin", request.suggestedFileName)
        assertNull(request.contentLength)
        assertNull(request.mimeType)
        assertNull(request.userAgent)
    }

    @Test fun handoffUsesTheSamePathForAnyUserSelectedLink() {
        val ordinary = BrowserDownloadHandoffPolicy.fromUserSelectedWebViewDownload(
            "https://example.com/watch?v=1", null, null, "text/html", -1L,
        )
        val youtube = BrowserDownloadHandoffPolicy.fromUserSelectedWebViewDownload(
            "https://youtube.com/watch?v=1", null, null, "text/html", -1L,
        )
        assertEquals("watch", ordinary.suggestedFileName)
        assertEquals("watch", youtube.suggestedFileName)
    }
}
