package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewLayoutPolicyTest {
    @Test
    fun webViewPageUsesRemainingColumnSpaceAndKeepsDockClearance() {
        assertTrue(BrowserWebViewLayoutPolicy.USE_REMAINING_COLUMN_SPACE)
        assertTrue(BrowserWebViewLayoutPolicy.CLIP_HOST_CHILDREN)
        assertEquals(98, BrowserWebViewLayoutPolicy.DOCK_CLEARANCE_DP)
    }
}
