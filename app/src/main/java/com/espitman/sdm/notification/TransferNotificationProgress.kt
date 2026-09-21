package com.espitman.sdm.notification

import java.math.BigInteger
import java.util.Locale

data class TransferNotificationProgress(
    val percent: Int,
    val max: Int,
    val indeterminate: Boolean,
    val text: String,
) {
    companion object {
        const val MAX = 100

        fun from(downloadedBytes: Long, totalBytes: Long?): TransferNotificationProgress {
            val downloaded = downloadedBytes.coerceAtLeast(0L)
            val total = totalBytes
            val indeterminate = total == null || total <= 0L
            val percent = if (indeterminate || total == null) {
                0
            } else {
                floorPercent(downloaded, total).coerceIn(0, MAX)
            }
            val text = if (indeterminate || total == null) {
                formatBytes(downloaded)
            } else {
                "${formatBytes(downloaded)} / ${formatBytes(total)}"
            }
            return TransferNotificationProgress(
                percent = percent,
                max = MAX,
                indeterminate = indeterminate,
                text = text,
            )
        }

        private fun floorPercent(downloaded: Long, total: Long): Int {
            if (downloaded <= 0L) return 0
            val raw = BigInteger.valueOf(downloaded)
                .multiply(BigInteger.valueOf(MAX.toLong()))
                .divide(BigInteger.valueOf(total))
            return if (raw > BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                Int.MAX_VALUE
            } else {
                raw.toInt()
            }
        }

        private fun formatBytes(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var index = 0
            while (value >= 1024 && index < units.lastIndex) {
                value /= 1024
                index++
            }
            return if (index == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[index])
        }
    }
}
