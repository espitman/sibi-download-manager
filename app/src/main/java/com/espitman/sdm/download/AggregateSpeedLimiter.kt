package com.espitman.sdm.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

fun interface SpeedLimiter {
    /**
     * Suspends until at least one byte may be written, then admits up to
     * [byteCount] bytes under the current aggregate cap. Returns immediately
     * with [byteCount] when the effective rate is unlimited.
     */
    suspend fun acquire(byteCount: Int): Int

    companion object {
        val Unlimited = SpeedLimiter { byteCount ->
            require(byteCount >= 0) { "byteCount must be >= 0: $byteCount" }
            byteCount
        }
    }
}

fun interface MonotonicClock {
    fun nanoTime(): Long

    object System : MonotonicClock {
        override fun nanoTime(): Long = java.lang.System.nanoTime()
    }
}

/**
 * Process-wide token bucket shared by every active transfer. Callers join a
 * FIFO queue so concurrent downloads share the cap without starving one
 * another. Settings and transport changes are read on every admission and
 * also wake waiters via [notifyPolicyChanged].
 */
class AggregateSpeedLimiter(
    private val effectiveBytesPerSecond: () -> Long?,
    private val monotonicClock: MonotonicClock = MonotonicClock.System,
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val maxBurstBytes: Long = DEFAULT_MAX_BURST_BYTES,
    private val maxWaitSliceMillis: Long = DEFAULT_MAX_WAIT_SLICE_MILLIS,
) : SpeedLimiter {
    private val mutex = Mutex()
    private val waiters = ArrayDeque<TurnTicket>()
    private val policyGeneration = AtomicInteger(0)
    private val policySignal = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var tokensBytes = 0.0
    private var lastRefillNanos = monotonicClock.nanoTime()

    internal var onEnqueued: ((Int) -> Unit)? = null
    internal var onPromoted: (() -> Unit)? = null

    init {
        require(maxBurstBytes > 0L) { "maxBurstBytes must be > 0: $maxBurstBytes" }
        require(maxWaitSliceMillis > 0L) { "maxWaitSliceMillis must be > 0: $maxWaitSliceMillis" }
        policySignal.tryEmit(0)
    }

    fun notifyPolicyChanged() {
        val next = policyGeneration.incrementAndGet()
        policySignal.tryEmit(next)
    }

    override suspend fun acquire(byteCount: Int): Int {
        require(byteCount >= 0) { "byteCount must be >= 0: $byteCount" }
        if (byteCount == 0) return 0
        if (currentRate() == null) return byteCount

        val ticket = TurnTicket()
        try {
            awaitTurn(ticket)
            currentCoroutineContext().ensureActive()
            return admitAsHead(byteCount)
        } finally {
            withContext(NonCancellable) {
                releaseTurn(ticket)
            }
        }
    }

    private suspend fun awaitTurn(ticket: TurnTicket) {
        val runNow: Boolean
        val queued: Int
        mutex.withLock {
            waiters.addLast(ticket)
            runNow = waiters.first() === ticket
            queued = waiters.size
        }
        onEnqueued?.invoke(queued)
        if (runNow) return
        ticket.ready.await()
    }

    private suspend fun releaseTurn(ticket: TurnTicket) {
        val promoted = mutex.withLock { removeAndPromoteLocked(ticket) }
        if (promoted) {
            onPromoted?.invoke()
        }
    }

    /**
     * Removes [ticket] if present. When it was head, completes the next waiter
     * exactly once. Safe to call again after the ticket is already gone.
     */
    private fun removeAndPromoteLocked(ticket: TurnTicket): Boolean {
        val index = waiters.indexOfFirst { it === ticket }
        if (index < 0) return false
        waiters.removeAt(index)
        if (index != 0) return false
        val next = waiters.firstOrNull() ?: return false
        next.ready.complete(Unit)
        return true
    }

    private suspend fun admitAsHead(byteCount: Int): Int {
        while (true) {
            currentCoroutineContext().ensureActive()
            val rate = currentRate() ?: return byteCount
            val granted = mutex.withLock {
                refillLocked(rate)
                grantLocked(byteCount, rate)
            }
            if (granted > 0) return granted

            val waitMs = mutex.withLock {
                val liveRate = currentRate() ?: return byteCount
                refillLocked(liveRate)
                if (max(0.0, tokensBytes) >= 1.0) {
                    0L
                } else {
                    waitSliceMillis(liveRate)
                }
            }
            if (waitMs <= 0L) continue
            awaitBudget(waitMs)
        }
    }

    private fun grantLocked(byteCount: Int, rateBps: Long): Int {
        val burst = burstBytes(rateBps)
        val available = min(max(0.0, tokensBytes), burst.toDouble())
        val granted = minOf(byteCount.toLong(), available.toLong(), burst).toInt()
        if (granted > 0) {
            tokensBytes = max(0.0, tokensBytes - granted.toDouble())
        }
        return granted
    }

    private fun refillLocked(rateBps: Long) {
        val now = monotonicClock.nanoTime()
        val elapsed = now - lastRefillNanos
        if (now < lastRefillNanos || elapsed < 0L) {
            lastRefillNanos = now
            return
        }
        lastRefillNanos = now
        if (elapsed == 0L || rateBps <= 0L) return
        val added = rateBps.toDouble() * (elapsed.toDouble() / NANOS_PER_SECOND)
        val burst = burstBytes(rateBps).toDouble()
        tokensBytes = if (added.isNaN() || added.isInfinite()) {
            burst
        } else {
            min(burst, max(0.0, tokensBytes) + added)
        }
    }

    private fun burstBytes(rateBps: Long): Long {
        if (rateBps <= 0L) return 0L
        return min(maxBurstBytes, rateBps)
    }

    private fun waitSliceMillis(rateBps: Long): Long {
        if (rateBps <= 0L) return maxWaitSliceMillis
        val deficit = max(1.0 - max(0.0, tokensBytes), 1.0)
        val millis = ceil(deficit * MILLIS_PER_SECOND / rateBps.toDouble()).toLong()
        return when {
            millis <= 0L -> 1L
            millis > maxWaitSliceMillis -> maxWaitSliceMillis
            else -> millis
        }
    }

    private suspend fun awaitBudget(maxMillis: Long) {
        val seenGeneration = policyGeneration.get()
        if (policyGeneration.get() != seenGeneration) {
            currentCoroutineContext().ensureActive()
            return
        }
        coroutineScope {
            val finished = CompletableDeferred<Unit>()
            val delayJob = launch {
                delayMillis(maxMillis)
                finished.complete(Unit)
            }
            val wakeJob = launch {
                val replayed = policySignal.first()
                if (replayed != seenGeneration || policyGeneration.get() != seenGeneration) {
                    finished.complete(Unit)
                    return@launch
                }
                policySignal.first { generation -> generation != seenGeneration }
                finished.complete(Unit)
            }
            try {
                finished.await()
            } finally {
                delayJob.cancel()
                wakeJob.cancel()
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun currentRate(): Long? {
        val rate = effectiveBytesPerSecond() ?: return null
        return if (rate <= 0L) null else rate
    }

    private class TurnTicket {
        val ready = CompletableDeferred<Unit>()
    }

    companion object {
        const val DEFAULT_MAX_BURST_BYTES = 32L * 1024L
        const val DEFAULT_MAX_WAIT_SLICE_MILLIS = 50L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MILLIS_PER_SECOND = 1_000.0
    }
}
