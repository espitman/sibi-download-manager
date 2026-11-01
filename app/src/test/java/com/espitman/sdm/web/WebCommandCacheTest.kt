package com.espitman.sdm.web

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WebCommandCacheTest {
    @Test fun concurrentDuplicateExecutesOnceAndBrowsersAreIsolated() = runBlocking {
        val cache = WebCommandCache()
        var calls = 0
        suspend fun execute(browser: String) = cache.execute(browser, "1234567890abcdef", "same") {
            calls++; delay(20); LanHttpServer.Response(body = "accepted".toByteArray())
        }
        val one = async { execute("one") }; val duplicate = async { execute("one") }
        assertArrayEquals(one.await().body, duplicate.await().body)
        assertEquals(1, calls)
        execute("two")
        assertEquals(2, calls)
    }
    @Test fun rejectsChangedPayloadAndAllowsNewActionAfterExpiry() = runBlocking {
        var now = 0L
        val cache = WebCommandCache { now }
        val key = "1234567890abcdef"
        cache.execute("one", key, "first") { LanHttpServer.Response() }
        try { cache.execute("one", key, "other") { fail("Executed changed command"); LanHttpServer.Response() }; fail() }
        catch (_: IllegalArgumentException) { }
        now = 600_000
        cache.execute("one", key, "other") { LanHttpServer.Response() }
        Unit
    }
    @Test fun slowCommandDoesNotBlockUnrelatedActionsAndCannotExpireWhilePending() = runBlocking {
        var now = 0L
        val cache = WebCommandCache { now }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val slow = async { cache.execute("a", "1234567890abcdef", "slow") {
            calls++; started.complete(Unit); release.await(); LanHttpServer.Response(body = byteArrayOf(1))
        } }
        started.await(); now = 600_001
        withTimeout(1000) { cache.execute("a", "abcdef1234567890", "pause") { LanHttpServer.Response() } }
        val duplicate = async { cache.execute("a", "1234567890abcdef", "slow") { calls++; LanHttpServer.Response() } }
        release.complete(Unit)
        assertArrayEquals(slow.await().body, duplicate.await().body)
        assertEquals(1, calls)
    }
}
