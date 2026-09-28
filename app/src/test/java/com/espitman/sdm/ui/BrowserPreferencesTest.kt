package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPreferencesTest {
    @Test fun defaultsMatchTheReferenceSheet() {
        val parsed = parseBrowserPreferences(false, true, false, null)
        assertFalse(parsed.privateByDefault)
        assertTrue(parsed.blockTrackers)
        assertFalse(parsed.clearOnExit)
        assertEquals(BrowserSearchEngine.Google, parsed.searchEngine)
        assertFalse(BrowserPreferences().privateByDefault)
    }

    @Test fun unknownSearchEngineIdsFallBackToGoogle() {
        assertEquals(
            BrowserSearchEngine.Google,
            parseBrowserPreferences(false, false, true, "unknown").searchEngine,
        )
        assertEquals(
            BrowserSearchEngine.Startpage,
            parseBrowserPreferences(false, true, false, "startpage").searchEngine,
        )
    }

    @Test fun privateByDefaultDoesNotForceCleanupOfRegularTabs() {
        val prefs = BrowserPreferences(privateByDefault = true, clearOnExit = false)
        val regular = listOf(BrowserTab("tab-1", "New tab", null, isPrivate = false))
        assertFalse(shouldClearBrowserDataOnStart(prefs, regular))
        assertFalse(shouldClearBrowserDataOnExit(prefs, regular))
        val privateTab = listOf(BrowserTab("tab-2", "Private tab", null, isPrivate = true))
        val regularPrefs = BrowserPreferences(privateByDefault = false, clearOnExit = false)
        assertTrue(shouldClearBrowserDataOnStart(regularPrefs, privateTab))
        assertTrue(shouldClearBrowserDataOnExit(regularPrefs, privateTab))
    }

    @Test fun legacyUnversionedPrivateByDefaultIsNotTreatedAsAnExplicitChoice() {
        assertFalse(resolvePrivateByDefaultPreference(storedSchema = null, storedPrivateByDefault = true))
        assertFalse(resolvePrivateByDefaultPreference(null, null))
        assertFalse(resolvePrivateByDefaultPreference(0, true))
        assertTrue(needsBrowserPrefsSchemaWrite(null))
        assertTrue(needsBrowserPrefsSchemaWrite(0))
        assertFalse(needsBrowserPrefsSchemaWrite(BROWSER_PREFS_SCHEMA))
        assertTrue(resolvePrivateByDefaultPreference(BROWSER_PREFS_SCHEMA, true))
        assertFalse(resolvePrivateByDefaultPreference(BROWSER_PREFS_SCHEMA, false))
    }

    @Test fun regularSessionsKeepWebDataUnlessClearOnExitIsEnabled() {
        val prefs = BrowserPreferences(privateByDefault = false, clearOnExit = false)
        val regular = listOf(BrowserTab("tab-1", "Example", "https://example.com", isPrivate = false))
        assertFalse(shouldClearBrowserDataOnStart(prefs, regular))
        assertFalse(shouldClearBrowserDataOnExit(prefs, regular))
        assertTrue(
            shouldClearBrowserDataOnExit(prefs.copy(clearOnExit = true), regular),
        )
    }

    @Test fun exitPersistenceDropsPrivateTabsAndClearsWhenRequested() {
        val snapshot = BrowserTabsSnapshot(
            listOf(
                BrowserTab("tab-1", "Example", "https://example.com", isPrivate = false),
                BrowserTab("tab-2", "Private tab", "https://secret.example", isPrivate = true),
            ),
            "tab-2",
        )
        val kept = sessionToPersistOnExit(
            BrowserPreferences(privateByDefault = false, clearOnExit = false),
            snapshot,
            nextTabOrdinal = 6,
        )
        assertEquals(listOf("tab-1"), kept.tabs.map { it.id })
        assertEquals("tab-1", kept.activeId)
        assertEquals(6, kept.nextTabOrdinal)
        val cleared = sessionToPersistOnExit(
            BrowserPreferences(privateByDefault = false, clearOnExit = true),
            snapshot,
            nextTabOrdinal = 6,
        )
        assertTrue(cleared.tabs.isEmpty())
        assertEquals(6, cleared.nextTabOrdinal)
    }

    @Test fun startupRestoresRegularTabsAndDoesNotRelabelThemPrivate() {
        val persisted = BrowserPersistedSession(
            tabs = listOf(BrowserTab("tab-4", "Example", "https://example.com", isPrivate = false)),
            activeId = "tab-4",
            nextTabOrdinal = 5,
        )
        val restored = resolveStartupBrowserSession(
            firstLaunch = false,
            persisted = persisted,
            privateByDefault = true,
        )
        assertEquals("Example", restored.tabs.single().title)
        assertFalse(restored.tabs.single().isPrivate)
        assertEquals(5, restored.nextTabOrdinal)
        val first = resolveStartupBrowserSession(true, null, privateByDefault = false)
        assertTrue(first.tabs.none { it.isPrivate })
        assertEquals(initialBrowserTabs().tabs.map { it.id }, first.tabs.map { it.id })
        val privateStart = resolveStartupBrowserSession(true, null, privateByDefault = true)
        assertTrue(privateStart.tabs.single().isPrivate)
        assertEquals("Private tab", privateStart.tabs.single().title)
        val fresh = resolveStartupBrowserSession(false, BrowserPersistedSession(emptyList(), "", 4), false)
        assertFalse(fresh.tabs.single().isPrivate)
        assertEquals("New tab", fresh.tabs.single().title)
        assertEquals(4, fresh.nextTabOrdinal)
        val privateFresh = resolveStartupBrowserSession(false, BrowserPersistedSession(emptyList(), "", 4), true)
        assertTrue(privateFresh.tabs.single().isPrivate)
        assertEquals("Private tab", privateFresh.tabs.single().title)
    }

    @Test fun sessionEncodingRoundTripsTitlesAndUrlsWithReservedCharacters() {
        val tab = BrowserTab(
            id = "tab-9",
            title = "A & B\tC",
            url = "https://example.com/q?a=1&b=two three",
            isPrivate = false,
        )
        val encoded = encodeBrowserSession(listOf(tab), tab.id, nextTabOrdinal = 10)
        val decoded = decodeBrowserSession(encoded)
        assertEquals(listOf(tab), decoded?.tabs)
        assertEquals("tab-9", decoded?.activeId)
        assertEquals(10, decoded?.nextTabOrdinal)
        assertNull(decodeBrowserSession("not-a-session"))
        val empty = decodeBrowserSession(encodeBrowserSession(emptyList(), "", 3))
        assertEquals(emptyList<BrowserTab>(), empty?.tabs)
        assertEquals(3, empty?.nextTabOrdinal)
        val desktop = tab.copy(desktopSite = true)
        val restoredDesktop = decodeBrowserSession(encodeBrowserSession(listOf(desktop), desktop.id, 11))
        assertFalse(restoredDesktop!!.tabs.single().desktopSite)
    }
}
