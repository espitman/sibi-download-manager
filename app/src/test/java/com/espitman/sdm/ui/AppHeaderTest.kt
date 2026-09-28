package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHeaderTest {
    @Test fun browserOmitsInertSearchAndMoreWhileKeepingOtherDestinations() {
        val browser = appHeaderActions(showSearch = false, showMore = false)
        assertFalse(browser.search)
        assertFalse(browser.more)
        assertFalse(browser.sort)
        assertFalse(browser.privateBadge)

        val settings = appHeaderActions(showMore = false)
        assertTrue(settings.search)
        assertFalse(settings.more)

        val defaults = appHeaderActions()
        assertTrue(defaults.search)
        assertTrue(defaults.more)
        assertFalse(defaults.sort)
    }

    @Test fun privateBrowserKeepsTheBadgeAndStillHidesHeaderActions() {
        val privateBrowser = appHeaderActions(
            showSearch = true,
            showMore = true,
            privateMode = true,
        )
        assertTrue(privateBrowser.privateBadge)
        assertFalse(privateBrowser.search)
        assertFalse(privateBrowser.more)
        assertFalse(privateBrowser.sort)
        assertEquals(
            AppHeaderActions(search = false, more = false, sort = false, privateBadge = true),
            privateBrowser,
        )
    }
}
