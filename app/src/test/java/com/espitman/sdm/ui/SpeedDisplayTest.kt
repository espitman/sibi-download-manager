package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedDisplayTest {
    @Test
    fun zeroIdleUsesWholeMegabytes() {
        assertSpeed(0L, value = "0", unit = "MB/s")
    }

    @Test
    fun positiveSubKilobyteAvoidsZeroKilobytes() {
        assertSpeed(500L, value = "<1", unit = "KB/s")
    }

    @Test
    fun midRangeUsesWholeDecimalKilobytes() {
        assertSpeed(500_000L, value = "500", unit = "KB/s")
        assertSpeed(999_999L, value = "999", unit = "KB/s")
    }

    @Test
    fun megabyteThresholdAndAboveUseWholeMegabytes() {
        assertSpeed(1_000_000L, value = "1", unit = "MB/s")
        assertSpeed(2_000_000L, value = "2", unit = "MB/s")
        assertSpeed(13_001_523L, value = "13", unit = "MB/s")
    }

    @Test
    fun formattersHideZeroAndSharePositiveLabels() {
        assertEquals("—", formatDownloadSpeed(0L))
        assertEquals("—", formatCardSpeed(0L))
        assertEquals("<1 KB/s", formatDownloadSpeed(500L))
        assertEquals("<1 KB/s", formatCardSpeed(500L))
        assertEquals("500 KB/s", formatDownloadSpeed(500_000L))
        assertEquals("999 KB/s", formatCardSpeed(999_999L))
        assertEquals("1 MB/s", formatDownloadSpeed(1_000_000L))
        assertEquals("2 MB/s", formatCardSpeed(2_000_000L))
    }

    @Test
    fun detailsAndStatusSplitTheSameValueAndUnit() {
        assertEquals("<1", splitSpeedValue(500L))
        assertEquals("KB/s", splitSpeedUnit(500L))
        assertEquals("500", splitSpeedValue(500_000L))
        assertEquals("KB/s", splitSpeedUnit(500_000L))
        assertEquals("0", splitSpeedValue(0L))
        assertEquals("MB/s", splitSpeedUnit(0L))
        assertEquals("1", splitSpeedValue(1_000_000L))
        assertEquals("MB/s", splitSpeedUnit(1_000_000L))
    }

    private fun assertSpeed(bytesPerSecond: Long, value: String, unit: String) {
        val display = decimalSpeedDisplay(bytesPerSecond)
        assertEquals(value, display.value)
        assertEquals(unit, display.unit)
        assertEquals("$value $unit", display.formatted)
    }
}
