package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangeResumeTest {

    @Test
    fun rangeHeaderUsesActualPartLength() {
        assertEquals("bytes=0-", HttpRangeResume.rangeHeaderValue(0L))
        assertEquals("bytes=4096-", HttpRangeResume.rangeHeaderValue(4096L))
    }

    @Test
    fun ifRangePrefersStrongEtagOverLastModified() {
        val stored = HttpRangeResume.ResumeValidators(
            etag = "\"file-v1\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )
        assertEquals("\"file-v1\"", HttpRangeResume.ifRangeHeaderValue(stored))
        assertEquals(
            mapOf(
                HttpRangeResume.HEADER_RANGE to "bytes=128-",
                HttpRangeResume.HEADER_IF_RANGE to "\"file-v1\"",
            ),
            HttpRangeResume.requestHeaders(128L, stored),
        )
    }

    @Test
    fun ifRangeFallsBackToLastModifiedWhenEtagIsWeakOrMissing() {
        val weak = HttpRangeResume.ResumeValidators(
            etag = "W/\"file-v1\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", HttpRangeResume.ifRangeHeaderValue(weak))
        assertFalse(HttpRangeResume.isStrongEtag("W/\"file-v1\""))

        val lastModifiedOnly = HttpRangeResume.ResumeValidators(
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            HttpRangeResume.ifRangeHeaderValue(lastModifiedOnly),
        )

        val none = HttpRangeResume.ResumeValidators()
        assertNull(HttpRangeResume.ifRangeHeaderValue(none))
        assertEquals(
            mapOf(HttpRangeResume.HEADER_RANGE to "bytes=10-"),
            HttpRangeResume.requestHeaders(10L, none),
        )
    }

    @Test
    fun validateContentRangeAcceptsMatchingStartAndTotal() {
        val parsed = HttpRangeResume.validateContentRange(
            header = "bytes 1024-2047/4096",
            expectedOffset = 1024L,
            knownTotal = 4096L,
        )
        assertEquals(1024L, parsed.start)
        assertEquals(2047L, parsed.end)
        assertEquals(4096L, parsed.total)
        assertEquals(1024L, parsed.inclusiveLength)
    }

    @Test
    fun validateContentRangeRejectsStartOrTotalMismatch() {
        val startMismatch = assertThrows(IllegalArgumentException::class.java) {
            HttpRangeResume.validateContentRange(
                header = "bytes 0-1023/4096",
                expectedOffset = 1024L,
                knownTotal = 4096L,
            )
        }
        assertTrue(startMismatch.message!!.contains("start"))

        val totalMismatch = assertThrows(IllegalArgumentException::class.java) {
            HttpRangeResume.validateContentRange(
                header = "bytes 1024-2047/8192",
                expectedOffset = 1024L,
                knownTotal = 4096L,
            )
        }
        assertTrue(totalMismatch.message!!.contains("total"))

        val unknownTotal = assertThrows(IllegalArgumentException::class.java) {
            HttpRangeResume.validateContentRange(
                header = "bytes 1024-2047/*",
                expectedOffset = 1024L,
                knownTotal = 4096L,
            )
        }
        assertTrue(unknownTotal.message!!.contains("unknown"))
    }

    @Test
    fun storedStrongEtagMustMatchStrongResponseEtag() {
        val stored = HttpRangeResume.ResumeValidators(etag = "\"abc\"")
        assertEquals(
            HttpRangeResume.ResumeValidation.Ok,
            HttpRangeResume.validateStoredValidators(stored, "\"abc\"", "ignored"),
        )
        val missing = HttpRangeResume.validateStoredValidators(stored, null, "Wed, 21 Oct 2015 07:28:00 GMT")
        assertTrue(missing is HttpRangeResume.ResumeValidation.Failed)
        val weakResponse = HttpRangeResume.validateStoredValidators(stored, "W/\"abc\"", null)
        assertTrue(weakResponse is HttpRangeResume.ResumeValidation.Failed)
        val different = HttpRangeResume.validateStoredValidators(stored, "\"other\"", null)
        assertTrue(different is HttpRangeResume.ResumeValidation.Failed)
    }

    @Test
    fun storedLastModifiedMustMatchWhenNoStrongEtag() {
        val stored = HttpRangeResume.ResumeValidators(lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        assertEquals(
            HttpRangeResume.ResumeValidation.Ok,
            HttpRangeResume.validateStoredValidators(stored, null, "Wed, 21 Oct 2015 07:28:00 GMT"),
        )
        val missing = HttpRangeResume.validateStoredValidators(stored, "\"abc\"", null)
        assertTrue(missing is HttpRangeResume.ResumeValidation.Failed)
        val different = HttpRangeResume.validateStoredValidators(
            stored,
            null,
            "Thu, 22 Oct 2015 07:28:00 GMT",
        )
        assertTrue(different is HttpRangeResume.ResumeValidation.Failed)
    }

    @Test
    fun storedValidatorsFailWhenNeitherStrongEtagNorLastModifiedExists() {
        val none = HttpRangeResume.validateStoredValidators(
            HttpRangeResume.ResumeValidators(),
            "\"abc\"",
            "Wed, 21 Oct 2015 07:28:00 GMT",
        )
        assertTrue(none is HttpRangeResume.ResumeValidation.Failed)
        assertTrue((none as HttpRangeResume.ResumeValidation.Failed).reason.contains("ETag"))
        assertTrue(none.reason.contains("Last-Modified"))

        val weakOnly = HttpRangeResume.validateStoredValidators(
            HttpRangeResume.ResumeValidators(etag = "W/\"abc\""),
            "\"abc\"",
            null,
        )
        assertTrue(weakOnly is HttpRangeResume.ResumeValidation.Failed)
    }

    @Test
    fun bodyLengthMustMatchContentRangeInclusiveLength() {
        HttpRangeResume.validateDeclaredBodyLength(inclusiveLength = 4L, contentLength = 4L)
        HttpRangeResume.validateDeclaredBodyLength(inclusiveLength = 4L, contentLength = -1L)
        HttpRangeResume.validateReceivedBodyLength(inclusiveLength = 4L, receivedBytes = 4L)

        val declared = assertThrows(IllegalArgumentException::class.java) {
            HttpRangeResume.validateDeclaredBodyLength(inclusiveLength = 4L, contentLength = 8L)
        }
        assertTrue(declared.message!!.contains("Content-Length"))

        val received = assertThrows(IllegalArgumentException::class.java) {
            HttpRangeResume.validateReceivedBodyLength(inclusiveLength = 4L, receivedBytes = 2L)
        }
        assertTrue(received.message!!.contains("body length"))
    }
}
