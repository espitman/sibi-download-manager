package com.espitman.sdm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScopedRequestContextTest {
    private val context = ScopedRequestContext(
        originUrl = "https://files.example.com/start",
        cookie = "session=secret",
        userAgent = "SDM Browser",
        referer = "https://files.example.com/page",
    )

    @Test fun credentialsAreAvailableOnlyToTheExactOrigin() {
        val same = context.headersFor("https://files.example.com/download")
        assertEquals("session=secret", same["Cookie"])
        assertEquals("https://files.example.com/page", same["Referer"])

        for (target in listOf(
            "https://cdn.example.com/download",
            "http://files.example.com/download",
            "https://files.example.com:8443/download",
        )) {
            val headers = context.headersFor(target)
            assertFalse(headers.containsKey("Cookie"))
            assertFalse(headers.containsKey("Referer"))
            assertEquals("SDM Browser", headers["User-Agent"])
        }
    }
}
