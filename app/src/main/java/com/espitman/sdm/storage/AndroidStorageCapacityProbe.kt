package com.espitman.sdm.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.File

class AndroidStorageCapacityProbe(
    context: Context,
) : StorageCapacityProbe {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    override fun queryLocalPath(path: File): StorageCapacity {
        val existing = generateSequence(path.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: return StorageCapacity.Unknown
        return runCatching {
            val stat = StatFs(existing.absolutePath)
            StorageCapacity.from(
                totalBytes = stat.totalBytes,
                availableBytes = stat.availableBytes,
            )
        }.getOrDefault(StorageCapacity.Unknown)
    }

    override fun queryTree(treeUri: String): StorageCapacity {
        val valid = StorageAccessPolicy.validateTreeUri(treeUri) as? TreeUriValidation.Valid
            ?: return StorageCapacity.Unknown
        val tree = Uri.parse(valid.uriString)
        val fromVolume = queryLocalVolume(tree)
        if (fromVolume != null && !fromVolume.isUnknown) return fromVolume
        return queryProviderRoot(tree)
    }

    private fun queryLocalVolume(tree: Uri): StorageCapacity? {
        val volume = resolveStorageVolume(tree) ?: return null
        val directory = directoryOf(volume) ?: return null
        return queryLocalPath(directory)
    }

    private fun resolveStorageVolume(tree: Uri): StorageVolume? {
        val manager = appContext.getSystemService(StorageManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { manager.getStorageVolume(tree) }.getOrNull()?.let { return it }
        }
        if (!ExternalStorageVolume.isExternalStorageAuthority(tree.authority)) return null
        val documentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: RuntimeException) {
            return null
        }
        val volumeId = ExternalStorageVolume.volumeIdFromDocumentId(documentId) ?: return null
        return manager.storageVolumes.firstOrNull { volume ->
            ExternalStorageVolume.matchesVolumeId(volumeId, volume.isPrimary, volume.uuid)
        }
    }

    private fun directoryOf(volume: StorageVolume): File? {
        val directoryFromVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            null
        }
        @Suppress("DEPRECATION")
        return ExternalStorageVolume.filesystemRoot(
            isPrimary = volume.isPrimary,
            uuid = volume.uuid,
            directoryFromVolume = directoryFromVolume,
            primaryExternalDirectory = Environment.getExternalStorageDirectory(),
        )
    }

    private fun queryProviderRoot(tree: Uri): StorageCapacity {
        val authority = tree.authority ?: return StorageCapacity.Unknown
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: RuntimeException) {
            return StorageCapacity.Unknown
        }
        if (treeDocumentId.isNullOrBlank()) return StorageCapacity.Unknown
        return try {
            contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                ROOT_PROJECTION,
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor == null) return StorageCapacity.Unknown
                val rootIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val availableIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                val capacityIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_CAPACITY_BYTES)
                while (cursor.moveToNext()) {
                    val rootId = optionalString(cursor, rootIdIndex)
                    val documentId = optionalString(cursor, documentIdIndex)
                    if (!rootMatchesTreeDocument(treeDocumentId, rootId, documentId)) continue
                    return StorageCapacity.from(
                        totalBytes = optionalNonNegativeLong(cursor, capacityIndex),
                        availableBytes = optionalNonNegativeLong(cursor, availableIndex),
                    )
                }
                StorageCapacity.Unknown
            }
        } catch (_: Exception) {
            StorageCapacity.Unknown
        }
    }

    private fun optionalString(cursor: Cursor, columnIndex: Int): String? {
        if (columnIndex < 0 || cursor.isNull(columnIndex)) return null
        return cursor.getString(columnIndex)?.takeIf { it.isNotBlank() }
    }

    private fun optionalNonNegativeLong(cursor: Cursor, columnIndex: Int): Long? {
        if (columnIndex < 0 || cursor.isNull(columnIndex)) return null
        return cursor.getLong(columnIndex).takeIf { it >= 0L }
    }

    companion object {
        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
        )
    }
}
