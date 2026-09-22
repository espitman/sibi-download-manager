package com.espitman.sdm.storage

import java.io.File
import java.net.URI
import java.net.URISyntaxException

sealed interface CompletedFileDestinationKind {
    data class ContentDocument(val uriString: String) : CompletedFileDestinationKind
    data class LocalFile(val file: File) : CompletedFileDestinationKind
    data object Unavailable : CompletedFileDestinationKind
}

object CompletedFileDestination {
    fun classify(destinationPath: String?): CompletedFileDestinationKind {
        val trimmed = destinationPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return CompletedFileDestinationKind.Unavailable
        if (DownloadDestinationRef.isContentUri(trimmed)) {
            return classifyContent(trimmed)
        }
        val file = localFile(trimmed) ?: return CompletedFileDestinationKind.Unavailable
        return CompletedFileDestinationKind.LocalFile(file)
    }

    fun localFile(destinationPath: String): File? {
        val trimmed = destinationPath.trim()
        if (trimmed.isEmpty()) return null
        if (DownloadDestinationRef.isContentUri(trimmed)) return null
        if (trimmed.startsWith("file:", ignoreCase = true)) {
            val uri = parseUri(trimmed) ?: return null
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
            return File(path)
        }
        if (hasNonFileScheme(trimmed)) return null
        return File(trimmed)
    }

    fun isShareableContentUri(uriString: String): Boolean {
        val uri = parseUri(uriString.trim()) ?: return false
        return uri.scheme.equals("content", ignoreCase = true) && !uri.rawAuthority.isNullOrBlank()
    }

    private fun classifyContent(uriString: String): CompletedFileDestinationKind {
        return if (isShareableContentUri(uriString)) {
            CompletedFileDestinationKind.ContentDocument(uriString)
        } else {
            CompletedFileDestinationKind.Unavailable
        }
    }

    private fun hasNonFileScheme(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0) return false
        val scheme = value.substring(0, colon)
        return scheme.all { it.isLetter() } && !scheme.equals("file", ignoreCase = true)
    }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }
}
