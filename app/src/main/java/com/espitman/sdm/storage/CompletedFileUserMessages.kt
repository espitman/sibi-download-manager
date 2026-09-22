package com.espitman.sdm.storage

object CompletedFileUserMessages {
    const val DOWNLOAD_NOT_FOUND = "Download not found"
    const val DESTINATION_REQUIRED = "Destination path is required"
    const val FILENAME_BLANK = "Filename cannot be blank"
    const val FILENAME_SEPARATOR = "Filename cannot contain path separators"
    const val FILENAME_UNSAFE = "Filename is unsafe"
    const val COLLISION = "A file with that name already exists"
    const val MISSING = "Completed file is missing"
    const val ACCESS_UNAVAILABLE = "File access is unavailable"
    const val RENAME_FAILED = "Could not rename file"
    const val RECORD_UPDATE_FAILED = "Could not update download record"
    const val DELETED = "File deleted"
    const val ALREADY_DELETED = "File was already deleted"
    const val DELETE_FAILED = "Could not delete file"
    const val DELETE_INCONSISTENT = "File was removed, but the download record could not be updated"
}
