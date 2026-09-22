package com.espitman.sdm.download

/**
 * Converts the Settings speed-limit control to an aggregate byte rate.
 *
 * The persisted field is named `speedLimitMbps` (`speed_limit_mbps`) for key
 * compatibility, but the UI labels the control **MB/s** (decimal megabytes per
 * second), range 1..30. One UI unit is exactly 1,000,000 bytes/second, not
 * megabits and not binary mebibytes (1,048,576).
 */
object SpeedLimitUnits {
    const val BYTES_PER_DECIMAL_MB = 1_000_000L
    const val MIN_MB_PER_SECOND = 1f
    const val MAX_MB_PER_SECOND = 30f

    fun bytesPerSecond(mbPerSecond: Float): Long {
        require(mbPerSecond.isFinite()) { "Speed limit must be finite: $mbPerSecond" }
        require(mbPerSecond in MIN_MB_PER_SECOND..MAX_MB_PER_SECOND) {
            "Speed limit must be in $MIN_MB_PER_SECOND..$MAX_MB_PER_SECOND MB/s: $mbPerSecond"
        }
        return (mbPerSecond.toDouble() * BYTES_PER_DECIMAL_MB.toDouble()).toLong()
    }
}

/**
 * Resolves the live aggregate cap from Settings plus the current validated
 * transport. Network policy ([WifiOnlyPolicy]) remains authoritative for
 * whether a transfer may run at all; this only decides the byte rate.
 *
 * - `unlimitedSpeed=true` → no throttling.
 * - `unlimitedSpeed=false` and `speedLimitWifiOnly=false` → cap on every
 *   transport snapshot, including an offline/none reading.
 * - `unlimitedSpeed=false` and `speedLimitWifiOnly=true` → cap only while the
 *   current validated transport is Wi-Fi; cellular, ethernet, other, and none
 *   stay unrestricted.
 */
object SpeedLimitPolicy {
    fun effectiveBytesPerSecond(
        unlimitedSpeed: Boolean,
        speedLimitMbps: Float,
        speedLimitWifiOnly: Boolean,
        transport: ValidatedTransport,
    ): Long? {
        if (unlimitedSpeed) return null
        if (speedLimitWifiOnly && transport != ValidatedTransport.WIFI) return null
        return SpeedLimitUnits.bytesPerSecond(speedLimitMbps)
    }
}
