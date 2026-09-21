package com.espitman.sdm.data

import android.content.Context

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null

    fun downloads(context: Context): DownloadRepository = downloadRepository ?: synchronized(this) {
        downloadRepository ?: SqliteDownloadRepository(context.applicationContext).also { downloadRepository = it }
    }
}
