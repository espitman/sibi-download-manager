package com.espitman.sdm.ui

import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferCardActionTest {
    @Test
    fun onlyPausedResumesAndOnlyActiveTransfersPause() {
        assertEquals(TransferCardAction.Resume, transferCardAction(DownloadState.PAUSED))
        assertEquals(TransferCardAction.Pause, transferCardAction(DownloadState.CONNECTING))
        assertEquals(TransferCardAction.Pause, transferCardAction(DownloadState.DOWNLOADING))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.QUEUED))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.FAILED))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.COMPLETED))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.CANCELLED))
    }

    @Test
    fun priorityToastsMatchTheApprovedDetailsCopy() {
        assertEquals("High priority enabled", priorityToggleToast(true))
        assertEquals("Priority returned to normal", priorityToggleToast(false))
    }
}
