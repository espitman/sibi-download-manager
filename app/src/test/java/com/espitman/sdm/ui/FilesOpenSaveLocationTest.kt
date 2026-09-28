package com.espitman.sdm.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesOpenSaveLocationTest {
    @Test
    fun appSpecificDefaultOpensTheInAppFolderView() {
        assertEquals(
            FilesOpenSaveLocationAction.ShowInAppFolder,
            resolveFilesOpenSaveLocationAction(null),
        )
        assertEquals(
            FilesOpenSaveLocationAction.ShowInAppFolder,
            resolveFilesOpenSaveLocationAction(""),
        )
        assertEquals(
            FilesOpenSaveLocationAction.ShowInAppFolder,
            resolveFilesOpenSaveLocationAction("   "),
        )
    }

    @Test
    fun selectedSafTreeShowsTheSharedFileManagerPicker() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        assertEquals(
            FilesOpenSaveLocationAction.ShowFileManagerPicker(tree),
            resolveFilesOpenSaveLocationAction("  $tree  "),
        )
    }

    @Test
    fun privateDefaultResetsFilesWithoutAToastOrFileManagerQuery() {
        val toasts = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        var inAppFolder = 0
        var discovered = 0

        performFilesOpenSaveLocation(
            treeUri = null,
            discover = {
                discovered += 1
                error("file managers must not be queried for the app-specific default")
            },
            onToast = { toasts += it },
            showInAppFolder = { inAppFolder += 1 },
            showPicker = { shown += it },
        )

        assertEquals(1, inAppFolder)
        assertTrue(toasts.isEmpty())
        assertTrue(shown.isEmpty())
        assertEquals(0, discovered)
    }

    @Test
    fun selectedSafTreeShowsTheSharedPickerWithoutNavigatingOrToasting() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val discovery = FileManagerDiscovery(
            folderIntent = Intent(),
            options = listOf(
                FileManagerOption(
                    SaveLocationFileManagers.ES_FILE_EXPLORER_PACKAGE,
                    SaveLocationFileManagers.ES_FILE_EXPLORER_ACTIVITY,
                    "ES File Explorer",
                ),
                FileManagerOption("com.android.documentsui", "files.FilesActivity", "Files"),
            ),
        )
        val toasts = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        val discovered = mutableListOf<String>()
        var inAppFolder = 0

        performFilesOpenSaveLocation(
            treeUri = tree,
            discover = { uri ->
                discovered += uri
                discovery
            },
            onToast = { toasts += it },
            showInAppFolder = { inAppFolder += 1 },
            showPicker = { shown += it },
        )

        assertEquals(listOf(tree), discovered)
        assertEquals(listOf(discovery), shown)
        assertEquals(0, inAppFolder)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun missingExternalManagerFallsBackToFilesWithATruthfulToast() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val toasts = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        var inAppFolder = 0

        performFilesOpenSaveLocation(
            treeUri = tree,
            discover = { null },
            onToast = { toasts += it },
            showInAppFolder = { inAppFolder += 1 },
            showPicker = { shown += it },
        )
        assertEquals(listOf(FILE_MANAGER_UNAVAILABLE_TOAST), toasts)
        assertEquals(1, inAppFolder)
        assertTrue(shown.isEmpty())

        toasts.clear()
        performFilesOpenSaveLocation(
            treeUri = tree,
            discover = { FileManagerDiscovery(Intent(), emptyList()) },
            onToast = { toasts += it },
            showInAppFolder = { inAppFolder += 1 },
            showPicker = { shown += it },
        )
        assertEquals(listOf(FILE_MANAGER_UNAVAILABLE_TOAST), toasts)
        assertEquals(2, inAppFolder)
        assertTrue(shown.isEmpty())
        assertTrue(toasts.none { it == FILE_MANAGER_PRIVATE_TOAST })
    }

    @Test
    fun prepareFilesFolderViewShowsAllFilesClearsSearchAndRefreshes() {
        val state = FilesUiState().apply {
            filter = FileTypeFilter.Archives
            searchOpen = true
            query = "clip"
            sort = FileSortOption.OldestFirst
            refreshEpoch = 4
            pendingRevealFileId = "keep-out-of-this-path"
            revealEpoch = 2
        }

        prepareFilesFolderView(state)

        assertEquals(FileTypeFilter.All, state.filter)
        assertEquals(false, state.searchOpen)
        assertEquals("", state.query)
        assertEquals(FileSortOption.OldestFirst, state.sort)
        assertEquals(5, state.refreshEpoch)
        assertEquals("keep-out-of-this-path", state.pendingRevealFileId)
        assertEquals(2, state.revealEpoch)

        prepareFilesFolderView(state)
        assertEquals(6, state.refreshEpoch)
        assertEquals(FileTypeFilter.All, state.filter)
        assertEquals("", state.query)
    }
}
