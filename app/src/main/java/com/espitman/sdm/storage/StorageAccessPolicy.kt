package com.espitman.sdm.storage

import java.net.URI
import java.net.URISyntaxException

/**
 * Hybrid storage policy for API 26–35.
 *
 * Default downloads stay in the app-specific external Downloads directory
 * (no storage permission). User-selected folders use
 * [OPEN_DOCUMENT_TREE_ACTION] plus persistable read/write URI grants.
 * Broad media/storage permissions are not part of this model.
 */
object StorageAccessPolicy {
    const val OPEN_DOCUMENT_TREE_ACTION = "android.intent.action.OPEN_DOCUMENT_TREE"
    const val EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI"

    /** Matches [android.app.Activity.RESULT_OK]. */
    const val RESULT_OK = -1

    /** Matches [android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION]. */
    const val GRANT_READ = 0x00000001

    /** Matches [android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION]. */
    const val GRANT_WRITE = 0x00000002

    /** Matches [android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION]. */
    const val GRANT_PERSISTABLE = 0x00000040

    /** Matches [android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION]. */
    const val GRANT_PREFIX = 0x00000080

    const val PICKER_INTENT_FLAGS = GRANT_READ or GRANT_WRITE or GRANT_PERSISTABLE or GRANT_PREFIX
    const val TAKE_PERSISTABLE_FLAGS = GRANT_READ or GRANT_WRITE

    val DISALLOWED_BROAD_STORAGE_PERMISSIONS = listOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.MANAGE_DOCUMENTS",
    )

    fun validateTreeUri(uriString: String?): TreeUriValidation {
        if (uriString.isNullOrBlank()) {
            return TreeUriValidation.Invalid("URI is missing")
        }
        val trimmed = uriString.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return TreeUriValidation.Invalid("URI is malformed")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("content", ignoreCase = true)) {
            return TreeUriValidation.Invalid("Folder access requires a content tree URI")
        }
        if (uri.rawAuthority.isNullOrBlank()) {
            return TreeUriValidation.Invalid("Tree URI is missing an authority")
        }
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (segments.size != 2 || segments[0] != "tree" || segments[1].isBlank()) {
            return TreeUriValidation.Invalid("URI is not an OpenDocumentTree folder tree")
        }
        return TreeUriValidation.Valid(trimmed)
    }

    fun classifyConfiguredLocation(persistedTreeUri: String?): StorageLocation {
        if (persistedTreeUri.isNullOrBlank()) {
            return StorageLocation.AppSpecificDownloads
        }
        return when (val validation = validateTreeUri(persistedTreeUri)) {
            is TreeUriValidation.Valid -> StorageLocation.UserSelectedTree(validation.uriString)
            is TreeUriValidation.Invalid -> StorageLocation.InvalidConfiguredUri(
                persistedTreeUri.trim(),
                validation.reason,
            )
        }
    }

    fun hasReadAndWrite(flags: Int): Boolean =
        flags and GRANT_READ != 0 && flags and GRANT_WRITE != 0

    fun takeFlagsForResult(returnedGrantFlags: Int): Int {
        val masked = returnedGrantFlags and TAKE_PERSISTABLE_FLAGS
        return if (hasReadAndWrite(masked)) masked else TAKE_PERSISTABLE_FLAGS
    }

    fun interpretPickerResult(
        resultCode: Int,
        uriString: String?,
        returnedGrantFlags: Int = 0,
    ): OpenDocumentTreeOutcome {
        if (resultCode != RESULT_OK) return OpenDocumentTreeOutcome.Canceled
        if (uriString.isNullOrBlank()) return OpenDocumentTreeOutcome.Canceled
        return when (val validation = validateTreeUri(uriString)) {
            is TreeUriValidation.Invalid -> OpenDocumentTreeOutcome.InvalidTree(validation.reason)
            is TreeUriValidation.Valid -> OpenDocumentTreeOutcome.Accepted(
                uriString = validation.uriString,
                takeFlags = takeFlagsForResult(returnedGrantFlags),
            )
        }
    }
}

sealed interface TreeUriValidation {
    data class Valid(val uriString: String) : TreeUriValidation
    data class Invalid(val reason: String) : TreeUriValidation
}

sealed interface StorageLocation {
    data object AppSpecificDownloads : StorageLocation
    data class UserSelectedTree(val uriString: String) : StorageLocation
    data class InvalidConfiguredUri(val uriString: String, val reason: String) : StorageLocation
}

sealed interface OpenDocumentTreeOutcome {
    data object Canceled : OpenDocumentTreeOutcome
    data class InvalidTree(val reason: String) : OpenDocumentTreeOutcome
    data class Accepted(val uriString: String, val takeFlags: Int) : OpenDocumentTreeOutcome
}
