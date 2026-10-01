package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val catalog = settingsSearchCatalog(
        saveLocationLabel = "/Download/SDM-QA",
        themeLabel = "Black & Gold",
        versionName = "0.2.0",
    )

    @Test
    fun emptyOrBlankQueryKeepsEveryRow() {
        val all = SettingsSearchRow.entries.toList()

        assertEquals(all, filterSettingsRows(catalog, ""))
        assertEquals(all, filterSettingsRows(catalog, "   "))
        assertEquals("", settingsSearchActiveQuery(searchOpen = false, query = "theme"))
        assertEquals("theme", settingsSearchActiveQuery(searchOpen = true, query = "theme"))
    }

    @Test
    fun titleSubtitleAndHyphenatedQueriesAreCaseInsensitive() {
        assertRows("CONNECTIONS", SettingsSearchRow.Connections)
        assertRows("  simultaneous  ", SettingsSearchRow.Simultaneous)
        assertRows("auto-resume", SettingsSearchRow.AutoResume)
        assertRows("scheduled downloads", SettingsSearchRow.DailySchedule)
        assertRows("Auto resume", SettingsSearchRow.AutoResume)
        assertRows("Wi-Fi", SettingsSearchRow.WifiOnly)
        assertRows("wifi", SettingsSearchRow.WifiOnly)
        assertRows("speed limit", SettingsSearchRow.SpeedLimit)
        assertRows("save location", SettingsSearchRow.SaveLocation)
        assertRows("automatic folders", SettingsSearchRow.AutomaticFolders)
        assertRows("backup", SettingsSearchRow.Backup)
        assertRows("download-complete", SettingsSearchRow.DownloadComplete)
        assertRows("speed alerts", SettingsSearchRow.SpeedAlerts)
        assertRows("theme", SettingsSearchRow.Theme)
        assertRows("language", SettingsSearchRow.Language)
        assertRows("version", SettingsSearchRow.Version)
        assertRows("reset", SettingsSearchRow.Reset)
    }

    @Test
    fun groupTitlesAndUsefulSynonymsMatchWithoutDuplicatingRows() {
        assertEquals(
            listOf(SettingsSearchRow.Connections, SettingsSearchRow.Simultaneous, SettingsSearchRow.AutoResume, SettingsSearchRow.DailySchedule),
            filterSettingsRows(catalog, "DOWNLOAD BEHAVIOR"),
        )
        assertEquals(
            listOf(SettingsSearchRow.WifiOnly, SettingsSearchRow.SpeedLimit),
            filterSettingsRows(catalog, "network"),
        )
        assertRows("threads", SettingsSearchRow.Connections)
        assertRows("concurrent", SettingsSearchRow.Simultaneous)
        assertRows("throttle", SettingsSearchRow.SpeedLimit)
        assertRows("folder", SettingsSearchRow.SaveLocation, SettingsSearchRow.RenameFolder, SettingsSearchRow.AutomaticFolders)
        assertRows("stall", SettingsSearchRow.SpeedAlerts)
        assertRows("english", SettingsSearchRow.Language)
        assertRows("defaults", SettingsSearchRow.Reset)
        assertRows("SDM-QA", SettingsSearchRow.SaveLocation)
        assertRows("0.2.0", SettingsSearchRow.Version)
        assertRows("Black & Gold", SettingsSearchRow.Theme)
    }

    @Test
    fun unknownQueryHidesEveryRowAndClearingRestoresTheFullList() {
        assertTrue(filterSettingsRows(catalog, "no-such-setting").isEmpty())
        assertEquals(SettingsSearchRow.entries.toList(), filterSettingsRows(catalog, ""))
    }

    @Test
    fun defaultsAreClosedAndRoundTripRestoresSearchWithoutStartupOpenState() {
        val fresh = SettingsUiState()
        assertFalse(fresh.searchOpen)
        assertEquals("", fresh.query)

        val state = SettingsUiState().apply {
            searchOpen = true
            query = "Wi-Fi — سرعت"
        }
        val restored = restoreSettingsUiState(saveSettingsUiState(state))
        assertTrue(restored.searchOpen)
        assertEquals("Wi-Fi — سرعت", restored.query)

        val malformed = restoreSettingsUiState(listOf("yes", null))
        assertFalse(malformed.searchOpen)
        assertEquals("", malformed.query)
    }

    @Test
    fun headerBackAndCloseRestoreTheFullList() {
        val state = SettingsUiState().apply {
            searchOpen = true
            query = "theme"
        }

        assertEquals(SettingsSearchTrailingAction.ClearQuery, settingsSearchTrailingAction(state.query))
        applySettingsSearchTrailingAction(state)
        assertTrue(state.searchOpen)
        assertEquals("", state.query)
        assertEquals(SettingsSearchRow.entries.toList(), filterSettingsRows(catalog, settingsSearchActiveQuery(state.searchOpen, state.query)))

        assertEquals(SettingsSearchTrailingAction.CloseSearch, settingsSearchTrailingAction(state.query))
        applySettingsSearchTrailingAction(state)
        assertFalse(state.searchOpen)
        assertEquals("", state.query)

        state.searchOpen = true
        state.query = "wifi"
        closeSettingsSearch(state)
        assertFalse(state.searchOpen)
        assertEquals("", state.query)

        state.searchOpen = true
        state.query = "language"
        toggleSettingsSearch(state)
        assertFalse(state.searchOpen)
        assertEquals("", state.query)
        toggleSettingsSearch(state)
        assertTrue(state.searchOpen)
        assertEquals("", state.query)
    }

    private fun assertRows(query: String, vararg rows: SettingsSearchRow) {
        assertEquals(rows.toList(), filterSettingsRows(catalog, query))
    }
}
