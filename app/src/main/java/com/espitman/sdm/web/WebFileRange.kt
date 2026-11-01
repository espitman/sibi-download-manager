package com.espitman.sdm.web

internal data class WebFileRange(val start: Long, val end: Long) {
    val length: Long get() = (end - start + 1).coerceAtLeast(0)
    companion object {
        fun parse(header: String?, size: Long): WebFileRange? {
            require(size >= 0)
            if (header == null) return WebFileRange(0, size - 1)
            if (size == 0L) return null
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header) ?: return null
            val (first, last) = match.destructured
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return WebFileRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val start = first.toLongOrNull()?.takeIf { it < size } ?: return null
            val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            if (end < start) return null
            return WebFileRange(start, end)
        }
    }
}
