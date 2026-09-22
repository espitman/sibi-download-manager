package com.espitman.sdm.ui

import com.espitman.sdm.download.SpeedLimitUnits
import kotlin.math.roundToLong

/** Decimal MB/s, matching the bandwidth control. Keep sub-MB rates at zero. */
internal fun displayMegabytesPerSecond(bytesPerSecond: Long): Long {
    val rate = bytesPerSecond.coerceAtLeast(0L)
    if (rate < SpeedLimitUnits.BYTES_PER_DECIMAL_MB) return 0L
    return (rate.toDouble() / SpeedLimitUnits.BYTES_PER_DECIMAL_MB).roundToLong()
}
