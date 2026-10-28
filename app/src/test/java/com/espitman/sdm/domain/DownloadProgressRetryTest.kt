package com.espitman.sdm.domain

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressRetryTest {
    private fun active() = Download(url="https://example.com/file",fileName="file",createdAtEpochMillis=1,
        state=DownloadState.DOWNLOADING,downloadedBytes=100,automaticRetryCount=5)

    @Test fun newBytesRestoreBudgetButReconnectingAndSameOffsetDoNot() {
        val same=DownloadProgressMutation.apply(active(),100,2)
        assertEquals(5,same.automaticRetryCount)
        val failed=DownloadStateMachine.transition(same,DownloadState.FAILED,3,"Connection reset")
        assertNull(AutomaticRetrySettings(5,1).dueAt(failed))
        val progressed=DownloadProgressMutation.apply(active(),101,2)
        assertEquals(0,progressed.automaticRetryCount)
        assertNotNull(AutomaticRetrySettings(5,1).dueAt(DownloadStateMachine.transition(progressed,DownloadState.FAILED,3,"Connection reset")))
    }

    @Test fun intermittentFailuresWithProgressCanRecoverMoreThanFiveTimes() {
        var record=active().copy(automaticRetryCount=0)
        var time=2L
        repeat(12) {
            record=DownloadStateMachine.transition(record,DownloadState.FAILED,time++,"Connection reset")
            assertNotNull(AutomaticRetrySettings(5,1).dueAt(record))
            record=DownloadRetryFailedMutation.apply(record,true,time++)!!
            assertEquals(1,record.automaticRetryCount)
            record=DownloadStateMachine.transition(record,DownloadState.CONNECTING,time++)
            record=DownloadStateMachine.transition(record,DownloadState.DOWNLOADING,time++)
            assertEquals(1,record.automaticRetryCount)
            record=DownloadProgressMutation.apply(record,record.downloadedBytes+10,time++)
            assertEquals(0,record.automaticRetryCount)
        }
        assertEquals(220L,record.downloadedBytes)
    }

    @Test fun completionAlsoClearsCounterAndInvalidProgressCannotResetIt() {
        assertEquals(0,DownloadStateMachine.transition(active(),DownloadState.COMPLETED,2).automaticRetryCount)
        assertThrows(IllegalArgumentException::class.java){DownloadProgressMutation.apply(active(),99,2)}
        assertThrows(IllegalArgumentException::class.java){DownloadProgressMutation.apply(active().copy(state=DownloadState.CONNECTING),101,2)}
    }
}
