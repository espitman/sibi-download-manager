package com.espitman.sdm.download

sealed interface TransferCommand {
    val downloadId: String
}

data class StartTransferCommand(
    override val downloadId: String,
    val tempFilePath: String,
) : TransferCommand

data class PauseTransferCommand(
    override val downloadId: String,
) : TransferCommand

data class ResumeTransferCommand(
    override val downloadId: String,
) : TransferCommand

object DownloadTransferCommand {
    const val ACTION_START_TRANSFER = "com.espitman.sdm.download.action.START_TRANSFER"
    const val ACTION_PAUSE_TRANSFER = "com.espitman.sdm.download.action.PAUSE_TRANSFER"
    const val ACTION_RESUME_TRANSFER = "com.espitman.sdm.download.action.RESUME_TRANSFER"
    const val EXTRA_DOWNLOAD_ID = "com.espitman.sdm.download.extra.DOWNLOAD_ID"
    const val EXTRA_TEMP_FILE_PATH = "com.espitman.sdm.download.extra.TEMP_FILE_PATH"

    fun parse(
        action: String?,
        downloadId: String?,
        tempFilePath: String?,
    ): TransferCommand? {
        val id = downloadId?.trim().orEmpty()
        if (id.isEmpty()) return null
        return when (action) {
            ACTION_START_TRANSFER -> {
                val path = tempFilePath?.trim().orEmpty()
                if (path.isEmpty()) null else StartTransferCommand(downloadId = id, tempFilePath = path)
            }
            ACTION_PAUSE_TRANSFER -> PauseTransferCommand(downloadId = id)
            ACTION_RESUME_TRANSFER -> ResumeTransferCommand(downloadId = id)
            else -> null
        }
    }
}
