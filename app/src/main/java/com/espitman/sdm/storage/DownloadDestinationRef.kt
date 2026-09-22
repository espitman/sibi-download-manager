package com.espitman.sdm.storage

object DownloadDestinationRef {
    fun isContentUri(path: String?): Boolean {
        val trimmed = path?.trim() ?: return false
        return trimmed.startsWith("content:", ignoreCase = true)
    }
}
