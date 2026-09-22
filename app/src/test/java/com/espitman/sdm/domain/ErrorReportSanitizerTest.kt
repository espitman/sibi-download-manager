package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReportSanitizerTest {
    @Test
    fun stripsUrlsTokensAndPathsWhilePreservingClassificationPhrases() {
        val urlWithToken = "https://cdn.example.com/file.bin?token=s3cret&sig=abc"
        val connect = ErrorReportSanitizer.sanitize(
            "Failed to connect to example.com/1.2.3.4:443",
        )
        assertEquals("Failed to connect to", connect)
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify(connect))
        assertFalse(connect.contains("example.com"))
        assertFalse(connect.contains("1.2.3.4"))

        val resolve = ErrorReportSanitizer.sanitize(
            """java.net.UnknownHostException: Unable to resolve host "secret.internal.corp": No address associated with hostname""",
        )
        assertTrue(resolve.contains("Unable to resolve host"))
        assertTrue(resolve.contains("UnknownHostException"))
        assertFalse(resolve.contains("secret.internal.corp"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify(resolve))

        val redirect = ErrorReportSanitizer.sanitize(
            "Redirect to unsupported or invalid URL: $urlWithToken (Only HTTP and HTTPS download links are supported.)",
        )
        assertTrue(redirect.contains("Redirect to unsupported or invalid URL"))
        assertTrue(redirect.contains("Only HTTP and HTTPS"))
        assertFalse(redirect.contains("cdn.example.com"))
        assertFalse(redirect.contains("s3cret"))
        assertFalse(redirect.contains("token="))

        val location = ErrorReportSanitizer.sanitize(
            "Invalid or unsupported redirect location: https://evil.example/x?access_token=leak",
        )
        assertEquals("Invalid or unsupported redirect location", location)
        assertFalse(location.contains("evil.example"))
        assertFalse(location.contains("access_token"))

        val path = ErrorReportSanitizer.sanitize(
            "Destination already exists: /storage/emulated/0/Android/data/com.espitman.sdm/files/Download/secret.pdf",
        )
        assertEquals("Destination already exists", path)
        assertFalse(path.contains("storage"))
        assertFalse(path.contains("secret.pdf"))

        val tmp = ErrorReportSanitizer.sanitize(
            "Destination parent is not a directory: /tmp/sdm065-grok/blocked/file.bin",
        )
        assertEquals("Destination parent is not a directory", tmp)
        assertFalse(tmp.contains("/tmp"))
        assertEquals(DownloadFailure.OTHER, DownloadFailure.classify(tmp))
    }

    @Test
    fun stripsCredentialsAndLeavesHttpStatusForClassification() {
        val cookie = ErrorReportSanitizer.sanitize(
            "HTTP 403: Forbidden Cookie: session=abc123; auth=leak",
        )
        assertTrue(cookie.startsWith("HTTP 403: Forbidden"))
        assertFalse(cookie.contains("session="))
        assertFalse(cookie.contains("abc123"))
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify(cookie))

        val bearer = ErrorReportSanitizer.sanitize(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 HTTP 401: Unauthorized",
        )
        assertFalse(bearer.contains("Bearer"))
        assertFalse(bearer.contains("eyJ"))
        assertTrue(bearer.contains("HTTP 401"))
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify(bearer))

        val userinfo = ErrorReportSanitizer.sanitize(
            "Network error connecting to https://user:hunter2@files.example.com/private.bin",
        )
        assertFalse(userinfo.contains("hunter2"))
        assertFalse(userinfo.contains("user:"))
        assertFalse(userinfo.contains("files.example.com"))

        assertEquals(
            "HTTP 404: Not Found",
            ErrorReportSanitizer.sanitize("HTTP 404: Not Found"),
        )
        assertEquals(
            DownloadFailure.HTTP_ERROR,
            DownloadFailure.classify(ErrorReportSanitizer.sanitize("HTTP 404: Not Found")),
        )
        assertEquals(
            "java.io.IOException: No space left on device",
            ErrorReportSanitizer.sanitize("java.io.IOException:\nNo space left on device"),
        )
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify(
                ErrorReportSanitizer.sanitize("java.io.IOException:\nNo space left on device"),
            ),
        )
        assertEquals(
            "SSLHandshakeException: certificate validation failed",
            ErrorReportSanitizer.sanitize("SSLHandshakeException: certificate validation failed"),
        )
        assertEquals(
            DownloadFailure.TIMEOUT,
            DownloadFailure.classify(ErrorReportSanitizer.sanitize("SocketTimeoutException: timeout")),
        )
    }

    @Test
    fun boundsLengthAndIsIdempotent() {
        val long = "HTTP 500: " + "n".repeat(ErrorReportSanitizer.MAX_LENGTH)
        val first = ErrorReportSanitizer.sanitize(long)
        assertEquals(ErrorReportSanitizer.MAX_LENGTH, first.length)
        assertTrue(first.endsWith("…"))
        assertEquals(first, ErrorReportSanitizer.sanitize(first))
        assertEquals("", ErrorReportSanitizer.sanitize("   \n\t  "))
        assertEquals("", ErrorReportSanitizer.sanitize(null))
        assertEquals("", ErrorReportSanitizer.sanitize("https://only.example/path?token=1"))
    }
}
