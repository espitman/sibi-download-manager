package com.espitman.sdm.ui

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BrowserDownloadHandoffDeviceTest {
    @Test fun explicitPageDownloadClickProducesOneAddHandoff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = AtomicReference<BrowserDownloadRequest>()
        val pageReady = CountDownLatch(1)
        val downloadReady = CountDownLatch(1)
        var webView: WebView? = null
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val serverThread = thread(name = "browser-download-test-server") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val isDownload = requestLine.contains("/files/release.apk")
                        val body = if (isDownload) "apk-test" else
                            "<a id='download' href='/files/release.apk'>Download</a>"
                        val headers = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: ${if (isDownload) "application/vnd.android.package-archive" else "text/html"}\r\n")
                            if (isDownload) append("Content-Disposition: attachment; filename=release.apk\r\n")
                            append("Content-Length: ${body.toByteArray().size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        it.getOutputStream().write((headers + body).toByteArray())
                    }
                }
            } catch (_: java.net.SocketException) {
                // Closing the server terminates the test listener.
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView = WebView(context).apply {
                configurePrivateBrowserWebView(this)
                setDownloadListener { url, userAgent, disposition, mimeType, length ->
                    request.set(
                        BrowserDownloadHandoffPolicy.fromUserSelectedWebViewDownload(
                            url, userAgent, disposition, mimeType, length,
                        ),
                    )
                    downloadReady.countDown()
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pageReady.countDown()
                    }
                }
                loadUrl("$baseUrl/page")
            }
        }
        try {
            check(pageReady.await(10, TimeUnit.SECONDS)) { "WebView test page did not load" }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView?.evaluateJavascript("document.getElementById('download').click()", null)
            }
            check(downloadReady.await(10, TimeUnit.SECONDS)) { "User-selected link did not reach DownloadListener" }
            assertNotNull(request.get())
            assertEquals("$baseUrl/files/release.apk", request.get().url)
            assertEquals("release.apk", request.get().suggestedFileName)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { webView?.destroy() }
            server.close()
            serverThread.join(1_000)
        }
    }
}
