package com.espitman.sdm.download

import java.io.File

object DownloadResumePart {
    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Failed(val error: String) : Result()
    }

    fun resolve(destinationPath: String?): Result {
        if (destinationPath.isNullOrBlank()) {
            return Result.Failed("Destination path is missing")
        }
        val partFile = try {
            DownloadPartFile.forDestination(File(destinationPath))
        } catch (thrown: IllegalArgumentException) {
            return Result.Failed(
                thrown.message?.takeIf { it.isNotBlank() } ?: "Incomplete download part is missing",
            )
        }
        if (!partFile.exists()) {
            return Result.Failed("Incomplete download part is missing")
        }
        if (partFile.length() <= 0L) {
            return Result.Failed("Incomplete download part is empty")
        }
        return Result.Ready(partFile)
    }
}
