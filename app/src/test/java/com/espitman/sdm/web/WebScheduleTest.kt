package com.espitman.sdm.web

import com.espitman.sdm.domain.DownloadSchedule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebScheduleTest {
    @Test fun roundTripsOnceDailyAndNoSchedule() {
        val once = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = 1000, endEpochMillis = 2000)
        val daily = DownloadSchedule(DownloadSchedule.Kind.DAILY, startMinuteOfDay = 1380, endMinuteOfDay = 60, zoneId = "Asia/Tehran")
        listOf(once, daily, null).forEach { assertEquals(it, WebSchedule.decode(WebSchedule.encode(it))) }
    }
    @Test fun rejectsFractionalTimesInvalidWindowsAndUnknownFields() {
        val cases = listOf(
            JSONObject().put("kind", "ONCE").put("start", 1.5),
            JSONObject().put("kind", "ONCE").put("start", 1000).put("end", 999),
            JSONObject().put("kind", "ONCE").put("start", 1000).put("path", "/secret"),
            JSONObject().put("kind", "DAILY").put("startMinute", 1440).put("endMinute", 60).put("zone", "UTC"),
            JSONObject().put("kind", "DAILY").put("startMinute", 60).put("endMinute", 60).put("zone", "UTC"),
        )
        cases.forEach { try { WebSchedule.decode(it); fail("Accepted $it") } catch (_: IllegalArgumentException) {} }
    }
    @Test fun phoneLocalTimeUsesPhoneZoneAndRejectsInvalidCalendarDates() {
        val input = JSONObject().put("kind", "ONCE").put("startLocal", "2026-10-02T13:30").put("zone", "Asia/Tehran")
        assertEquals(java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(), WebSchedule.decode(input)?.startEpochMillis)
        for (invalid in listOf(input.toString().replace("10-02", "02-30"), input.toString().replace("Asia/Tehran", "Invalid/Zone"))) {
            try { WebSchedule.decode(JSONObject(invalid)); fail("Accepted invalid calendar/zone") } catch (_: IllegalArgumentException) {}
        }
    }
}
