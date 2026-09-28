package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadSchedule
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class BootScheduleRecoveryTest {
    @Test fun futureScheduleRearmsAtStartAfterBoot() {
        val schedule = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = 20_000L)
        assertEquals(20_000L, BootScheduleRecovery.nextTick(listOf(record("future", DownloadState.QUEUED, schedule)), 10_000L))
    }

    @Test fun alreadyOpenScheduleGetsNearTermWakeupButManualPauseDoesNot() {
        val schedule = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = 5_000L)
        val scheduled = record("scheduled", DownloadState.PAUSED, schedule, DownloadPauseCause.SCHEDULE)
        val manual = record("manual", DownloadState.PAUSED, schedule)
        assertEquals(15_000L, BootScheduleRecovery.nextTick(listOf(scheduled), 10_000L))
        assertEquals(null, BootScheduleRecovery.nextTick(listOf(manual), 10_000L))
    }

    @Test fun ordinaryQueuedDownloadRearmsWithoutStartingFromBootReceiver() {
        assertEquals(15_000L, BootScheduleRecovery.nextTick(listOf(record("queued", DownloadState.QUEUED, null)), 10_000L))
    }

    private fun record(
        id: String,
        state: DownloadState,
        schedule: DownloadSchedule?,
        pauseCause: DownloadPauseCause? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = id,
        state = state,
        createdAtEpochMillis = 1L,
        schedule = schedule,
        pauseCause = pauseCause,
    )
}
