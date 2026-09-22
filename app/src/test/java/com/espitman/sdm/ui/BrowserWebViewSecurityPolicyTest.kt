package com.espitman.sdm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewSecurityPolicyTest {
    @Test
    fun privateBrowserDisablesFileAndContentAccess() {
        assertTrue(BrowserWebViewSecurityPolicy.JAVASCRIPT_ENABLED)
        assertTrue(BrowserWebViewSecurityPolicy.DOM_STORAGE_ENABLED)
        assertFalse(BrowserWebViewSecurityPolicy.ALLOW_FILE_ACCESS)
        assertFalse(BrowserWebViewSecurityPolicy.ALLOW_CONTENT_ACCESS)
        assertFalse(BrowserWebViewSecurityPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS)
        assertFalse(BrowserWebViewSecurityPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS)
        assertFalse(BrowserWebViewSecurityPolicy.SUPPORT_MULTIPLE_WINDOWS)
        assertFalse(BrowserWebViewSecurityPolicy.SAVE_FORM_DATA)
    }
}
