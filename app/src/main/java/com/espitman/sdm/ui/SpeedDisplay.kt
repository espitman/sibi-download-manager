package com.espitman.sdm.ui

import com.espitman.sdm.download.SpeedLimitUnits
import kotlin.math.roundToLong

internal data class DecimalSpeedDisplay(
    val value: String,
    val unit: String,
) {
    val formatted: String get() = "$value $unit"
}

private const val BYTES_PER_DECIMAL_KB = 1_000L

/** Decimal KB/s below 1 MB/s, whole MB/s at or above 1,000,000 B/s. */
internal fun decimalSpeedDisplay(bytesPerSecond: Long): DecimalSpeedDisplay {
    val rate = bytesPerSecond.coerceAtLeast(0L)
    return when {
        rate == 0L -> DecimalSpeedDisplay("0", "MB/s")
        rate < BYTES_PER_DECIMAL_KB -> DecimalSpeedDisplay("<1", "KB/s")
        rate < SpeedLimitUnits.BYTES_PER_DECIMAL_MB ->
            DecimalSpeedDisplay((rate / BYTES_PER_DECIMAL_KB).toString(), "KB/s")
        else -> DecimalSpeedDisplay(
            (rate.toDouble() / SpeedLimitUnits.BYTES_PER_DECIMAL_MB).roundToLong().toString(),
            "MB/s",
        )
    }
}
