package com.espitman.sdm.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
internal class SettingsUiState {
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
}

internal enum class SettingsSearchRow {
    Connections,
    Simultaneous,
    AutoResume,
    DailySchedule,
    WifiOnly,
    SpeedLimit,
    SaveLocation,
    DownloadComplete,
    SpeedAlerts,
    Theme,
    Language,
    Version,
    Reset,
}

internal enum class SettingsSearchTrailingAction {
    ClearQuery,
    CloseSearch,
}

internal data class SettingsSearchItem(
    val row: SettingsSearchRow,
    val groupTitle: String,
    val title: String,
    val subtitle: String,
    val synonyms: List<String> = emptyList(),
)

internal fun saveSettingsUiState(state: SettingsUiState): List<Any> = listOf(
    state.searchOpen,
    state.query,
)

internal fun restoreSettingsUiState(saved: List<*>): SettingsUiState {
    val restored = SettingsUiState()
    restored.searchOpen = saved.getOrNull(0) as? Boolean ?: false
    restored.query = saved.getOrNull(1) as? String ?: ""
    return restored
}

private val SettingsUiStateSaver = listSaver<SettingsUiState, Any>(
    save = { saveSettingsUiState(it) },
    restore = { restoreSettingsUiState(it) },
)

@Composable
internal fun rememberSettingsUiState(): SettingsUiState =
    rememberSaveable(saver = SettingsUiStateSaver) { SettingsUiState() }

internal fun toggleSettingsSearch(state: SettingsUiState) {
    state.searchOpen = !state.searchOpen
    if (!state.searchOpen) state.query = ""
}

internal fun closeSettingsSearch(state: SettingsUiState) {
    state.searchOpen = false
    state.query = ""
}

internal fun settingsSearchActiveQuery(searchOpen: Boolean, query: String): String =
    if (searchOpen) query else ""

internal fun settingsSearchTrailingAction(query: String): SettingsSearchTrailingAction =
    if (query.isNotEmpty()) SettingsSearchTrailingAction.ClearQuery else SettingsSearchTrailingAction.CloseSearch

internal fun applySettingsSearchTrailingAction(state: SettingsUiState) {
    when (settingsSearchTrailingAction(state.query)) {
        SettingsSearchTrailingAction.ClearQuery -> state.query = ""
        SettingsSearchTrailingAction.CloseSearch -> closeSettingsSearch(state)
    }
}

internal fun settingsSearchCatalog(
    saveLocationLabel: String,
    themeLabel: String,
    versionName: String,
): List<SettingsSearchItem> = listOf(
    SettingsSearchItem(
        row = SettingsSearchRow.Connections,
        groupTitle = "DOWNLOAD BEHAVIOR",
        title = "Connections",
        subtitle = "Parallel threads per download",
        synonyms = listOf("threads", "parallel", "segment"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.Simultaneous,
        groupTitle = "DOWNLOAD BEHAVIOR",
        title = "Simultaneous downloads",
        subtitle = "Maximum active downloads",
        synonyms = listOf("concurrent", "slots", "queue"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.AutoResume,
        groupTitle = "DOWNLOAD BEHAVIOR",
        title = "Auto-resume",
        subtitle = "Continue interrupted downloads",
        synonyms = listOf("resume", "interrupted", "auto resume"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.DailySchedule,
        groupTitle = "DOWNLOAD BEHAVIOR",
        title = "Scheduled downloads",
        subtitle = "Resume all and pause all at set times",
        synonyms = listOf("schedule", "timer", "daily", "pause all", "resume all"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.WifiOnly,
        groupTitle = "NETWORK",
        title = "Wi-Fi only",
        subtitle = "Pause downloads on mobile data",
        synonyms = listOf("wifi", "wireless", "cellular", "mobile data"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.SpeedLimit,
        groupTitle = "NETWORK",
        title = "Speed limit",
        subtitle = "Combined download speed",
        synonyms = listOf("throttle", "bandwidth", "unlimited", "mb/s"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.SaveLocation,
        groupTitle = "STORAGE",
        title = "Save location",
        subtitle = saveLocationLabel,
        synonyms = listOf("folder", "directory", "destination", "path", "storage"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.DownloadComplete,
        groupTitle = "NOTIFICATIONS",
        title = "Download complete",
        subtitle = "Notify when a transfer finishes",
        synonyms = listOf("completion", "finished", "notify"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.SpeedAlerts,
        groupTitle = "NOTIFICATIONS",
        title = "Speed alerts",
        subtitle = "Warn when transfers stall",
        synonyms = listOf("stall", "slow", "alert"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.Theme,
        groupTitle = "APPEARANCE",
        title = "Theme",
        subtitle = themeLabel,
        synonyms = listOf("appearance", "dark", "light", "black", "gold"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.Language,
        groupTitle = "APPEARANCE",
        title = "Language",
        subtitle = "English only",
        synonyms = listOf("english", "locale"),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.Version,
        groupTitle = "ABOUT",
        title = "SDM version",
        subtitle = "Sibi Download Manager",
        synonyms = listOfNotNull("about", "version", "release", versionName.takeIf { it.isNotBlank() }),
    ),
    SettingsSearchItem(
        row = SettingsSearchRow.Reset,
        groupTitle = "",
        title = "Reset settings",
        subtitle = "",
        synonyms = listOf("defaults", "restore"),
    ),
)

internal fun filterSettingsRows(
    items: List<SettingsSearchItem>,
    query: String,
): List<SettingsSearchRow> = items.filter { settingsRowMatches(it, query) }.map { it.row }

internal fun settingsRowMatches(item: SettingsSearchItem, rawQuery: String): Boolean {
    val query = rawQuery.trim()
    if (query.isEmpty()) return true
    return item.searchableText.any { settingsSearchHaystackContains(it, query) }
}

private val SettingsSearchItem.searchableText: List<String>
    get() = listOf(groupTitle, title, subtitle) + synonyms

internal fun settingsSearchHaystackContains(haystack: String, needle: String): Boolean {
    if (haystack.contains(needle, ignoreCase = true)) return true
    val normalizedHaystack = settingsSearchNormalized(haystack)
    val normalizedNeedle = settingsSearchNormalized(needle)
    return normalizedNeedle.isNotEmpty() && normalizedHaystack.contains(normalizedNeedle)
}

internal fun settingsSearchNormalized(text: String): String =
    buildString(text.length) {
        var previousSpace = false
        text.forEach { char ->
            val mapped = when {
                char == '-' || char.isWhitespace() -> ' '
                else -> char.lowercaseChar()
            }
            if (mapped == ' ') {
                if (!previousSpace && isNotEmpty()) {
                    append(' ')
                    previousSpace = true
                }
            } else {
                append(mapped)
                previousSpace = false
            }
        }
    }.trim()
