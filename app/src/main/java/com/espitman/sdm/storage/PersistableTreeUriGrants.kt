package com.espitman.sdm.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Persistable read/write grants for an OpenDocumentTree folder URI.
 *
 * Survives process death while the system still holds the grant.
 */
class PersistableTreeUriGrants(private val contentResolver: ContentResolver) : TreeUriGrantStore {
    fun takeReadWrite(uriString: String): PersistableGrantResult =
        takeReadWrite(uriString, StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS)

    override fun takeReadWrite(
        uriString: String,
        takeFlags: Int,
    ): PersistableGrantResult {
        val valid = validated(uriString) ?: return invalid(uriString)
        val uri = Uri.parse(valid)
        if (!StorageAccessPolicy.hasReadAndWrite(StorageAccessPolicy.takeFlagsForResult(takeFlags))) {
            return PersistableGrantResult.Failure(valid, "Persistable URI grant was rejected")
        }
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            if (hasReadWrite(valid)) {
                PersistableGrantResult.Success(valid)
            } else {
                PersistableGrantResult.Failure(valid, "Persistable read/write grant was not recorded")
            }
        } catch (error: SecurityException) {
            PersistableGrantResult.Failure(valid, "Persistable URI grant was denied", error)
        } catch (error: IllegalArgumentException) {
            PersistableGrantResult.Failure(valid, "Persistable URI grant was rejected", error)
        }
    }

    override fun releaseReadWrite(uriString: String): PersistableGrantResult {
        val valid = validated(uriString) ?: return invalid(uriString)
        if (!hasReadWrite(valid)) return PersistableGrantResult.Success(valid)
        val uri = Uri.parse(valid)
        return try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            PersistableGrantResult.Success(valid)
        } catch (error: SecurityException) {
            if (!hasReadWrite(valid)) {
                PersistableGrantResult.Success(valid)
            } else {
                PersistableGrantResult.Failure(valid, "Persistable URI grant could not be released", error)
            }
        }
    }

    override fun hasReadWrite(uriString: String): Boolean {
        val valid = validated(uriString) ?: return false
        val uri = Uri.parse(valid)
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun persistedReadWriteTrees(): List<String> =
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri.toString() }
            .filter { StorageAccessPolicy.validateTreeUri(it) is TreeUriValidation.Valid }

    private fun validated(uriString: String): String? =
        (StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid)?.uriString

    private fun invalid(uriString: String): PersistableGrantResult.InvalidTree {
        val reason = (StorageAccessPolicy.validateTreeUri(uriString) as TreeUriValidation.Invalid).reason
        return PersistableGrantResult.InvalidTree(uriString, reason)
    }
}

sealed interface PersistableGrantResult {
    data class Success(val uriString: String) : PersistableGrantResult
    data class InvalidTree(val uriString: String, val reason: String) : PersistableGrantResult
    data class Failure(
        val uriString: String,
        val message: String,
        val cause: Throwable? = null,
    ) : PersistableGrantResult
}
