package com.espitman.sdm.domain

import java.net.URI

enum class DownloadUrlError {
    EMPTY,
    UNSUPPORTED_SCHEME,
    CREDENTIALS_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    INVALID_PORT,
    MALFORMED,
}

sealed class DownloadUrlResult {
    data class Valid(val url: String) : DownloadUrlResult()
    data class Invalid(val error: DownloadUrlError) : DownloadUrlResult()
}

object DownloadUrl {
    private val supportedSchemes = setOf("http", "https")

    fun validate(raw: String): DownloadUrlResult {
        val url = raw.trim()
        if (url.isEmpty()) return DownloadUrlResult.Invalid(DownloadUrlError.EMPTY)
        if (url.any { it.isWhitespace() }) return DownloadUrlResult.Invalid(DownloadUrlError.MALFORMED)

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return DownloadUrlResult.Invalid(DownloadUrlError.MALFORMED)
        }
        val scheme = uri.scheme?.lowercase()
            ?: return DownloadUrlResult.Invalid(DownloadUrlError.MALFORMED)
        if (scheme !in supportedSchemes) {
            return DownloadUrlResult.Invalid(DownloadUrlError.UNSUPPORTED_SCHEME)
        }
        if (uri.isOpaque) return DownloadUrlResult.Invalid(DownloadUrlError.MALFORMED)
        if (uri.host.isNullOrBlank()) {
            return DownloadUrlResult.Invalid(DownloadUrlError.MALFORMED)
        }
        if (uri.rawUserInfo != null) {
            return DownloadUrlResult.Invalid(DownloadUrlError.CREDENTIALS_NOT_ALLOWED)
        }
        if (uri.rawFragment != null) {
            return DownloadUrlResult.Invalid(DownloadUrlError.FRAGMENT_NOT_ALLOWED)
        }
        if (uri.port !in -1..65535) {
            return DownloadUrlResult.Invalid(DownloadUrlError.INVALID_PORT)
        }
        return DownloadUrlResult.Valid(url)
    }

    fun errorMessage(error: DownloadUrlError): String = when (error) {
        DownloadUrlError.EMPTY -> "Enter a direct HTTP or HTTPS download URL."
        DownloadUrlError.UNSUPPORTED_SCHEME -> "Only HTTP and HTTPS download links are supported."
        DownloadUrlError.CREDENTIALS_NOT_ALLOWED -> "Remove the username or password from this download URL."
        DownloadUrlError.FRAGMENT_NOT_ALLOWED -> "Remove the # fragment from this download URL."
        DownloadUrlError.INVALID_PORT -> "This download URL uses an invalid port."
        DownloadUrlError.MALFORMED -> "This is not a valid HTTP or HTTPS download URL."
    }
}
