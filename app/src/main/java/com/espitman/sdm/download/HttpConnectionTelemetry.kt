package com.espitman.sdm.download

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Counts open payload streams, including independently active HTTP range requests. */
object HttpConnectionTelemetry {
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val active: StateFlow<Map<String, Int>> = counts

    @Synchronized private fun change(id: String, delta: Int) {
        val next = (counts.value[id].orEmptyCount() + delta).coerceAtLeast(0)
        counts.value = if (next == 0) counts.value - id else counts.value + (id to next)
    }
    private fun Int?.orEmptyCount() = this ?: 0

    internal fun track(id: String, input: InputStream): InputStream {
        change(id, 1)
        return object : FilterInputStream(input) {
            private val closed = AtomicBoolean(false)
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    try { super.close() } finally { change(id, -1) }
                }
            }
        }
    }
}
