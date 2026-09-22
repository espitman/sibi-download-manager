package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserNavigationTest {
    @Test fun addressInputPreservesHttpAndHttpsIncludingCleartextLinks() {
        assertEquals("http://example.com/file.bin", normalizeBrowserInput(" http://example.com/file.bin "))
        assertEquals("https://example.com", normalizeBrowserInput("https://example.com"))
    }

    @Test fun hostNamesGainHttpsAndSearchTermsUseTheConfiguredSearchUrl() {
        assertEquals("https://archive.org", normalizeBrowserInput("archive.org"))
        assertEquals(
            "https://www.google.com/search?q=large%20test%20file",
            normalizeBrowserInput("large test file"),
        )
    }

    @Test fun emptyAndUnsupportedSchemesAreRejected() {
        assertNull(normalizeBrowserInput("  "))
        assertNull(normalizeBrowserInput("javascript:alert(1)"))
        assertNull(normalizeBrowserInput("file:///sdcard/private"))
    }

    @Test fun mainFrameErrorsUseStableUserFacingMessages() {
        assertEquals("Check your connection and try again.", browserFailureMessage(-2, "net::ERR_FAILED"))
        assertEquals("The address could not be found.", browserFailureMessage(-6, null))
        assertEquals("The connection timed out.", browserFailureMessage(-8, null))
        assertEquals("A secure connection could not be established.", browserFailureMessage(-11, null))
        assertEquals("Server unavailable", browserFailureMessage(-1, " Server unavailable "))
    }
}
