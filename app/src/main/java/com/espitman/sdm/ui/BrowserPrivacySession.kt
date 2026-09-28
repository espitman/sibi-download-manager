package com.espitman.sdm.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

internal object BrowserPrivacySession {
    fun begin(onReady: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            onReady()
        }
    }

    fun end(webViews: Collection<WebView>, clearData: Boolean = true) {
        webViews.forEach { view ->
            view.stopLoading()
            if (clearData) {
                view.clearHistory()
                view.clearCache(true)
                view.clearFormData()
            }
            view.destroy()
        }
        if (!clearData) return
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}
