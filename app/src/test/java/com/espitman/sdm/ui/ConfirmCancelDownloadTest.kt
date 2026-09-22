package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmCancelDownloadTest {
    @Test
    fun dispatchesCancelThenClosesDialogAndReturnsToListWithoutToast() {
        val order = mutableListOf<String>()

        confirmCancelDownload(
            dispatchCancel = { order += "dispatch" },
            closeDialog = { order += "close" },
            returnToList = { order += "return" },
        )

        assertEquals(listOf("dispatch", "close", "return"), order)
    }
}
