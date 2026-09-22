package com.espitman.sdm.storage

import java.io.File

/**
 * Maps ExternalStorageProvider tree document IDs to a real [android.os.storage.StorageVolume]
 * filesystem. Document IDs look like `primary:Download/SDM-QA` or `{uuid}:DCIM`.
 */
object ExternalStorageVolume {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_ID = "primary"

    fun isExternalStorageAuthority(authority: String?): Boolean =
        authority.equals(AUTHORITY, ignoreCase = true)

    fun volumeIdFromDocumentId(documentId: String?): String? {
        if (documentId.isNullOrBlank()) return null
        val volumeId = documentId.substringBefore(':', missingDelimiterValue = documentId).trim()
        return volumeId.takeIf { it.isNotEmpty() }
    }

    fun matchesVolumeId(volumeId: String, isPrimary: Boolean, uuid: String?): Boolean {
        if (volumeId.equals(PRIMARY_ID, ignoreCase = true)) return isPrimary
        val volumeUuid = uuid?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return volumeId.equals(volumeUuid, ignoreCase = true)
    }

    fun filesystemRoot(
        isPrimary: Boolean,
        uuid: String?,
        directoryFromVolume: File?,
        primaryExternalDirectory: File,
        exists: (File) -> Boolean = File::exists,
        removableCandidates: (String) -> List<File> = defaultRemovableCandidates,
    ): File? {
        directoryFromVolume?.takeIf(exists)?.let { return it }
        if (isPrimary) {
            return primaryExternalDirectory.takeIf(exists)
        }
        val volumeUuid = uuid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return removableCandidates(volumeUuid).firstOrNull(exists)
    }

    val defaultRemovableCandidates: (String) -> List<File> = { id ->
        listOf(File("/storage/$id"), File("/mnt/media_rw/$id"))
    }
}
