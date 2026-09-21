package com.espitman.sdm.download

enum class KeepActiveWakeLockAction {
    ACQUIRE,
    RELEASE,
    NONE,
}

/** Pure acquire/release transitions for a single non-reference-counted wake lock. */
object KeepActiveWakeLockDecision {
    fun nextAction(
        closed: Boolean,
        shouldHold: Boolean,
        held: Boolean,
        elapsedSinceAcquireMs: Long?,
        timeoutMs: Long,
    ): KeepActiveWakeLockAction {
        if (closed || !shouldHold) {
            return if (held) KeepActiveWakeLockAction.RELEASE else KeepActiveWakeLockAction.NONE
        }
        if (!held) return KeepActiveWakeLockAction.ACQUIRE
        val elapsed = elapsedSinceAcquireMs ?: timeoutMs
        val refreshAfterMs = timeoutMs / 2
        return if (elapsed >= refreshAfterMs) {
            KeepActiveWakeLockAction.ACQUIRE
        } else {
            KeepActiveWakeLockAction.NONE
        }
    }
}
