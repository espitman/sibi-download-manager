package com.espitman.sdm.storage

import java.net.URI
import java.net.URLDecoder

object SaveLocationLabels {
    const val DEFAULT = "/Download/SDM"
    const val SELECTED_FOLDER_FALLBACK = "Selected folder"

    fun fromTree(treeUri: String, queriedDisplayName: String?): String {
        val queried = queriedDisplayName?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
        if (queried != null) return queried
        val valid = StorageAccessPolicy.validateTreeUri(treeUri) as? TreeUriValidation.Valid
            ?: return SELECTED_FOLDER_FALLBACK
        val rawPath = try {
            URI(valid.uriString).rawPath
        } catch (_: Exception) {
            return SELECTED_FOLDER_FALLBACK
        } ?: return SELECTED_FOLDER_FALLBACK
        val treeId = rawPath.split('/').filter { it.isNotEmpty() }.getOrNull(1)
            ?: return SELECTED_FOLDER_FALLBACK
        val decoded = try {
            URLDecoder.decode(treeId, Charsets.UTF_8.name())
        } catch (_: Exception) {
            treeId
        }
        val relative = decoded.substringAfter(':', missingDelimiterValue = decoded)
            .trim('/')
            .replace('\\', '/')
        return if (relative.isBlank()) SELECTED_FOLDER_FALLBACK else "/$relative"
    }
}
