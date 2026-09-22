package com.espitman.sdm.storage

/**
 * Durable download/destination identity for Files open and share.
 * UI rows keep this for intents and must not render [destinationPath].
 */
data class CompletedFileIdentity(
    val downloadId: String,
    val destinationPath: String,
    val persistedMimeType: String?,
    val fileName: String,
)
