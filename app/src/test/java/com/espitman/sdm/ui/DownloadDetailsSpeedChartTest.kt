package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDetailsSpeedChartTest {

    @Test
    fun emptyHistoryUsesUnavailableCaptionAndNoPoints() {
        val points = normalizeDownloadDetailsSpeedChartPoints(emptyList())
        assertTrue(points.isEmpty())
        assertEquals(
            "Unavailable",
            downloadDetailsSpeedChartCaption(
                samples = emptyList(),
                speedValue = "12.4",
                speedUnit = "MB/s",
            ),
        )
    }

    @Test
    fun retainedSamplesKeepCountAndUseCurrentSpeedCaption() {
        val samples = listOf(0L, 1_000L, 2_000L)
        val points = normalizeDownloadDetailsSpeedChartPoints(samples)
        assertEquals(samples.size, points.size)
        assertEquals(false, points.size == 16)
        assertEquals(false, points.size == 18)
        assertEquals(
            "Last 60 sec · 1.9 KB/s",
            downloadDetailsSpeedChartCaption(
                samples = samples,
                speedValue = "1.9",
                speedUnit = "KB/s",
            ),
        )
    }

    @Test
    fun zeroPeakSitsOnTheBaselineAndPeakMapsToTheTop() {
        val zeros = normalizeDownloadDetailsSpeedChartPoints(listOf(0L, 0L, 0L))
        assertEquals(listOf(1f, 1f, 1f), zeros.map { it.y })
        assertEquals(0f, zeros.first().x, 0.0001f)
        assertEquals(1f, zeros.last().x, 0.0001f)

        val peaked = normalizeDownloadDetailsSpeedChartPoints(listOf(0L, 50L, 100L))
        assertEquals(1f, peaked[0].y, 0.0001f)
        assertEquals(0.5f, peaked[1].y, 0.0001f)
        assertEquals(0f, peaked[2].y, 0.0001f)
        assertEquals(0f, peaked[0].x, 0.0001f)
        assertEquals(1f, peaked[2].x, 0.0001f)
    }

    @Test
    fun singleSampleDoesNotDivideByZeroAndOverflowPeakNormalizes() {
        val single = normalizeDownloadDetailsSpeedChartPoints(listOf(0L))
        assertEquals(1, single.size)
        assertEquals(0f, single[0].x, 0.0001f)
        assertEquals(1f, single[0].y, 0.0001f)

        val overflow = normalizeDownloadDetailsSpeedChartPoints(listOf(0L, Long.MAX_VALUE))
        assertEquals(2, overflow.size)
        assertEquals(1f, overflow[0].y, 0.0001f)
        assertEquals(0f, overflow[1].y, 0.0001f)
        assertEquals(0f, downloadDetailsSpeedChartYFraction(Long.MAX_VALUE, Long.MAX_VALUE), 0.0001f)
        assertEquals(1f, downloadDetailsSpeedChartYFraction(-1L, 10L), 0.0001f)
        assertEquals(1f, downloadDetailsSpeedChartYFraction(10L, 0L), 0.0001f)
    }
}
