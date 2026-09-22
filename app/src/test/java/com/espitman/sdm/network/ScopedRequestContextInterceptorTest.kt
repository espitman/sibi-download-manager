package com.espitman.sdm.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopedRequestContextInterceptorTest {
    @Test fun redirectToUnrelatedOriginCannotReceiveBrowserCredentials() {
        val origin = MockWebServer()
        val unrelated = MockWebServer()
        origin.start()
        unrelated.start()
        try {
            origin.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", unrelated.url("/final")),
            )
            unrelated.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val originUrl = origin.url("/start").toString()
            val context = ScopedRequestContext(
                originUrl = originUrl,
                cookie = "session=secret",
                userAgent = "SDM Browser",
                referer = origin.url("/page").toString(),
            )
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(ScopedRequestContextInterceptor())
                .build()
            val request = Request.Builder().url(originUrl)
                .tag(ScopedRequestContext::class.java, context)
                .header("Authorization", "Bearer must-not-leak")
                .build()

            client.newCall(request).execute().use { assertEquals(200, it.code) }

            val first = origin.takeRequest()
            assertEquals("session=secret", first.getHeader("Cookie"))
            assertEquals("SDM Browser", first.getHeader("User-Agent"))
            val redirected = unrelated.takeRequest()
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader("Referer"))
            assertNull(redirected.getHeader("Authorization"))
            assertEquals("SDM Browser", redirected.getHeader("User-Agent"))
        } finally {
            origin.shutdown()
            unrelated.shutdown()
        }
    }
}
