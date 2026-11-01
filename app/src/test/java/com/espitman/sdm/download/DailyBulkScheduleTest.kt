package com.espitman.sdm.download

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyBulkScheduleTest {
    @Test fun nextOccurrenceIsTodayWhenStillUpcoming() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = LocalDateTime.of(2026, 9, 28, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 9, 28, 12, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, DailyBulkSchedule.nextOccurrence(12 * 60 + 30, now, zone))
    }

    @Test fun nextOccurrenceMovesToTomorrowAfterBoundary() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = LocalDateTime.of(2026, 9, 28, 12, 30).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 9, 29, 12, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, DailyBulkSchedule.nextOccurrence(12 * 60 + 30, now, zone))
    }
    @Test fun overnightResumeAndPauseUseIndependentNextDayBoundaries() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = LocalDateTime.of(2026, 10, 28, 23, 45).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 10, 29, 23, 0).atZone(zone).toInstant().toEpochMilli(), DailyBulkSchedule.nextOccurrence(23 * 60, now, zone))
        assertEquals(LocalDateTime.of(2026, 10, 29, 7, 0).atZone(zone).toInstant().toEpochMilli(), DailyBulkSchedule.nextOccurrence(7 * 60, now, zone))
    }
}
