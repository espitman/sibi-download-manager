package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadUrlTest {
    @Test
    fun acceptsDirectHttpAndHttpsUrls() {
        listOf(
            "https://example.com/file.zip",
            "HTTP://example.com/file.bin",
            "https://cdn.example.com:8443/path/to/file.apk?token=1",
            "http://127.0.0.1:8080/small.bin",
            "https://[2001:db8::1]/file",
            "  https://media.example.com/Dune.mkv  ",
        ).forEach { input ->
            val result = DownloadUrl.validate(input)
            assertTrue("$input should be valid", result is DownloadUrlResult.Valid)
            assertEquals(input.trim(), (result as DownloadUrlResult.Valid).url)
        }
    }

    @Test
    fun rejectsBlankAndNonHttpSchemes() {
        assertEquals(DownloadUrlError.EMPTY, (DownloadUrl.validate("") as DownloadUrlResult.Invalid).error)
        assertEquals(DownloadUrlError.EMPTY, (DownloadUrl.validate("   ") as DownloadUrlResult.Invalid).error)
        assertEquals(
            DownloadUrlError.UNSUPPORTED_SCHEME,
            (DownloadUrl.validate("ftp://example.com/file.zip") as DownloadUrlResult.Invalid).error,
        )
        assertEquals(
            DownloadUrlError.UNSUPPORTED_SCHEME,
            (DownloadUrl.validate("file:///sdcard/file.zip") as DownloadUrlResult.Invalid).error,
        )
        assertEquals(
            DownloadUrlError.UNSUPPORTED_SCHEME,
            (DownloadUrl.validate("javascript:alert(1)") as DownloadUrlResult.Invalid).error,
        )
        assertEquals(
            DownloadUrlError.UNSUPPORTED_SCHEME,
            (DownloadUrl.validate("magnet:?xt=urn:btih:abc") as DownloadUrlResult.Invalid).error,
        )
    }

    @Test
    fun rejectsCredentialsFragmentsAndInvalidPorts() {
        assertEquals(
            DownloadUrlError.CREDENTIALS_NOT_ALLOWED,
            (DownloadUrl.validate("https://user:secret@example.com/file.zip") as DownloadUrlResult.Invalid).error,
        )
        assertEquals(
            DownloadUrlError.FRAGMENT_NOT_ALLOWED,
            (DownloadUrl.validate("https://example.com/file.zip#section") as DownloadUrlResult.Invalid).error,
        )
        assertEquals(
            DownloadUrlError.INVALID_PORT,
            (DownloadUrl.validate("https://example.com:65536/file.zip") as DownloadUrlResult.Invalid).error,
        )
    }

    @Test
    fun rejectsMissingSchemeHostAndMalformedInput() {
        listOf(
            "example.com/file.zip",
            "www.example.com",
            "https://",
            "http://",
            "https:///file.zip",
            "https://example.com/file zip",
            "not a url",
        ).forEach { input ->
            val result = DownloadUrl.validate(input)
            assertTrue("$input should be invalid", result is DownloadUrlResult.Invalid)
            assertEquals(DownloadUrlError.MALFORMED, (result as DownloadUrlResult.Invalid).error)
        }
    }

    @Test
    fun errorCopyDescribesTheFailure() {
        assertEquals(
            "Enter a direct HTTP or HTTPS download URL.",
            DownloadUrl.errorMessage(DownloadUrlError.EMPTY),
        )
        assertEquals(
            "Only HTTP and HTTPS download links are supported.",
            DownloadUrl.errorMessage(DownloadUrlError.UNSUPPORTED_SCHEME),
        )
        assertEquals(
            "This is not a valid HTTP or HTTPS download URL.",
            DownloadUrl.errorMessage(DownloadUrlError.MALFORMED),
        )
        assertEquals(
            "Remove the username or password from this download URL.",
            DownloadUrl.errorMessage(DownloadUrlError.CREDENTIALS_NOT_ALLOWED),
        )
        assertEquals(
            "Remove the # fragment from this download URL.",
            DownloadUrl.errorMessage(DownloadUrlError.FRAGMENT_NOT_ALLOWED),
        )
        assertEquals(
            "This download URL uses an invalid port.",
            DownloadUrl.errorMessage(DownloadUrlError.INVALID_PORT),
        )
    }

    @Test
    fun downloadModelRejectsTheSameInvalidUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            Download(
                url = "ftp://example.com/file.zip",
                fileName = "file.zip",
                createdAtEpochMillis = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Download(
                url = "example.com/file.zip",
                fileName = "file.zip",
                createdAtEpochMillis = 1,
            )
        }
    }
}
