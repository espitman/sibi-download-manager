package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewCallbackPolicyTest {
    @Test
    fun pageCallbacksMutateChromeOnlyForTheActiveTab() {
        assertTrue(BrowserWebViewCallbackPolicy.appliesToActiveChrome("tab-2", "tab-2"))
        assertFalse(BrowserWebViewCallbackPolicy.appliesToActiveChrome("tab-1", "tab-2"))
    }

    @Test
    fun mainFrameFailureChromeIsActiveTabOnly() {
        assertTrue(
            BrowserWebViewCallbackPolicy.appliesToActiveLoadFailure(
                callbackTabId = "tab-2",
                activeTabId = "tab-2",
                isForMainFrame = true,
            ),
        )
        assertFalse(
            BrowserWebViewCallbackPolicy.appliesToActiveLoadFailure(
                callbackTabId = "tab-1",
                activeTabId = "tab-2",
                isForMainFrame = true,
            ),
        )
        assertFalse(
            BrowserWebViewCallbackPolicy.appliesToActiveLoadFailure(
                callbackTabId = "tab-2",
                activeTabId = "tab-2",
                isForMainFrame = false,
            ),
        )
    }

    @Test
    fun finishedVisitUsesOwningTabPrivacyNotTheActiveTab() {
        assertFalse(BrowserWebViewCallbackPolicy.shouldRecordFinishedVisit(owningTabIsPrivate = true))
        assertTrue(BrowserWebViewCallbackPolicy.shouldRecordFinishedVisit(owningTabIsPrivate = false))
    }

    @Test
    fun resolvedTitlePrefersWebViewThenOwningTabNotTheActiveTab() {
        assertEquals(
            "Owning",
            BrowserWebViewCallbackPolicy.resolvedTabTitle(null, "Owning", "https://slow.example"),
        )
        assertEquals(
            "Loaded",
            BrowserWebViewCallbackPolicy.resolvedTabTitle("Loaded", "Owning", "https://slow.example"),
        )
        assertEquals(
            "https://slow.example",
            BrowserWebViewCallbackPolicy.resolvedTabTitle("  ", " ", "https://slow.example"),
        )
    }

    @Test
    fun composeUpdateDoesNotReloadWhenChromeUrlDiffersWithoutPendingNavigation() {
        assertNull(
            BrowserWebViewCallbackPolicy.urlToLoadOnComposeUpdate(
                pending = null,
                visibleTabId = "tab-2",
                viewUrl = "https://active.example",
            ),
        )
    }

    @Test
    fun composeUpdateLoadsPendingUrlOnlyForTheVisibleOwningTab() {
        val pending = BrowserPendingNavigation("tab-1", "https://slow.example")
        assertNull(
            BrowserWebViewCallbackPolicy.urlToLoadOnComposeUpdate(
                pending = pending,
                visibleTabId = "tab-2",
                viewUrl = "https://active.example",
            ),
        )
        assertEquals(
            "https://slow.example",
            BrowserWebViewCallbackPolicy.urlToLoadOnComposeUpdate(
                pending = pending,
                visibleTabId = "tab-1",
                viewUrl = "https://old.example",
            ),
        )
        assertNull(
            BrowserWebViewCallbackPolicy.urlToLoadOnComposeUpdate(
                pending = pending,
                visibleTabId = "tab-1",
                viewUrl = "https://slow.example",
            ),
        )
        assertFalse(
            BrowserWebViewCallbackPolicy.shouldConsumePendingNavigation(pending, "tab-2"),
        )
        assertTrue(
            BrowserWebViewCallbackPolicy.shouldConsumePendingNavigation(pending, "tab-1"),
        )
        assertFalse(
            BrowserWebViewCallbackPolicy.shouldConsumePendingNavigation(null, "tab-1"),
        )
    }
}
