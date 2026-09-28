package com.espitman.sdm.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class BrowserLoadFailure(
    val failingUrl: String,
    val message: String,
)

internal enum class BrowserSearchEngine(val id: String, val label: String, val hostLabel: String) {
    Google("google", "Google", "google.com"),
    DuckDuckGo("duckduckgo", "DuckDuckGo", "duckduckgo.com"),
    Bing("bing", "Bing", "bing.com"),
    Startpage("startpage", "Startpage", "startpage.com"),
    ;

    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return when (this) {
            Google -> "https://www.google.com/search?q=$encoded"
            DuckDuckGo -> "https://duckduckgo.com/?q=$encoded"
            Bing -> "https://www.bing.com/search?q=$encoded"
            Startpage -> "https://www.startpage.com/sp/search?query=$encoded"
        }
    }

    companion object {
        fun fromId(id: String?): BrowserSearchEngine =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Google
    }
}

internal fun normalizeBrowserInput(
    input: String,
    searchEngine: BrowserSearchEngine = BrowserSearchEngine.Google,
): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)
    return when {
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) -> trimmed
        scheme != null -> null
        trimmed.contains('.') && trimmed.none(Char::isWhitespace) -> "https://$trimmed"
        else -> searchEngine.searchUrl(trimmed)
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
