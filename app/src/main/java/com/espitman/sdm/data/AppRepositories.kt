package com.espitman.sdm.data

import android.content.Context

import com.espitman.sdm.network.DownloadMetadataRetriever
import com.espitman.sdm.network.HttpDownloadMetadataRetriever

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null
    @Volatile private var metadataRetriever: DownloadMetadataRetriever? = null

    fun downloads(context: Context): DownloadRepository = downloadRepository ?: synchronized(this) {
        downloadRepository ?: SqliteDownloadRepository(context.applicationContext).also { downloadRepository = it }
    }

    fun metadataRetriever(): DownloadMetadataRetriever = metadataRetriever ?: synchronized(this) {
        metadataRetriever ?: HttpDownloadMetadataRetriever().also { metadataRetriever = it }
    }
}
