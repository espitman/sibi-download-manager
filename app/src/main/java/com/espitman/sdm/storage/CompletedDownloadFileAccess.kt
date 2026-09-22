package com.espitman.sdm.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException

fun interface CompletedFileProbe {
    fun isReadableDocument(destinationPath: String): Boolean
}

object FilesystemCompletedFileProbe : CompletedFileProbe {
    override fun isReadableDocument(destinationPath: String): Boolean {
        if (DownloadDestinationRef.isContentUri(destinationPath)) return false
        return isReadableLocalFile(destinationPath)
    }
}

class ContentResolverCompletedFileProbe(
    private val contentResolver: ContentResolver,
) : CompletedFileProbe {
    constructor(context: Context) : this(context.applicationContext.contentResolver)

    override fun isReadableDocument(destinationPath: String): Boolean {
        if (DownloadDestinationRef.isContentUri(destinationPath)) {
            return isReadableContentDocument(contentResolver, destinationPath)
        }
        return isReadableLocalFile(destinationPath)
    }
}

internal fun isReadableLocalFile(destinationPath: String): Boolean {
    if (destinationPath.isBlank()) return false
    val file = File(destinationPath)
    return file.isFile && file.canRead()
}

internal fun isReadableContentDocument(
    contentResolver: ContentResolver,
    destinationPath: String,
): Boolean {
    val uri = runCatching { Uri.parse(destinationPath.trim()) }.getOrNull() ?: return false
    if (uri.scheme.isNullOrBlank() || !uri.scheme.equals("content", ignoreCase = true)) return false
    if (uri.authority.isNullOrBlank()) return false
    return try {
        if (isDirectoryDocument(contentResolver, uri)) return false
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: FileNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: Exception) {
        false
    }
}

private fun isDirectoryDocument(contentResolver: ContentResolver, uri: Uri): Boolean {
    val queried = try {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    } catch (_: Exception) {
        null
    }
    val mime = queried ?: contentResolver.getType(uri)
    return mime.equals(DocumentsContract.Document.MIME_TYPE_DIR, ignoreCase = true)
}
