package com.espitman.sdm.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun privateBrowserWebViewIsHostedInAClippingFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var webView: WebView? = null
        var host: FrameLayout? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView = WebView(context).also(::configurePrivateBrowserWebView)
            host = hostPrivateBrowserWebView(context, requireNotNull(webView))
        }
        try {
            val hosted = requireNotNull(host)
            val child = requireNotNull(webView)
            assertTrue(hosted.clipChildren)
            assertTrue(hosted.clipToPadding)
            assertEquals(1, hosted.childCount)
            assertSame(child, hosted.getChildAt(0))
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, child.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, child.layoutParams.height)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { webView?.destroy() }
        }
    }

    @Test fun defaultWebViewRequestsAMobileSite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var webView: WebView? = null
        var userAgent = ""
        var useWideViewPort = false
        var loadWithOverviewMode = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView = WebView(context).also { configureBrowserWebView(it, isPrivate = false) }
            val settings = requireNotNull(webView).settings
            userAgent = settings.userAgentString.orEmpty()
            useWideViewPort = settings.useWideViewPort
            loadWithOverviewMode = settings.loadWithOverviewMode
        }
        try {
            assertTrue(BrowserWebViewDisplayPolicy.isMobileUserAgent(userAgent))
            assertTrue(useWideViewPort)
            assertTrue(loadWithOverviewMode)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { webView?.destroy() }
        }
    }

    @Test fun desktopModeIsPerWebViewAndDoesNotLeakToAnotherTab() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var mobileView: WebView? = null
        var desktopView: WebView? = null
        var mobileUa = ""
        var desktopUa = ""
        var restoredUa = ""
        var desktopOverview = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mobileView = WebView(context).also { configureBrowserWebView(it, isPrivate = false) }
            desktopView = WebView(context).also {
                configureBrowserWebView(it, isPrivate = false, desktopSite = true)
            }
            mobileUa = requireNotNull(mobileView).settings.userAgentString.orEmpty()
            desktopUa = requireNotNull(desktopView).settings.userAgentString.orEmpty()
            desktopOverview = requireNotNull(desktopView).settings.loadWithOverviewMode
            applyBrowserWebViewDisplayMode(requireNotNull(desktopView), desktopSite = false)
            restoredUa = requireNotNull(desktopView).settings.userAgentString.orEmpty()
        }
        try {
            assertTrue(BrowserWebViewDisplayPolicy.isMobileUserAgent(mobileUa))
            assertFalse(BrowserWebViewDisplayPolicy.isMobileUserAgent(desktopUa))
            assertEquals(BrowserWebViewDisplayPolicy.DESKTOP_USER_AGENT, desktopUa)
            assertFalse(desktopOverview)
            assertTrue(BrowserWebViewDisplayPolicy.isMobileUserAgent(restoredUa))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                mobileView?.destroy()
                desktopView?.destroy()
            }
        }
    }

    @Test fun hostedWebViewWidthIsClampedToTheScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screenWidth = context.resources.displayMetrics.widthPixels
        var webView: WebView? = null
        var unboundedWidth = 0
        var oversizedWidth = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView = WebView(context).also { configureBrowserWebView(it, isPrivate = false) }
            val hosted = hostPrivateBrowserWebView(context, requireNotNull(webView))
            hosted.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            unboundedWidth = hosted.measuredWidth
            hosted.measure(
                View.MeasureSpec.makeMeasureSpec(8192, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            oversizedWidth = hosted.measuredWidth
        }
        try {
            assertEquals(screenWidth, unboundedWidth)
            assertEquals(screenWidth, oversizedWidth)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { webView?.destroy() }
        }
    }
}
