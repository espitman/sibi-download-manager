package com.espitman.sdm.storage

import java.io.File

enum class CompletedDestinationPresence {
    Readable,
    Missing,
    AccessUnavailable,
}

object CompletedDestinationAccess {
    fun classifyDownload(download: com.espitman.sdm.domain.Download, documents: ContentDocumentStore? = null): CompletedDestinationPresence {
        if (!download.isTorrent) return classify(download.destinationPath, download.destinationTreeUri, documents)
        val path = download.destinationPath ?: return CompletedDestinationPresence.Missing
        if (DownloadDestinationRef.isContentUri(path)) return documents?.directoryPresence(path, download.destinationTreeUri) ?: CompletedDestinationPresence.AccessUnavailable
        return try {
            val folder = File(path)
            when { !folder.isDirectory -> CompletedDestinationPresence.Missing
                folder.canRead() -> CompletedDestinationPresence.Readable
                else -> CompletedDestinationPresence.AccessUnavailable }
        } catch (_: SecurityException) { CompletedDestinationPresence.AccessUnavailable }
    }
    fun classify(
        destinationPath: String?,
        treeUri: String? = null,
        contentDocuments: ContentDocumentStore? = null,
    ): CompletedDestinationPresence {
        return when (val kind = CompletedFileDestination.classify(destinationPath)) {
            CompletedFileDestinationKind.Unavailable -> CompletedDestinationPresence.Missing
            is CompletedFileDestinationKind.LocalFile -> classifyLocal(kind.file)
            is CompletedFileDestinationKind.ContentDocument -> {
                val store = contentDocuments ?: return CompletedDestinationPresence.AccessUnavailable
                store.presence(kind.uriString, treeUri)
            }
        }
    }

    fun classifyLocal(file: File): CompletedDestinationPresence {
        return try {
            when {
                !file.exists() -> CompletedDestinationPresence.Missing
                !file.isFile -> CompletedDestinationPresence.Missing
                file.canRead() -> CompletedDestinationPresence.Readable
                else -> CompletedDestinationPresence.AccessUnavailable
            }
        } catch (_: SecurityException) {
            CompletedDestinationPresence.AccessUnavailable
        }
    }
}
