package com.espitman.sdm.ui

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val BROWSER_PREFS_SCHEMA = 1

internal data class BrowserPreferences(
    val privateByDefault: Boolean = false,
    val blockTrackers: Boolean = true,
    val clearOnExit: Boolean = false,
    val searchEngine: BrowserSearchEngine = BrowserSearchEngine.Google,
)

internal data class BrowserPersistedSession(
    val tabs: List<BrowserTab>,
    val activeId: String,
    val nextTabOrdinal: Int,
)

internal fun parseBrowserPreferences(
    privateByDefault: Boolean,
    blockTrackers: Boolean,
    clearOnExit: Boolean,
    searchEngineId: String?,
): BrowserPreferences = BrowserPreferences(
    privateByDefault = privateByDefault,
    blockTrackers = blockTrackers,
    clearOnExit = clearOnExit,
    searchEngine = BrowserSearchEngine.fromId(searchEngineId),
)

internal fun shouldBlockBrowserTracker(url: String, blockTrackers: Boolean): Boolean =
    blockTrackers && BrowserTrackingProtection.shouldBlock(url)

internal fun shouldClearBrowserDataOnStart(
    @Suppress("UNUSED_PARAMETER") preferences: BrowserPreferences,
    startingTabs: List<BrowserTab>,
): Boolean = startingTabs.any(BrowserTab::isPrivate)

internal fun shouldClearBrowserDataOnExit(
    preferences: BrowserPreferences,
    tabs: List<BrowserTab>,
): Boolean = preferences.clearOnExit || tabs.any(BrowserTab::isPrivate)

internal fun resolvePrivateByDefaultPreference(
    storedSchema: Int?,
    storedPrivateByDefault: Boolean?,
): Boolean {
    if ((storedSchema ?: 0) >= BROWSER_PREFS_SCHEMA) {
        return storedPrivateByDefault == true
    }
    return false
}

internal fun needsBrowserPrefsSchemaWrite(storedSchema: Int?): Boolean =
    (storedSchema ?: 0) < BROWSER_PREFS_SCHEMA

internal fun sessionToPersistOnExit(
    preferences: BrowserPreferences,
    snapshot: BrowserTabsSnapshot,
    nextTabOrdinal: Int,
): BrowserPersistedSession {
    if (preferences.clearOnExit) {
        return BrowserPersistedSession(emptyList(), "", nextTabOrdinal)
    }
    val regular = persistableBrowserTabs(snapshot.tabs)
    return BrowserPersistedSession(
        tabs = regular,
        activeId = persistableActiveId(regular, snapshot.activeId).orEmpty(),
        nextTabOrdinal = nextTabOrdinal,
    )
}

internal fun resolveStartupBrowserSession(
    firstLaunch: Boolean,
    persisted: BrowserPersistedSession?,
    privateByDefault: Boolean,
): BrowserPersistedSession {
    if (firstLaunch) {
        if (privateByDefault) {
            val tab = createBrowserTab("tab-1", explicitPrivate = true)
            return BrowserPersistedSession(listOf(tab), tab.id, nextTabOrdinal = 2)
        }
        val initial = initialBrowserTabs()
        return BrowserPersistedSession(initial.tabs, initial.activeId, nextTabOrdinal = 3)
    }
    val restored = persisted?.takeIf { it.tabs.isNotEmpty() }?.let { session ->
        val activeId = persistableActiveId(session.tabs, session.activeId) ?: session.tabs.first().id
        session.copy(activeId = activeId)
    }
    if (restored != null) return restored
    val tab = createBrowserTab("tab-1", explicitPrivate = privateByDefault)
    return BrowserPersistedSession(listOf(tab), tab.id, nextTabOrdinal = persisted?.nextTabOrdinal?.coerceAtLeast(2) ?: 2)
}

internal fun encodeBrowserSession(
    tabs: List<BrowserTab>,
    activeId: String,
    nextTabOrdinal: Int,
): String = buildString {
    appendLine(BROWSER_SESSION_VERSION)
    appendLine(nextTabOrdinal)
    appendLine(encodeSessionField(activeId))
    appendLine(tabs.size)
    tabs.forEach { tab ->
        append(encodeSessionField(tab.id))
        append('\t')
        append(if (tab.isPrivate) '1' else '0')
        append('\t')
        append(encodeSessionField(tab.url.orEmpty()))
        append('\t')
        appendLine(encodeSessionField(tab.title))
    }
}

internal fun decodeBrowserSession(raw: String): BrowserPersistedSession? {
    val lines = raw.split('\n').map { it.trimEnd('\r') }
    if (lines.size < 4 || lines[0] != BROWSER_SESSION_VERSION) return null
    val nextTabOrdinal = lines[1].toIntOrNull() ?: return null
    val activeId = decodeSessionField(lines[2])
    val count = lines[3].toIntOrNull() ?: return null
    if (count < 0) return null
    val tabLines = lines.drop(4).filter { it.isNotEmpty() }
    if (tabLines.size < count) return null
    val tabs = (0 until count).map { index ->
        val parts = tabLines[index].split('\t')
        if (parts.size != 4) return null
        BrowserTab(
            id = decodeSessionField(parts[0]),
            title = decodeSessionField(parts[3]).ifBlank { "New tab" },
            url = decodeSessionField(parts[2]).ifBlank { null },
            isPrivate = parts[1] == "1",
        )
    }
    if (tabs.map { it.id }.distinct().size != tabs.size) return null
    if (tabs.isNotEmpty() && tabs.none { it.id == activeId }) {
        return BrowserPersistedSession(tabs, tabs.first().id, nextTabOrdinal)
    }
    return BrowserPersistedSession(tabs, activeId, nextTabOrdinal)
}

internal class BrowserPreferencesStore internal constructor(
    private val preferences: SharedPreferences,
) {
    fun read(): BrowserPreferences {
        migrateLegacyPrivateByDefaultIfNeeded()
        return parseBrowserPreferences(
            privateByDefault = preferences.getBoolean(KEY_PRIVATE_BY_DEFAULT, false),
            blockTrackers = preferences.getBoolean(KEY_BLOCK_TRACKERS, true),
            clearOnExit = preferences.getBoolean(KEY_CLEAR_ON_EXIT, false),
            searchEngineId = preferences.getString(KEY_SEARCH_ENGINE, BrowserSearchEngine.Google.id),
        )
    }

    fun write(value: BrowserPreferences) {
        preferences.edit()
            .putInt(KEY_SCHEMA, BROWSER_PREFS_SCHEMA)
            .putBoolean(KEY_PRIVATE_BY_DEFAULT, value.privateByDefault)
            .putBoolean(KEY_BLOCK_TRACKERS, value.blockTrackers)
            .putBoolean(KEY_CLEAR_ON_EXIT, value.clearOnExit)
            .putString(KEY_SEARCH_ENGINE, value.searchEngine.id)
            .apply()
    }

    fun hasPersistedSession(): Boolean = preferences.contains(KEY_SESSION)

    fun readSession(): BrowserPersistedSession? =
        preferences.getString(KEY_SESSION, null)?.let(::decodeBrowserSession)

    fun writeSession(session: BrowserPersistedSession) {
        preferences.edit()
            .putString(
                KEY_SESSION,
                encodeBrowserSession(session.tabs, session.activeId, session.nextTabOrdinal),
            )
            .apply()
    }

    fun readHistory(): List<BrowserHistoryEntry> =
        preferences.getString(KEY_HISTORY, null)?.let(::decodeBrowserHistory).orEmpty()

    fun writeHistory(entries: List<BrowserHistoryEntry>) {
        preferences.edit().putString(KEY_HISTORY, encodeBrowserHistory(entries)).apply()
    }

    private fun migrateLegacyPrivateByDefaultIfNeeded() {
        val storedSchema = if (preferences.contains(KEY_SCHEMA)) {
            preferences.getInt(KEY_SCHEMA, 0)
        } else {
            null
        }
        if (!needsBrowserPrefsSchemaWrite(storedSchema)) return
        val storedPrivateByDefault = if (preferences.contains(KEY_PRIVATE_BY_DEFAULT)) {
            preferences.getBoolean(KEY_PRIVATE_BY_DEFAULT, false)
        } else {
            null
        }
        preferences.edit()
            .putInt(KEY_SCHEMA, BROWSER_PREFS_SCHEMA)
            .putBoolean(
                KEY_PRIVATE_BY_DEFAULT,
                resolvePrivateByDefaultPreference(storedSchema, storedPrivateByDefault),
            )
            .apply()
    }

    companion object {
        private const val FILE = "sdm_browser"
        private const val KEY_SCHEMA = "prefs_schema"
        private const val KEY_PRIVATE_BY_DEFAULT = "private_by_default"
        private const val KEY_BLOCK_TRACKERS = "block_trackers"
        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_SESSION = "session_v1"
        private const val KEY_HISTORY = "history_v1"

        @Volatile private var instance: BrowserPreferencesStore? = null

        fun get(context: Context): BrowserPreferencesStore = instance ?: synchronized(this) {
            instance ?: BrowserPreferencesStore(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

private const val BROWSER_SESSION_VERSION = "v1"

private fun encodeSessionField(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun decodeSessionField(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
