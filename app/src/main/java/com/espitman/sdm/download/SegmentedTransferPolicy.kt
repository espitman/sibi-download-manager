package com.espitman.sdm.download

import com.espitman.sdm.domain.Download

data class TransferByteRange(val start: Long, val endInclusive: Long) {
    init {
        require(start >= 0L)
        require(endInclusive >= start)
    }

    val length: Long get() = endInclusive - start + 1L
    fun headerValue(): String = "bytes=$start-$endInclusive"
}

object SegmentedTransferPolicy {
    const val MIN_SEGMENTED_SIZE_BYTES = 1024L * 1024L
    const val MIN_SEGMENT_SIZE_BYTES = 256L * 1024L
    const val MAX_SEGMENT_COUNT = 32
    const val INITIAL_SEGMENT_COUNT = 2

    fun plan(
        download: Download,
        resumeOffset: Long,
        segmentCount: Int = INITIAL_SEGMENT_COUNT,
    ): List<TransferByteRange>? {
        if (resumeOffset != 0L || download.acceptsRanges != true) return null
        val total = download.totalBytes ?: return null
        if (total < MIN_SEGMENTED_SIZE_BYTES || segmentCount < 2) return null
        val validators = HttpRangeResume.ResumeValidators(download.etag, download.lastModified)
        if (HttpRangeResume.ifRangeHeaderValue(validators) == null) return null

        val sizeBoundCount = (total / MIN_SEGMENT_SIZE_BYTES).coerceAtLeast(2L).toInt()
        val effectiveCount = minOf(segmentCount, MAX_SEGMENT_COUNT, sizeBoundCount)
        val base = total / effectiveCount
        val remainder = total % effectiveCount
        var start = 0L
        return List(effectiveCount) { index ->
            val length = base + if (index < remainder) 1L else 0L
            TransferByteRange(start, start + length - 1L).also { start += length }
        }
    }

    fun requiredLocalBytes(totalBytes: Long, ranges: List<TransferByteRange>): Long {
        val largest = ranges.maxOfOrNull { it.length } ?: 0L
        return if (totalBytes > Long.MAX_VALUE - largest) Long.MAX_VALUE else totalBytes + largest
    }
}
