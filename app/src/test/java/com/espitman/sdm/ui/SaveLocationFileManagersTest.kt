package com.espitman.sdm.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLocationFileManagersTest {
    @Test
    fun documentsUiFilesActivitiesAreAcceptedAndPickersAreRejected() {
        assertTrue(isDocumentsUiFileManager("com.android.documentsui", "com.android.documentsui.files.FilesActivity"))
        assertTrue(isDocumentsUiFileManager("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity"))
        assertEquals(
            false,
            isDocumentsUiFileManager("com.android.documentsui", "com.android.documentsui.picker.PickActivity"),
        )
        assertEquals(
            false,
            isDocumentsUiFileManager("com.android.documentsui", "com.android.documentsui.UninstallerActivity"),
        )
        assertEquals(
            false,
            isDocumentsUiFileManager("com.estrongs.android.pop", "com.estrongs.android.pop.view.FileExplorerActivity"),
        )
    }

    @Test
    fun assembledOptionsPutEsFileExplorerFirstThenSystemFiles() {
        val es = FileManagerOption(
            packageName = SaveLocationFileManagers.ES_FILE_EXPLORER_PACKAGE,
            activityName = SaveLocationFileManagers.ES_FILE_EXPLORER_ACTIVITY,
            label = "ES File Explorer",
        )
        val later = FileManagerOption("com.android.documentsui", "files.FilesActivity", "Zebra Files")
        val earlier = FileManagerOption("com.google.android.documentsui", "files.FilesActivity", "Files")
        val duplicate = FileManagerOption("com.android.documentsui", "files.FilesActivity", "Zebra Files")

        val options = assembleFileManagerOptions(es, listOf(later, earlier, duplicate))

        assertEquals(
            listOf("ES File Explorer", "Files", "Zebra Files"),
            options.map { it.label },
        )
    }

    @Test
    fun pickerOutcomeMatchesTheExistingFilesHeaderRules() {
        assertEquals(FileManagerPickerOutcome.Unavailable, fileManagerPickerOutcome(null))
        assertEquals(
            FileManagerPickerOutcome.PrivateFolder,
            fileManagerPickerOutcome(FileManagerDiscovery(Intent(), emptyList())),
        )

        val discovery = FileManagerDiscovery(
            Intent(),
            listOf(FileManagerOption("com.android.documentsui", "files.FilesActivity", "Files")),
        )
        assertEquals(FileManagerPickerOutcome.Ready(discovery), fileManagerPickerOutcome(discovery))
    }

    @Test
    fun presentPickerShowsManagersOrReusesTheExistingToasts() {
        val toasts = mutableListOf<String>()
        val shown = mutableListOf<FileManagerDiscovery>()
        val discovery = FileManagerDiscovery(
            Intent(),
            listOf(FileManagerOption("com.android.documentsui", "files.FilesActivity", "Files")),
        )

        presentFileManagerPicker(FileManagerPickerOutcome.Unavailable, shown::add, toasts::add)
        presentFileManagerPicker(FileManagerPickerOutcome.PrivateFolder, shown::add, toasts::add)
        presentFileManagerPicker(FileManagerPickerOutcome.Ready(discovery), shown::add, toasts::add)

        assertEquals(
            listOf(FILE_MANAGER_UNAVAILABLE_TOAST, FILE_MANAGER_PRIVATE_TOAST),
            toasts,
        )
        assertEquals(listOf(discovery), shown)
    }
}
