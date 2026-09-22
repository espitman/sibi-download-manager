package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsUiStateTest {
    @Test
    fun defaultsAreDownloadingWithSearchAndOverlaysClosed() {
        val state = DownloadsUiState()

        assertEquals(DownloadCategory.Downloading, state.category)
        assertFalse(state.searchOpen)
        assertEquals("", state.query)
        assertFalse(state.menuOpen)
        assertNull(state.overlay)
    }

    @Test
    fun roundTripRestoresDurableCategorySearchAndUnicodeQuery() {
        val state = DownloadsUiState().apply {
            category = DownloadCategory.Completed
            searchOpen = true
            query = "فیلم Dune — 映画.mkv"
            menuOpen = true
            overlay = HomeOverlay.SpeedLimit
        }

        val restored = restoreDownloadsUiState(saveDownloadsUiState(state))

        assertEquals(DownloadCategory.Completed, restored.category)
        assertTrue(restored.searchOpen)
        assertEquals("فیلم Dune — 映画.mkv", restored.query)
    }

    @Test
    fun roundTripClosesTransientMenuAndOverlay() {
        val state = DownloadsUiState().apply {
            category = DownloadCategory.Queued
            searchOpen = true
            query = "queue"
            menuOpen = true
            overlay = HomeOverlay.Preferences
        }

        val restored = restoreDownloadsUiState(saveDownloadsUiState(state))

        assertEquals(DownloadCategory.Queued, restored.category)
        assertTrue(restored.searchOpen)
        assertEquals("queue", restored.query)
        assertFalse(restored.menuOpen)
        assertNull(restored.overlay)
    }

    @Test
    fun malformedSavedValuesFallBackToDefaults() {
        val restored = restoreDownloadsUiState(listOf("UnknownTab", "yes", null))

        assertEquals(DownloadCategory.Downloading, restored.category)
        assertFalse(restored.searchOpen)
        assertEquals("", restored.query)
        assertFalse(restored.menuOpen)
        assertNull(restored.overlay)
    }

    @Test
    fun categoryAndQuerySurviveOpeningAndReturningFromExactSelection() {
        val state = DownloadsUiState().apply {
            category = DownloadCategory.Queued
            searchOpen = true
            query = "Part.Two"
        }
        val selected = download("selected")
        val records = listOf(download("other"), selected)

        assertSame(selected, resolveSelectedDownload(records, "selected"))
        assertEquals(DownloadCategory.Queued, state.category)
        assertEquals("Part.Two", state.query)
        assertTrue(state.searchOpen)

        assertNull(resolveSelectedDownload(records, null))
        assertEquals(DownloadCategory.Queued, state.category)
        assertEquals("Part.Two", state.query)
        assertTrue(state.searchOpen)

        val restored = restoreDownloadsUiState(saveDownloadsUiState(state))
        assertEquals(DownloadCategory.Queued, restored.category)
        assertEquals("Part.Two", restored.query)
        assertTrue(restored.searchOpen)
    }

    private fun download(id: String) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        destinationPath = "/downloads/$id.zip",
        totalBytes = 1_000L,
        downloadedBytes = 0L,
        state = DownloadState.QUEUED,
        error = null,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
    )
}
