package com.espitman.sdm.ui

import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[index])
}
