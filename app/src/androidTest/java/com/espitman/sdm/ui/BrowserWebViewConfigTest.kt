package com.espitman.sdm.ui

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserWebViewConfigTest {
    @Test fun privateBrowserUsesSessionOnlyWebViewConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var webView: WebView? = null
        var javaScript = false
        var domStorage = false
        var cacheMode = 0
        var multipleWindows = true
        var saveFormData = true
        var fileAccess = true
        var contentAccess = true
        var fileUrlAccess = true
        var universalFileUrlAccess = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView = WebView(context).also(::configurePrivateBrowserWebView)
            val settings = requireNotNull(webView).settings
            javaScript = settings.javaScriptEnabled
            domStorage = settings.domStorageEnabled
            cacheMode = settings.cacheMode
            multipleWindows = settings.supportMultipleWindows()
            fileAccess = settings.allowFileAccess
            contentAccess = settings.allowContentAccess
            @Suppress("DEPRECATION")
            fileUrlAccess = settings.allowFileAccessFromFileURLs
            @Suppress("DEPRECATION")
            universalFileUrlAccess = settings.allowUniversalAccessFromFileURLs
            @Suppress("DEPRECATION")
            saveFormData = settings.saveFormData
        }
        try {
            assertTrue(javaScript)
            assertTrue(domStorage)
            assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
            assertFalse(multipleWindows)
            assertFalse(saveFormData)
            assertFalse(fileAccess)
            assertFalse(contentAccess)
            assertFalse(fileUrlAccess)
            assertFalse(universalFileUrlAccess)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { webView?.destroy() }
        }
    }
}
