package com.espitman.sdm.web

import org.junit.Assert.*
import org.junit.Test

class WebFileRangeTest {
    @Test fun fullOpenEndedSuffixAndClampedRanges() {
        assertEquals(WebFileRange(0, 99), WebFileRange.parse(null, 100))
        assertEquals(WebFileRange(20, 99), WebFileRange.parse("bytes=20-", 100))
        assertEquals(WebFileRange(80, 99), WebFileRange.parse("bytes=-20", 100))
        assertEquals(WebFileRange(0, 99), WebFileRange.parse("bytes=-200", 100))
        assertEquals(WebFileRange(20, 99), WebFileRange.parse("bytes=20-200", 100))
        assertEquals(0, WebFileRange.parse(null, 0)!!.length)
    }
    @Test fun rejectsOverflowMultiRangeAndInvalidOrUnsatisfiableRequests() {
        listOf("bytes=100-", "bytes=20-19", "bytes=-0", "bytes=-", "bytes=0-1,3-4", "bytes=999999999999999999999-", "items=0-1", "bytes=-1-2").forEach {
            assertNull(it, WebFileRange.parse(it, 100))
        }
        assertNull(WebFileRange.parse("bytes=0-", 0))
    }
}
