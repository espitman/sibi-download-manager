package com.espitman.sdm.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * Permission-free default destination: app-specific external Downloads,
 * with an internal `Downloads` fallback when external app storage is unusable.
 *
 * This is [Context.getExternalFilesDir] for [Environment.DIRECTORY_DOWNLOADS],
 * not the shared public Downloads tree, so it needs no storage permission on
 * API 26–35.
 */
object AppSpecificDownloadsDirectory {
    const val INTERNAL_FALLBACK_NAME = "Downloads"

    fun from(context: Context): File {
        val appContext = context.applicationContext
        return resolve(
            externalDownloadsDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            internalFilesDir = appContext.filesDir,
        )
    }

    fun resolve(externalDownloadsDir: File?, internalFilesDir: File): File {
        val external = usableDirectory(externalDownloadsDir)
        if (external != null) return external
        val fallback = File(internalFilesDir, INTERNAL_FALLBACK_NAME)
        if (!fallback.exists()) fallback.mkdirs()
        if (!fallback.exists() || !fallback.isDirectory) {
            throw IOException("Failed to create or access downloads directory: ${fallback.absolutePath}")
        }
        return fallback
    }

    private fun usableDirectory(directory: File?): File? {
        if (directory == null) return null
        if (!directory.exists()) directory.mkdirs()
        return directory.takeIf { it.exists() && it.isDirectory }
    }
}
