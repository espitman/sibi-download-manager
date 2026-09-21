package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepActiveWakeLockDecisionTest {
    private val timeoutMs = KeepActivePolicy.WAKE_LOCK_TIMEOUT_MS

    @Test
    fun acquiresWhenPolicyRequiresHoldAndLockIsFree() {
        assertEquals(
            KeepActiveWakeLockAction.ACQUIRE,
            next(closed = false, shouldHold = true, held = false, elapsedSinceAcquireMs = null),
        )
    }

    @Test
    fun doesNotReacquireBeforeHalfTimeout() {
        assertEquals(
            KeepActiveWakeLockAction.NONE,
            next(closed = false, shouldHold = true, held = true, elapsedSinceAcquireMs = timeoutMs / 2 - 1),
        )
    }

    @Test
    fun refreshesAtHalfTimeoutWhilePolicyStillHolds() {
        assertEquals(
            KeepActiveWakeLockAction.ACQUIRE,
            next(closed = false, shouldHold = true, held = true, elapsedSinceAcquireMs = timeoutMs / 2),
        )
        assertEquals(
            KeepActiveWakeLockAction.ACQUIRE,
            next(closed = false, shouldHold = true, held = true, elapsedSinceAcquireMs = timeoutMs - 1),
        )
    }

    @Test
    fun refreshesWhenHeldElapsedIsUnknown() {
        assertEquals(
            KeepActiveWakeLockAction.ACQUIRE,
            next(closed = false, shouldHold = true, held = true, elapsedSinceAcquireMs = null),
        )
    }

    @Test
    fun releasesImmediatelyWhenPolicyBecomesFalse() {
        assertEquals(
            KeepActiveWakeLockAction.RELEASE,
            next(closed = false, shouldHold = false, held = true, elapsedSinceAcquireMs = 1_000),
        )
        assertEquals(
            KeepActiveWakeLockAction.NONE,
            next(closed = false, shouldHold = false, held = false, elapsedSinceAcquireMs = null),
        )
    }

    @Test
    fun closedReleasesHeldLockAndBlocksLaterAcquire() {
        assertEquals(
            KeepActiveWakeLockAction.RELEASE,
            next(closed = true, shouldHold = true, held = true, elapsedSinceAcquireMs = 1_000),
        )
        assertEquals(
            KeepActiveWakeLockAction.NONE,
            next(closed = true, shouldHold = true, held = false, elapsedSinceAcquireMs = null),
        )
        assertEquals(
            KeepActiveWakeLockAction.NONE,
            next(closed = true, shouldHold = false, held = false, elapsedSinceAcquireMs = timeoutMs),
        )
    }

    @Test
    fun sequencesAcquireRefreshReleaseAndClosedGuard() {
        var closed = false
        var held = false

        fun step(shouldHold: Boolean, elapsedMs: Long?): KeepActiveWakeLockAction {
            val action = next(closed, shouldHold, held, elapsedMs)
            when (action) {
                KeepActiveWakeLockAction.ACQUIRE -> held = true
                KeepActiveWakeLockAction.RELEASE -> held = false
                KeepActiveWakeLockAction.NONE -> Unit
            }
            return action
        }

        assertEquals(KeepActiveWakeLockAction.ACQUIRE, step(shouldHold = true, elapsedMs = null))
        assertEquals(KeepActiveWakeLockAction.NONE, step(shouldHold = true, elapsedMs = timeoutMs / 2 - 1))
        assertEquals(KeepActiveWakeLockAction.ACQUIRE, step(shouldHold = true, elapsedMs = timeoutMs / 2))
        assertEquals(KeepActiveWakeLockAction.RELEASE, step(shouldHold = false, elapsedMs = 10))
        closed = true
        assertEquals(KeepActiveWakeLockAction.NONE, step(shouldHold = true, elapsedMs = null))
    }

    private fun next(
        closed: Boolean,
        shouldHold: Boolean,
        held: Boolean,
        elapsedSinceAcquireMs: Long?,
    ) = KeepActiveWakeLockDecision.nextAction(
        closed = closed,
        shouldHold = shouldHold,
        held = held,
        elapsedSinceAcquireMs = elapsedSinceAcquireMs,
        timeoutMs = timeoutMs,
    )
}
