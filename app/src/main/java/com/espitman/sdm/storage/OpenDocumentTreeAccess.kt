package com.espitman.sdm.storage

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Standard-system folder picker for user-selected destinations.
 *
 * Builds [Intent.ACTION_OPEN_DOCUMENT_TREE] with persistable read/write
 * (and prefix) grants. Launching the picker and storing the chosen URI
 * as the active save location is handled by [SaveLocationCoordinator].
 */
object OpenDocumentTreeAccess {
    fun createPickerIntent(initialTreeUriString: String? = null): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        val initial = initialUri(initialTreeUriString)
        if (initial != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        return intent
    }

    private fun initialUri(initialTreeUriString: String?): Uri? {
        val valid = StorageAccessPolicy.validateTreeUri(initialTreeUriString) as? TreeUriValidation.Valid
            ?: return null
        return Uri.parse(valid.uriString)
    }
}
