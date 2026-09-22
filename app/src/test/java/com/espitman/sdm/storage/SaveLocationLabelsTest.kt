package com.espitman.sdm.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLocationLabelsTest {
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSDM"

    @Test
    fun prefersQueriedDisplayName() {
        assertEquals("Movies", SaveLocationLabels.fromTree(treeUri, "Movies"))
        assertEquals("Movies", SaveLocationLabels.fromTree(treeUri, "  Movies  "))
    }

    @Test
    fun derivesStablePathLabelFromTreeDocumentId() {
        assertEquals("/Download/SDM", SaveLocationLabels.fromTree(treeUri, null))
        assertEquals(
            "/Download",
            SaveLocationLabels.fromTree(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                " ",
            ),
        )
    }

    @Test
    fun fallsBackWhenTheUriIsNotATree() {
        assertEquals(
            SaveLocationLabels.SELECTED_FOLDER_FALLBACK,
            SaveLocationLabels.fromTree("content://downloads/document/1", null),
        )
    }
}
