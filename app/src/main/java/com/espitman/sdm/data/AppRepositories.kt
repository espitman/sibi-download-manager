package com.espitman.sdm.data

import android.content.Context

import com.espitman.sdm.download.DownloadSubmissionCoordinator
import com.espitman.sdm.download.DownloadTransferEngine
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.network.DownloadMetadataRetriever
import com.espitman.sdm.network.HttpDownloadMetadataRetriever
import java.io.File

/** Application-owned dependencies; never retain an Activity. */
object AppRepositories {
    @Volatile private var downloadRepository: DownloadRepository? = null
    @Volatile private var metadataRetriever: DownloadMetadataRetriever? = null
    @Volatile private var transferEngine: DownloadTransferEngine? = null
    @Volatile private var submissionCoordinator: DownloadSubmissionCoordinator? = null

    fun downloads(context: Context): DownloadRepository = downloadRepository ?: synchronized(this) {
        downloadRepository ?: SqliteDownloadRepository(context.applicationContext).also { downloadRepository = it }
    }

    fun metadataRetriever(): DownloadMetadataRetriever = metadataRetriever ?: synchronized(this) {
        metadataRetriever ?: HttpDownloadMetadataRetriever().also { metadataRetriever = it }
    }

    fun transferEngine(): DownloadTransferEngine = transferEngine ?: synchronized(this) {
        transferEngine ?: DownloadTransferEngine().also { transferEngine = it }
    }

    fun submissionCoordinator(context: Context): DownloadSubmissionCoordinator = submissionCoordinator ?: synchronized(this) {
        submissionCoordinator ?: run {
            val appContext = context.applicationContext
            val repo = downloads(appContext)
            val retriever = metadataRetriever()
            val directoryProvider = {
                val externalDir = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = if (externalDir != null) {
                    if (!externalDir.exists()) externalDir.mkdirs()
                    if (externalDir.exists() && externalDir.isDirectory) externalDir else null
                } else null

                dir ?: run {
                    val fallback = File(appContext.filesDir, "Downloads")
                    if (!fallback.exists()) fallback.mkdirs()
                    if (!fallback.exists() || !fallback.isDirectory) {
                        throw java.io.IOException("Failed to create or access downloads directory: ${fallback.absolutePath}")
                    }
                    fallback
                }
            }
            val transferStarter = { download: com.espitman.sdm.domain.Download, tempFile: File ->
                DownloadTransferService.startTransfer(appContext, download.id, tempFile.absolutePath)
            }
            DownloadSubmissionCoordinator(
                metadataRetriever = retriever,
                repository = repo,
                directoryProvider = directoryProvider,
                transferStarter = transferStarter,
            ).also { submissionCoordinator = it }
        }
    }
}
