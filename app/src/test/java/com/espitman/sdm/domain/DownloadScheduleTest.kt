package com.espitman.sdm.domain

import com.espitman.sdm.download.DownloadQueuePolicy
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadScheduleTest {
    @Test fun oneTimeWindowStartsAndEndsAtExactInstants() {
        val schedule = DownloadSchedule(
            DownloadSchedule.Kind.ONCE,
            startEpochMillis = 10_000,
            endEpochMillis = 20_000,
        )
        assertFalse(schedule.isOpen(9_999))
        assertTrue(schedule.isOpen(10_000))
        assertTrue(schedule.isOpen(19_999))
        assertFalse(schedule.isOpen(20_000))
        assertEquals(10_000L, schedule.nextBoundary(9_999))
        assertEquals(20_000L, schedule.nextBoundary(10_000))
        assertEquals(null, schedule.nextBoundary(20_000))
    }

    @Test fun overnightDailyWindowUsesPersistedZoneAcrossDeviceZoneChanges() {
        val zone = ZoneId.of("Asia/Tehran")
        val schedule = DownloadSchedule(
            DownloadSchedule.Kind.DAILY,
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 2 * 60,
            zoneId = zone.id,
        )
        fun at(day: Int, hour: Int, minute: Int = 0) =
            LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        assertFalse(schedule.isOpen(at(28, 22, 59)))
        assertTrue(schedule.isOpen(at(28, 23)))
        assertTrue(schedule.isOpen(at(29, 1, 59)))
        assertFalse(schedule.isOpen(at(29, 2)))
        assertEquals(at(28, 23), schedule.nextBoundary(at(28, 22, 59)))
        assertEquals(at(29, 2), schedule.nextBoundary(at(28, 23)))
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertTrue(schedule.isOpen(at(29, 1, 59)))
            assertEquals(at(29, 2), schedule.nextBoundary(at(28, 23)))
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun scheduleGateDoesNotConsumeQueueSlotsOrChangeManualPause() {
        val now = 10_000L
        val later = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = 20_000)
        val queuedLater = record("later", DownloadState.QUEUED, later)
        val queuedNow = record("now", DownloadState.QUEUED, null)
        val manualPause = record("manual", DownloadState.PAUSED, null)
        val selected = DownloadQueuePolicy.select(
            listOf(queuedLater, queuedNow, manualPause),
            maxConcurrent = 1,
            nowEpochMillis = now,
        )
        assertEquals(listOf("now"), selected.map { it.id })
        assertEquals(DownloadState.PAUSED, manualPause.state)
    }

    private fun record(id: String, state: DownloadState, schedule: DownloadSchedule?) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = id,
        state = state,
        createdAtEpochMillis = 1L,
        schedule = schedule,
    )
}
