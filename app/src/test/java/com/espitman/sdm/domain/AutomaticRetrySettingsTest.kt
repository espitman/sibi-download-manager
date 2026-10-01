package com.espitman.sdm.domain

import org.junit.Assert.*
import org.junit.Test

class AutomaticRetrySettingsTest {
    private fun failed(error: String = "HTTP 503", count: Int = 0) = Download(url="https://example.com/f",fileName="f",createdAtEpochMillis=1000,
        updatedAtEpochMillis=2000,state=DownloadState.FAILED,error=error,automaticRetryCount=count,failedAtEpochMillis=2000)
    @Test fun budgetsAndDisableAreExact() {
        assertEquals(12000L,AutomaticRetrySettings(3,10).dueAt(failed(count=2)))
        assertNull(AutomaticRetrySettings(3,10).dueAt(failed(count=3)))
        assertNull(AutomaticRetrySettings(0,10).dueAt(failed()))
    }
    @Test fun onlyTransientErrorsAreEligible() {
        listOf("HTTP 404","HTTP 401","HTTP 403","HTTP 410","ENOSPC","SSLHandshakeException").forEach { assertNull(AutomaticRetrySettings().dueAt(failed(it))) }
        listOf("HTTP 408","HTTP 429","HTTP 503","timed out","Connection reset").forEach { assertNotNull(AutomaticRetrySettings().dueAt(failed(it))) }
    }
    @Test fun changingDelayUsesOriginalFailureTimeAfterOtherEdits() {
        val d=failed().copy(updatedAtEpochMillis=9000)
        assertEquals(7000L,AutomaticRetrySettings(2,5).dueAt(d))
        assertEquals(32000L,AutomaticRetrySettings(2,30).dueAt(d))
    }
    @Test fun manualPauseStopsDeadlineWithoutLosingProgress() {
        val paused=DownloadPauseMutation.apply(failed().copy(downloadedBytes=23),999,3000)
        assertEquals(DownloadState.PAUSED,paused.state);assertEquals(23L,paused.downloadedBytes)
        assertNull(paused.error);assertNull(AutomaticRetrySettings().dueAt(paused))
    }
    @Test fun boundsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { AutomaticRetrySettings(11,1) }
        assertThrows(IllegalArgumentException::class.java) { AutomaticRetrySettings(2,0) }
    }
}
