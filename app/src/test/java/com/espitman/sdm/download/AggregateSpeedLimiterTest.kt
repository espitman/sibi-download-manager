package com.espitman.sdm.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AggregateSpeedLimiterTest {
    @Test
    fun unlimitedDoesNotDelayOrCharge() = runBlocking {
        val delayed = AtomicLong(0L)
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = { null },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { millis -> delayed.addAndGet(millis) },
        )
        assertEquals(64_000, limiter.acquire(64_000))
        assertEquals(0L, delayed.get())
    }

    @Test
    fun allNetworkCapWaitsForAggregateTokens() = runBlocking {
        val clock = FakeMonotonicClock()
        val delayed = AtomicLong(0L)
        val limiter = limiter(
            rate = { 1_000L },
            clock = clock,
            delayed = delayed,
            maxBurstBytes = 1_000L,
        )
        var admitted = 0
        while (admitted < 5_000) {
            admitted += limiter.acquire(5_000 - admitted)
        }
        assertEquals(5_000, admitted)
        val elapsedMs = clock.nanoTime() / 1_000_000L
        assertTrue("elapsedMs=$elapsedMs delayed=${delayed.get()}", elapsedMs >= 4_000L)
        assertTrue(delayed.get() >= 4_000L)
    }

    @Test
    fun wifiOnlyTransportUsesPolicy() = runBlocking {
        val transport = AtomicReference(ValidatedTransport.WIFI)
        val delayed = AtomicLong(0L)
        val clock = FakeMonotonicClock()
        val limiter = limiter(
            rate = {
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = false,
                    speedLimitMbps = 1f,
                    speedLimitWifiOnly = true,
                    transport = transport.get(),
                )
            },
            clock = clock,
            delayed = delayed,
            maxBurstBytes = 1_000L,
        )
        assertTrue(limiter.acquire(8_000) < 8_000 || delayed.get() > 0L)
        delayed.set(0L)
        transport.set(ValidatedTransport.CELLULAR)
        assertEquals(32_000, limiter.acquire(32_000))
        assertEquals(0L, delayed.get())
    }

    @Test
    fun liveSettingChangeToUnlimitedCompletesWait() = runBlocking {
        val unlimited = AtomicBoolean(false)
        val delayStarted = CompletableDeferred<Unit>()
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = { if (unlimited.get()) null else 1L },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { _ ->
                delayStarted.complete(Unit)
                delay(60_000)
            },
        )
        val admitted = async { limiter.acquire(10_000) }
        delayStarted.await()
        unlimited.set(true)
        limiter.notifyPolicyChanged()
        assertEquals(10_000, withTimeout(1_000) { admitted.await() })
    }

    @Test
    fun liveTransportChangeOffWifiLiftsWifiOnlyCap() = runBlocking {
        val transport = AtomicReference(ValidatedTransport.WIFI)
        val delayStarted = CompletableDeferred<Unit>()
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = {
                SpeedLimitPolicy.effectiveBytesPerSecond(
                    unlimitedSpeed = false,
                    speedLimitMbps = 1f,
                    speedLimitWifiOnly = true,
                    transport = transport.get(),
                )
            },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { _ ->
                delayStarted.complete(Unit)
                delay(60_000)
            },
        )
        val admitted = async { limiter.acquire(20_000) }
        delayStarted.await()
        transport.set(ValidatedTransport.ETHERNET)
        limiter.notifyPolicyChanged()
        assertEquals(20_000, withTimeout(1_000) { admitted.await() })
    }

    @Test
    fun concurrentAcquiresShareAggregateBudgetWithoutStarvation() = runBlocking {
        val clock = FakeMonotonicClock()
        val limiter = limiter(
            rate = { 2_000L },
            clock = clock,
            maxBurstBytes = 500L,
        )
        val first = async {
            var admitted = 0
            while (admitted < 3_000) {
                admitted += limiter.acquire(3_000 - admitted)
            }
            admitted
        }
        val second = async {
            var admitted = 0
            while (admitted < 3_000) {
                admitted += limiter.acquire(3_000 - admitted)
            }
            admitted
        }
        assertEquals(3_000, withTimeout(5_000) { first.await() })
        assertEquals(3_000, withTimeout(5_000) { second.await() })
        val elapsedMs = clock.nanoTime() / 1_000_000L
        assertTrue("elapsedMs=$elapsedMs", elapsedMs >= 2_700L)
    }

    @Test
    fun cancellingNewlyPromotedHeadWakesTheNextWaiter() = runBlocking {
        val headDelayStarted = CompletableDeferred<Unit>()
        val secondEnqueued = CompletableDeferred<Unit>()
        val thirdEnqueued = CompletableDeferred<Unit>()
        val unlimited = AtomicBoolean(false)
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = { if (unlimited.get()) null else 1L },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { _ ->
                headDelayStarted.complete(Unit)
                delay(60_000)
            },
        )
        limiter.onEnqueued = { size ->
            if (size >= 2) secondEnqueued.complete(Unit)
            if (size >= 3) thirdEnqueued.complete(Unit)
        }

        val first = async { limiter.acquire(8_000) }
        headDelayStarted.await()
        val second = async { limiter.acquire(8_000) }
        secondEnqueued.await()
        val third = async { limiter.acquire(8_000) }
        thirdEnqueued.await()

        limiter.onPromoted = { second.cancel() }
        first.cancel()
        assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { second.await() }.exceptionOrNull() is CancellationException)

        unlimited.set(true)
        limiter.notifyPolicyChanged()
        assertEquals(8_000, withTimeout(1_000) { third.await() })
    }

    @Test
    fun concurrentPolicyNotificationsWakeWaiter() = runBlocking {
        val delayStarted = CompletableDeferred<Unit>()
        val unlimited = AtomicBoolean(false)
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = { if (unlimited.get()) null else 1L },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { _ ->
                delayStarted.complete(Unit)
                delay(60_000)
            },
        )
        val pending = async { limiter.acquire(4_000) }
        delayStarted.await()
        coroutineScope {
            repeat(32) {
                launch { limiter.notifyPolicyChanged() }
            }
        }
        unlimited.set(true)
        limiter.notifyPolicyChanged()
        assertEquals(4_000, withTimeout(1_000) { pending.await() })
    }

    @Test
    fun acquireIsCancellableDuringWait() = runBlocking {
        val delayStarted = CompletableDeferred<Unit>()
        val limiter = AggregateSpeedLimiter(
            effectiveBytesPerSecond = { 1L },
            monotonicClock = FakeMonotonicClock(),
            delayMillis = { _ ->
                delayStarted.complete(Unit)
                delay(60_000)
            },
        )
        val pending = async { limiter.acquire(8_000) }
        delayStarted.await()
        pending.cancel()
        val thrown = runCatching { pending.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun clockRollbackDoesNotGrantABurst() = runBlocking {
        val clock = FakeMonotonicClock(initialNanos = 1_000_000_000_000L)
        val delayed = AtomicLong(0L)
        val limiter = limiter(
            rate = { 10_000L },
            clock = clock,
            delayed = delayed,
            maxBurstBytes = 1_000L,
        )
        clock.nanos = 1_000L
        val first = limiter.acquire(8_000)
        assertTrue("first=$first", first <= 1_000)
        assertTrue("rollback must not mint tokens delayed=${delayed.get()}", delayed.get() > 0L)
    }

    @Test
    fun sameTickAndForwardOverflowDoNotExplodeTokens() = runBlocking {
        val clock = FakeMonotonicClock(initialNanos = Long.MIN_VALUE + 32L)
        val delayed = AtomicLong(0L)
        val limiter = limiter(
            rate = { 1_000_000L },
            clock = clock,
            delayed = delayed,
            maxBurstBytes = 2_000L,
        )
        limiter.acquire(1)
        clock.nanos = Long.MAX_VALUE
        val granted = limiter.acquire(50_000)
        assertTrue("granted=$granted", granted <= 2_000)
        delayed.set(0L)
        clock.advance(0L)
        val again = limiter.acquire(50_000)
        assertTrue(again <= 2_000)
    }

    @Test
    fun initialTokensDoNotAllowGiantBurst() = runBlocking {
        val clock = FakeMonotonicClock()
        val delayed = AtomicLong(0L)
        val limiter = limiter(
            rate = { 1_000_000L },
            clock = clock,
            delayed = delayed,
            maxBurstBytes = 4_000L,
        )
        val first = limiter.acquire(64_000)
        assertTrue("first=$first", first <= 4_000)
        assertTrue(delayed.get() > 0L)
    }

    @Test
    fun zeroAndNegativeAcquireAreRejectedOrNoops() = runBlocking {
        val limiter = limiter(rate = { 1_000L }, clock = FakeMonotonicClock())
        assertEquals(0, limiter.acquire(0))
        val thrown = runCatching { limiter.acquire(-1) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    private fun limiter(
        rate: () -> Long?,
        clock: FakeMonotonicClock,
        delayed: AtomicLong = AtomicLong(0L),
        maxBurstBytes: Long = 1_000L,
    ) = AggregateSpeedLimiter(
        effectiveBytesPerSecond = rate,
        monotonicClock = clock,
        delayMillis = { millis ->
            delayed.addAndGet(millis)
            clock.advance(millis * 1_000_000L)
            yield()
        },
        maxBurstBytes = maxBurstBytes,
        maxWaitSliceMillis = 50L,
    )

    private class FakeMonotonicClock(initialNanos: Long = 0L) : MonotonicClock {
        var nanos: Long = initialNanos
        override fun nanoTime(): Long = nanos
        fun advance(deltaNanos: Long) {
            nanos += deltaNanos
        }
    }
}
