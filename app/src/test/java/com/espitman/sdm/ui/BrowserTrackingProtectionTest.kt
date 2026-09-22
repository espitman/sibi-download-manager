package com.espitman.sdm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTrackingProtectionTest {
    @Test fun knownTrackerHostsAreBlockedWithoutBlockingLookalikes() {
        assertTrue(BrowserTrackingProtection.shouldBlock("https://stats.google-analytics.com/collect"))
        assertTrue(BrowserTrackingProtection.shouldBlock("https://connect.facebook.net/en_US/fbevents.js"))
        assertFalse(BrowserTrackingProtection.shouldBlock("https://mygoogle-analytics.com/file.bin"))
        assertFalse(BrowserTrackingProtection.shouldBlock("http://127.0.0.1:8001/sample.bin"))
    }
}
