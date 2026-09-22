package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesUiStateTest {
    @Test
    fun defaultsAreAllFilesNewestFirstWithSearchClosed() {
        val state = FilesUiState()

        assertEquals(FileTypeFilter.All, state.filter)
        assertFalse(state.searchOpen)
        assertEquals("", state.query)
        assertEquals(FileSortOption.NewestFirst, state.sort)
    }

    @Test
    fun roundTripRestoresDurableFilterSearchAndSort() {
        val state = FilesUiState().apply {
            filter = FileTypeFilter.Archives
            searchOpen = true
            query = "Editorial_Assets — آرشیو.zip"
            sort = FileSortOption.OldestFirst
        }

        val restored = restoreFilesUiState(saveFilesUiState(state))

        assertEquals(FileTypeFilter.Archives, restored.filter)
        assertTrue(restored.searchOpen)
        assertEquals("Editorial_Assets — آرشیو.zip", restored.query)
        assertEquals(FileSortOption.OldestFirst, restored.sort)
    }

    @Test
    fun malformedSavedValuesFallBackToRecentFilesDefaults() {
        val restored = restoreFilesUiState(listOf("UnknownFilter", "yes", null, "not-a-sort"))

        assertEquals(FileTypeFilter.All, restored.filter)
        assertFalse(restored.searchOpen)
        assertEquals("", restored.query)
        assertEquals(FileSortOption.NewestFirst, restored.sort)
    }

    @Test
    fun searchToggleDoesNotClearQueryWhileSortCyclesNewestAndOldest() {
        val state = FilesUiState().apply { query = "Dune" }

        state.searchOpen = true
        state.searchOpen = false
        assertEquals("Dune", state.query)
        assertFalse(state.searchOpen)

        state.sort = if (state.sort == FileSortOption.NewestFirst) {
            FileSortOption.OldestFirst
        } else {
            FileSortOption.NewestFirst
        }
        assertEquals(FileSortOption.OldestFirst, state.sort)
        assertEquals("Sorted oldest first", state.sort.toast)

        state.sort = if (state.sort == FileSortOption.NewestFirst) {
            FileSortOption.OldestFirst
        } else {
            FileSortOption.NewestFirst
        }
        assertEquals(FileSortOption.NewestFirst, state.sort)
        assertEquals("Sorted newest first", state.sort.toast)
    }
}
