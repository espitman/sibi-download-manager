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
    const val INITIAL_SEGMENT_COUNT = 2

    fun plan(
        download: Download,
        resumeOffset: Long,
        segmentCount: Int = INITIAL_SEGMENT_COUNT,
    ): List<TransferByteRange>? {
        if (resumeOffset != 0L || download.acceptsRanges != true) return null
        val total = download.totalBytes ?: return null
        if (total < MIN_SEGMENTED_SIZE_BYTES || segmentCount < 2 || total < segmentCount) return null
        val validators = HttpRangeResume.ResumeValidators(download.etag, download.lastModified)
        if (HttpRangeResume.ifRangeHeaderValue(validators) == null) return null

        val base = total / segmentCount
        val remainder = total % segmentCount
        var start = 0L
        return List(segmentCount) { index ->
            val length = base + if (index < remainder) 1L else 0L
            TransferByteRange(start, start + length - 1L).also { start += length }
        }
    }
}
