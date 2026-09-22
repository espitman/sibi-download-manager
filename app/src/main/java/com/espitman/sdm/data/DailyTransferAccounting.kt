package com.espitman.sdm.data

import java.time.Instant
import java.time.ZoneId

internal object DailyTransferAccounting {
    fun dayKey(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString()

    fun positiveDelta(previousBytes: Long, nextBytes: Long): Long {
        if (previousBytes < 0L || nextBytes <= previousBytes) return 0L
        return nextBytes - previousBytes
    }

    fun saturatingAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L) return 0L
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
