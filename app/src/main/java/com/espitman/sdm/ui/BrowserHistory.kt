package com.espitman.sdm.ui

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val BROWSER_HISTORY_LIMIT = 100

internal data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
)

internal fun shouldRecordBrowserHistory(isPrivate: Boolean, persistHistory: Boolean): Boolean =
    !isPrivate && persistHistory

internal fun shouldClearBrowserHistoryOnExit(preferences: BrowserPreferences): Boolean =
    preferences.clearOnExit

internal fun browserHistoryMenuOpensSheet(isPrivate: Boolean): Boolean = !isPrivate

internal fun isRecordableBrowserHistoryUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = HISTORY_SCHEME.find(trimmed)?.groupValues?.get(1) ?: return false
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}

internal fun browserHistoryDisplayHost(url: String): String =
    url.substringAfter("://").substringBefore('/').ifBlank { url }

internal fun recordBrowserHistoryVisit(
    entries: List<BrowserHistoryEntry>,
    url: String,
    title: String,
    isPrivate: Boolean,
    persistHistory: Boolean,
    visitedAt: Long,
    limit: Int = BROWSER_HISTORY_LIMIT,
): List<BrowserHistoryEntry> {
    if (!shouldRecordBrowserHistory(isPrivate, persistHistory)) return entries
    val normalizedUrl = url.trim()
    if (!isRecordableBrowserHistoryUrl(normalizedUrl)) return entries
    val resolvedTitle = title.trim().ifBlank { browserHistoryDisplayHost(normalizedUrl) }
    val next = BrowserHistoryEntry(normalizedUrl, resolvedTitle, visitedAt)
    return (listOf(next) + entries.filterNot { it.url == normalizedUrl }).take(limit.coerceAtLeast(0))
}

internal fun encodeBrowserHistory(entries: List<BrowserHistoryEntry>): String = buildString {
    appendLine(BROWSER_HISTORY_VERSION)
    appendLine(entries.size)
    entries.forEach { entry ->
        append(encodeHistoryField(entry.url))
        append('\t')
        append(encodeHistoryField(entry.title))
        append('\t')
        appendLine(entry.visitedAt)
    }
}

internal fun decodeBrowserHistory(raw: String): List<BrowserHistoryEntry>? {
    val lines = raw.split('\n').map { it.trimEnd('\r') }
    if (lines.size < 2 || lines[0] != BROWSER_HISTORY_VERSION) return null
    val count = lines[1].toIntOrNull() ?: return null
    if (count < 0) return null
    val entryLines = lines.drop(2).filter { it.isNotEmpty() }
    if (entryLines.size < count) return null
    return (0 until count).map { index ->
        val parts = entryLines[index].split('\t')
        if (parts.size != 3) return null
        val url = decodeHistoryField(parts[0])
        val title = decodeHistoryField(parts[1])
        val visitedAt = parts[2].toLongOrNull() ?: return null
        if (!isRecordableBrowserHistoryUrl(url)) return null
        BrowserHistoryEntry(url, title.ifBlank { browserHistoryDisplayHost(url) }, visitedAt)
    }
}

private const val BROWSER_HISTORY_VERSION = "v1"
private val HISTORY_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

private fun encodeHistoryField(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun decodeHistoryField(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
