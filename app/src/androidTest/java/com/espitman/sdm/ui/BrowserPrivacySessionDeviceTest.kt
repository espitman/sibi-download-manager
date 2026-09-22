package com.espitman.sdm.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserPrivacySessionDeviceTest {
    @Test fun startingPrivateSessionClearsCookiesLeftByInterruptedProcess() {
        val cookieManager = CookieManager.getInstance()
        val cookieSet = CountDownLatch(1)
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cookieManager.setCookie("https://sdm-private-test.example", "secret=stale") { cookieSet.countDown() }
        }
        check(cookieSet.await(5, TimeUnit.SECONDS))
        check(cookieManager.getCookie("https://sdm-private-test.example")?.contains("secret=stale") == true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            BrowserPrivacySession.begin { ready.countDown() }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Private session did not finish cleanup" }
        assertNull(cookieManager.getCookie("https://sdm-private-test.example"))
    }

    @Test fun endingPrivateSessionClearsCookiesAndWebViews() {
        val cookieManager = CookieManager.getInstance()
        val cookieSet = CountDownLatch(1)
        var view: WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = WebView(ApplicationProvider.getApplicationContext<Context>())
            cookieManager.setCookie("https://sdm-private-test.example", "secret=session",) { cookieSet.countDown() }
        }
        check(cookieSet.await(5, TimeUnit.SECONDS)) { "Cookie setup timed out" }
        val cookieBefore = cookieManager.getCookie("https://sdm-private-test.example")
        check(cookieBefore?.contains("secret=session") == true)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            BrowserPrivacySession.end(listOfNotNull(view))
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (cookieManager.getCookie("https://sdm-private-test.example") != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertNull(cookieManager.getCookie("https://sdm-private-test.example"))
    }
}
