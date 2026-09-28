package com.espitman.sdm.ui

import android.content.Intent
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsOpenFolderTest {
    @Test
    fun appSpecificCompletedRevealsTheFileInFiles() {
        val download = record(
            id = "local-done",
            destinationPath = "/data/user/0/com.espitman.sdm/files/Downloads/clip.bin",
            state = DownloadState.COMPLETED,
            completedAt = 2_000L,
        )

        assertEquals(
            DetailsOpenFolderAction.RevealInFiles("local-done"),
            resolveDetailsOpenFolderAction(download),
        )
    }

    @Test
    fun appSpecificIncompleteStillOpensTheInAppFolderView() {
        val download = record(
            id = "local-active",
            destinationPath = "/data/user/0/com.espitman.sdm/files/Downloads/clip.bin",
            state = DownloadState.DOWNLOADING,
            downloadedBytes = 250L,
        )

        assertEquals(
            DetailsOpenFolderAction.RevealInFiles("local-active"),
            resolveDetailsOpenFolderAction(download),
        )
    }

    @Test
    fun userSelectedTreeShowsTheSharedFileManagerPicker() {
        val download = record(
            id = "saf-done",
            destinationPath = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclip.bin",
            state = DownloadState.COMPLETED,
            completedAt = 2_000L,
        ).copy(
            destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            destinationDisplayLabel = "Download",
        )

        assertEquals(
            DetailsOpenFolderAction.ShowFileManagerPicker(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ),
            resolveDetailsOpenFolderAction(download),
        )
    }

    @Test
    fun userSelectedTreeForAnInProgressDownloadStillUsesThePicker() {
        val download = record(
            id = "saf-active",
            destinationPath = "/data/user/0/com.espitman.sdm/files/Downloads/clip.bin",
            state = DownloadState.PAUSED,
            downloadedBytes = 250L,
        ).copy(destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload")

        assertEquals(
            DetailsOpenFolderAction.ShowFileManagerPicker(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ),
            resolveDetailsOpenFolderAction(download),
        )
    }

    @Test
    fun missingDestinationIsUnavailable() {
        val download = record(
            id = "no-dest",
            destinationPath = null,
            state = DownloadState.QUEUED,
            downloadedBytes = 0L,
            startedAt = null,
        )

        assertEquals(
            DetailsOpenFolderAction.Message("Destination folder unavailable"),
            resolveDetailsOpenFolderAction(download),
        )
    }

    @Test
    fun performRevealNavigatesWithoutAskingForAFileManager() {
        val download = record(
            id = "local-done",
            destinationPath = "/Download/SDM/clip.bin",
            state = DownloadState.COMPLETED,
            completedAt = 2_000L,
        )
        val toasts = mutableListOf<String>()
        val revealed = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        var discovered = 0

        performDetailsOpenFolder(
            download = download,
            discover = {
                discovered += 1
                error("file managers must not be queried for app-specific destinations")
            },
            onToast = { toasts += it },
            onRevealFileInFiles = { revealed += it },
            showPicker = { shown += it },
        )

        assertEquals(listOf("Opening /Download/SDM"), toasts)
        assertEquals(listOf("local-done"), revealed)
        assertTrue(shown.isEmpty())
        assertEquals(0, discovered)
    }

    @Test
    fun performSafShowsTheSharedPickerAndDoesNotNavigateToFiles() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val download = record(
            id = "saf-done",
            destinationPath = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclip.bin",
            state = DownloadState.COMPLETED,
            completedAt = 2_000L,
        ).copy(destinationTreeUri = tree, destinationDisplayLabel = "Download")
        val discovery = FileManagerDiscovery(
            folderIntent = Intent(),
            options = listOf(
                FileManagerOption("com.estrongs.android.pop", "es.Activity", "ES File Explorer"),
                FileManagerOption("com.android.documentsui", "files.FilesActivity", "Files"),
            ),
        )
        val toasts = mutableListOf<String>()
        val revealed = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        val discovered = mutableListOf<String>()

        performDetailsOpenFolder(
            download = download,
            discover = { uri ->
                discovered += uri
                discovery
            },
            onToast = { toasts += it },
            onRevealFileInFiles = { revealed += it },
            showPicker = { shown += it },
        )

        assertEquals(listOf(tree), discovered)
        assertEquals(listOf(discovery), shown)
        assertTrue(toasts.isEmpty())
        assertTrue(revealed.isEmpty())
    }

    @Test
    fun performSafReusesTheFilesPickerToastsWhenNoManagerIsAvailable() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val download = record(
            id = "saf-done",
            destinationPath = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fclip.bin",
            state = DownloadState.COMPLETED,
            completedAt = 2_000L,
        ).copy(destinationTreeUri = tree)
        val toasts = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()

        performDetailsOpenFolder(
            download = download,
            discover = { null },
            onToast = { toasts += it },
            onRevealFileInFiles = { error("must not navigate to Files for a SAF destination") },
            showPicker = { shown += it },
        )
        assertEquals(listOf(FILE_MANAGER_UNAVAILABLE_TOAST), toasts)
        assertTrue(shown.isEmpty())

        toasts.clear()
        performDetailsOpenFolder(
            download = download,
            discover = { FileManagerDiscovery(Intent(), emptyList()) },
            onToast = { toasts += it },
            onRevealFileInFiles = { error("must not navigate to Files for a SAF destination") },
            showPicker = { shown += it },
        )
        assertEquals(listOf(FILE_MANAGER_PRIVATE_TOAST), toasts)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun prepareFilesRevealClearsSearchAndSelectsTheFileWithoutChangingSort() {
        val state = FilesUiState().apply {
            filter = FileTypeFilter.Archives
            searchOpen = true
            query = "clip"
            sort = FileSortOption.OldestFirst
        }

        prepareFilesReveal(state, "local-done")

        assertEquals(FileTypeFilter.All, state.filter)
        assertEquals(false, state.searchOpen)
        assertEquals("", state.query)
        assertEquals(FileSortOption.OldestFirst, state.sort)
        assertEquals("local-done", state.pendingRevealFileId)
        assertEquals(1, state.revealEpoch)

        prepareFilesReveal(state, "local-done")
        assertEquals(2, state.revealEpoch)
        assertEquals("local-done", state.pendingRevealFileId)
    }

    @Test
    fun filesRevealListIndexAccountsForTheFixedFilesHeaders() {
        assertEquals(3, filesRevealListIndex(searchOpen = false, fileIndex = 0))
        assertEquals(5, filesRevealListIndex(searchOpen = false, fileIndex = 2))
        assertEquals(4, filesRevealListIndex(searchOpen = true, fileIndex = 0))
    }

    private fun record(
        id: String,
        destinationPath: String?,
        state: DownloadState,
        downloadedBytes: Long = 1_000L,
        startedAt: Long? = 1_000L,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/clip.bin",
        fileName = "clip.bin",
        destinationPath = destinationPath,
        totalBytes = 1_000L,
        downloadedBytes = downloadedBytes,
        state = state,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
    )
}
