package com.espitman.sdm.storage

import java.util.Locale

object CompletedFileMime {
    const val OCTET_STREAM = "application/octet-stream"

    fun resolve(
        contentResolverType: String?,
        persistedMimeType: String?,
        fileName: String,
        extensionMime: (String) -> String?,
    ): String {
        specific(contentResolverType)?.let { return it }
        specific(persistedMimeType)?.let { return it }
        val extension = extensionOf(fileName)
        if (extension != null) {
            specific(extensionMime(extension))?.let { return it }
        }
        return OCTET_STREAM
    }

    fun normalize(mimeType: String?): String? {
        val raw = mimeType?.trim()?.lowercase(Locale.US) ?: return null
        if (raw.isEmpty()) return null
        val withoutParams = raw.substringBefore(';').trim()
        return withoutParams.takeIf { it.isNotEmpty() }
    }

    fun isSpecific(mimeType: String): Boolean {
        if (mimeType.isEmpty()) return false
        if (mimeType == "*/*") return false
        val slash = mimeType.indexOf('/')
        if (slash <= 0 || slash == mimeType.lastIndex) return false
        val subtype = mimeType.substring(slash + 1)
        if (subtype.isEmpty() || subtype == "*") return false
        if ('/' in subtype) return false
        if (mimeType in GENERIC_BINARY_MIMES) return false
        return true
    }

    fun extensionOf(fileName: String): String? {
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.lastIndex) return null
        val extension = trimmed.substring(dot + 1).lowercase(Locale.US)
        return extension.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
    }

    private fun specific(mimeType: String?): String? {
        val normalized = normalize(mimeType) ?: return null
        return normalized.takeIf(::isSpecific)
    }

    private val GENERIC_BINARY_MIMES = setOf(
        OCTET_STREAM,
        "binary/octet-stream",
        "application/binary",
        "application/force-download",
        "application/x-download",
        "application/download",
        "application/unknown",
    )
}
