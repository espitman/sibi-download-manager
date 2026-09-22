package com.espitman.sdm.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class BrowserLoadFailure(
    val failingUrl: String,
    val message: String,
)

internal fun normalizeBrowserInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)
    return when {
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) -> trimmed
        scheme != null -> null
        trimmed.contains('.') && trimmed.none(Char::isWhitespace) -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")}"
    }
}

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

internal fun browserFailureMessage(errorCode: Int, description: String?): String = when (errorCode) {
    -2 -> "Check your connection and try again."
    -6 -> "The address could not be found."
    -8 -> "The connection timed out."
    -11 -> "A secure connection could not be established."
    else -> description?.trim()?.takeIf(String::isNotEmpty) ?: "The page could not be loaded."
}
