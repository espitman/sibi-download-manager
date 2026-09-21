package com.espitman.sdm.download

data class StartTransferCommand(
    val downloadId: String,
    val tempFilePath: String,
)

object DownloadTransferCommand {
    const val ACTION_START_TRANSFER = "com.espitman.sdm.download.action.START_TRANSFER"
    const val EXTRA_DOWNLOAD_ID = "com.espitman.sdm.download.extra.DOWNLOAD_ID"
    const val EXTRA_TEMP_FILE_PATH = "com.espitman.sdm.download.extra.TEMP_FILE_PATH"

    fun parse(
        action: String?,
        downloadId: String?,
        tempFilePath: String?,
    ): StartTransferCommand? {
        if (action != ACTION_START_TRANSFER) return null
        val id = downloadId?.trim().orEmpty()
        val path = tempFilePath?.trim().orEmpty()
        if (id.isEmpty() || path.isEmpty()) return null
        return StartTransferCommand(downloadId = id, tempFilePath = path)
    }
}
