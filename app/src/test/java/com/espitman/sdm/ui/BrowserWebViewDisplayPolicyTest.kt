package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewDisplayPolicyTest {
    @Test fun defaultRequestsAreMobileAndDesktopOnlyWhenOptedIn() {
        val systemMobile =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"
        assertEquals(systemMobile, BrowserWebViewDisplayPolicy.userAgent(false, systemMobile))
        assertTrue(BrowserWebViewDisplayPolicy.isMobileUserAgent(systemMobile))
        assertTrue(
            BrowserWebViewDisplayPolicy.isDesktopUserAgent(
                BrowserWebViewDisplayPolicy.userAgent(true, systemMobile),
            ),
        )
        assertFalse(
            BrowserWebViewDisplayPolicy.isMobileUserAgent(
                BrowserWebViewDisplayPolicy.userAgent(true, systemMobile),
            ),
        )
        assertEquals(
            BrowserWebViewDisplayPolicy.DESKTOP_USER_AGENT,
            BrowserWebViewDisplayPolicy.userAgent(true, systemMobile),
        )
        assertTrue(BrowserWebViewDisplayPolicy.loadWithOverviewMode(false))
        assertFalse(BrowserWebViewDisplayPolicy.loadWithOverviewMode(true))
        assertTrue(BrowserWebViewDisplayPolicy.USE_WIDE_VIEWPORT)
    }

    @Test fun missingMobileTokenIsRestoredWithoutChangingAnAlreadyMobileUa() {
        val desktopLike =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Safari/537.36"
        val restored = BrowserWebViewDisplayPolicy.mobileUserAgent(desktopLike)
        assertTrue(BrowserWebViewDisplayPolicy.isMobileUserAgent(restored))
        assertTrue(restored.contains("Mobile Safari"))
        assertEquals(
            BrowserWebViewDisplayPolicy.FALLBACK_MOBILE_USER_AGENT,
            BrowserWebViewDisplayPolicy.mobileUserAgent("   "),
        )
    }

    @Test fun composeOvermeasureIsClampedToTheScreenWidth() {
        assertEquals(1080, BrowserWebViewDisplayPolicy.constrainedWidthPx(0, unbounded = true, 1080))
        assertEquals(1080, BrowserWebViewDisplayPolicy.constrainedWidthPx(8192, unbounded = false, 1080))
        assertEquals(980, BrowserWebViewDisplayPolicy.constrainedWidthPx(980, unbounded = false, 1080))
        assertEquals(1, BrowserWebViewDisplayPolicy.constrainedWidthPx(400, unbounded = true, 0))
    }
}
