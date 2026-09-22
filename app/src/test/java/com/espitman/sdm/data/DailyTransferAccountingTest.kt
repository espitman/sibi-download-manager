package com.espitman.sdm.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DailyTransferAccountingTest {
    private val zone = ZoneOffset.ofHours(3)

    @Test
    fun dayKeyUsesIsoLocalDateAndStaysOnTheSameCalendarDayUntilMidnight() {
        val dayStart = LocalDate.of(2023, 11, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val justBeforeNext = LocalDate.of(2023, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val nextMidnight = LocalDate.of(2023, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals("2023-11-14", DailyTransferAccounting.dayKey(dayStart, zone))
        assertEquals("2023-11-14", DailyTransferAccounting.dayKey(justBeforeNext, zone))
        assertEquals("2023-11-15", DailyTransferAccounting.dayKey(nextMidnight, zone))
        assertEquals(
            "2023-11-13",
            DailyTransferAccounting.dayKey(dayStart - 1L, zone),
        )
    }

    @Test
    fun positiveDeltaCountsOnlyIncreasesAndIgnoresRepeatsOrRewinds() {
        assertEquals(0L, DailyTransferAccounting.positiveDelta(0L, 0L))
        assertEquals(40L, DailyTransferAccounting.positiveDelta(10L, 50L))
        assertEquals(0L, DailyTransferAccounting.positiveDelta(50L, 50L))
        assertEquals(0L, DailyTransferAccounting.positiveDelta(50L, 20L))
        assertEquals(0L, DailyTransferAccounting.positiveDelta(100L, 0L))
        assertEquals(Long.MAX_VALUE, DailyTransferAccounting.positiveDelta(0L, Long.MAX_VALUE))
        assertEquals(1L, DailyTransferAccounting.positiveDelta(Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test
    fun saturatingAddStopsAtLongMaxValue() {
        assertEquals(7L, DailyTransferAccounting.saturatingAdd(3L, 4L))
        assertEquals(Long.MAX_VALUE, DailyTransferAccounting.saturatingAdd(Long.MAX_VALUE - 1L, 2L))
        assertEquals(Long.MAX_VALUE, DailyTransferAccounting.saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, DailyTransferAccounting.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, DailyTransferAccounting.saturatingAdd(0L, Long.MAX_VALUE))
    }
}
