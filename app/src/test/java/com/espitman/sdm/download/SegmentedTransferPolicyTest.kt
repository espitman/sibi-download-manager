package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentedTransferPolicyTest {
    @Test
    fun eligibleFreshKnownRangeDownloadIsPartitionedWithoutGaps() {
        val total = SegmentedTransferPolicy.MIN_SEGMENTED_SIZE_BYTES + 1
        val plan = SegmentedTransferPolicy.plan(download(total), resumeOffset = 0)!!
        assertEquals(2, plan.size)
        assertEquals(0L, plan.first().start)
        assertEquals(total - 1, plan.last().endInclusive)
        assertEquals(plan.first().endInclusive + 1, plan.last().start)
        assertEquals(total, plan.sumOf { it.length })
    }

    @Test
    fun ineligibleInputsFallBackToSingleConnection() {
        val minimum = SegmentedTransferPolicy.MIN_SEGMENTED_SIZE_BYTES
        assertNull(SegmentedTransferPolicy.plan(download(minimum).copy(acceptsRanges = false), 0))
        assertNull(SegmentedTransferPolicy.plan(download(minimum).copy(totalBytes = null), 0))
        assertNull(SegmentedTransferPolicy.plan(download(minimum - 1), 0))
        assertNull(SegmentedTransferPolicy.plan(download(minimum), 1))
        assertNull(SegmentedTransferPolicy.plan(download(minimum).copy(etag = null), 0))
    }

    @Test
    fun lastModifiedIsAValidFallbackValidator() {
        val download = download(SegmentedTransferPolicy.MIN_SEGMENTED_SIZE_BYTES)
            .copy(etag = null, lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        assertEquals(2, SegmentedTransferPolicy.plan(download, 0)!!.size)
    }

    @Test
    fun selectedConnectionsAreHonoredWithinFileSizeAndHardResourceBounds() {
        val fourMiB = 4L * 1024 * 1024
        assertEquals(8, SegmentedTransferPolicy.plan(download(fourMiB), 0, segmentCount = 8)!!.size)
        assertEquals(16, SegmentedTransferPolicy.plan(download(fourMiB), 0, segmentCount = 16)!!.size)
        assertEquals(16, SegmentedTransferPolicy.plan(download(fourMiB), 0, segmentCount = 32)!!.size)
        assertEquals(4, SegmentedTransferPolicy.plan(download(1024L * 1024), 0, segmentCount = 32)!!.size)
    }

    @Test
    fun temporaryStorageBudgetIncludesLargestSegmentDuringMerge() {
        val total = 4L * 1024 * 1024
        val ranges = SegmentedTransferPolicy.plan(download(total), 0, segmentCount = 8)!!
        assertEquals(total + 512L * 1024, SegmentedTransferPolicy.requiredLocalBytes(total, ranges))
    }

    private fun download(total: Long) = Download(
        id = "segmented",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        etag = "\"v1\"",
        totalBytes = total,
        acceptsRanges = true,
        createdAtEpochMillis = 1,
    )
}
