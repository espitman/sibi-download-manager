package com.espitman.sdm.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationProgressTest {
    @Test
    fun unknownTotalIsIndeterminateWithDownloadedTextOnly() {
        val progress = TransferNotificationProgress.from(downloadedBytes = 1_500L, totalBytes = null)
        assertTrue(progress.indeterminate)
        assertEquals(100, progress.max)
        assertEquals(0, progress.percent)
        assertEquals("1.46 KB", progress.text)
    }

    @Test
    fun zeroOrNegativeTotalIsIndeterminate() {
        val zero = TransferNotificationProgress.from(downloadedBytes = 40L, totalBytes = 0L)
        assertTrue(zero.indeterminate)
        assertEquals(100, zero.max)
        assertEquals("40 B", zero.text)

        val negative = TransferNotificationProgress.from(downloadedBytes = 40L, totalBytes = -8L)
        assertTrue(negative.indeterminate)
        assertEquals(100, negative.max)
        assertEquals("40 B", negative.text)
    }

    @Test
    fun determinateUsesFloorPercentAndDownloadedOverTotalText() {
        val progress = TransferNotificationProgress.from(downloadedBytes = 1L, totalBytes = 3L)
        assertFalse(progress.indeterminate)
        assertEquals(100, progress.max)
        assertEquals(33, progress.percent)
        assertEquals("1 B / 3 B", progress.text)
    }

    @Test
    fun percentIsOverflowSafeAndClamped() {
        val overflow = TransferNotificationProgress.from(
            downloadedBytes = Long.MAX_VALUE / 2,
            totalBytes = Long.MAX_VALUE,
        )
        assertFalse(overflow.indeterminate)
        assertEquals(49, overflow.percent)
        assertEquals(100, overflow.max)

        val clamped = TransferNotificationProgress.from(
            downloadedBytes = 250L,
            totalBytes = 100L,
        )
        assertEquals(100, clamped.percent)
        assertEquals("250 B / 100 B", clamped.text)
    }
}
