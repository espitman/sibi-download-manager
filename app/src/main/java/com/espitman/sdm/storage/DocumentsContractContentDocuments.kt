package com.espitman.sdm.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

class DocumentsContractContentDocuments(
    private val contentResolver: ContentResolver,
    private val hasTreeWriteGrant: (String) -> Boolean,
) : ContentDocumentStore {
    constructor(context: Context) : this(
        contentResolver = context.applicationContext.contentResolver,
        hasTreeWriteGrant = PersistableTreeUriGrants(context.applicationContext.contentResolver)::hasReadWrite,
    )

    override fun presence(documentUri: String, treeUri: String?): CompletedDestinationPresence {
        if (!hasWriteAccess(treeUri)) {
            return CompletedDestinationPresence.AccessUnavailable
        }
        val uri = parseDocumentUri(documentUri)
            ?: return CompletedDestinationPresence.Missing
        return try {
            if (isDirectoryDocument(uri)) return CompletedDestinationPresence.Missing
            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                CompletedDestinationPresence.Readable
            } ?: CompletedDestinationPresence.Missing
        } catch (denied: SecurityException) {
            CompletedDestinationPresence.AccessUnavailable
        } catch (_: FileNotFoundException) {
            CompletedDestinationPresence.Missing
        } catch (_: IllegalArgumentException) {
            CompletedDestinationPresence.Missing
        } catch (_: UnsupportedOperationException) {
            CompletedDestinationPresence.AccessUnavailable
        } catch (_: IllegalStateException) {
            CompletedDestinationPresence.AccessUnavailable
        } catch (_: Exception) {
            CompletedDestinationPresence.AccessUnavailable
        }
    }

    override fun rename(
        documentUri: String,
        treeUri: String?,
        displayName: String,
    ): ContentDocumentMutation {
        if (!hasWriteAccess(treeUri)) {
            return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
        }
        val uri = parseDocumentUri(documentUri)
            ?: return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
        when (presence(documentUri, treeUri)) {
            CompletedDestinationPresence.Missing ->
                return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
            CompletedDestinationPresence.AccessUnavailable ->
                return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
            CompletedDestinationPresence.Readable -> Unit
        }
        if (childDisplayNameExists(treeUri, displayName, excluding = uri)) {
            return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Collision)
        }
        return try {
            val renamed = DocumentsContract.renameDocument(contentResolver, uri, displayName)
                ?: return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Refused)
            ContentDocumentMutation.Success(renamed.toString(), displayName)
        } catch (denied: SecurityException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, denied)
        } catch (_: FileNotFoundException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
        } catch (refused: IllegalArgumentException) {
            if (looksLikeCollision(refused)) {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Collision, refused)
            } else {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Refused, refused)
            }
        } catch (unavailable: UnsupportedOperationException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, unavailable)
        } catch (unavailable: IllegalStateException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, unavailable)
        } catch (failure: Exception) {
            if (looksLikeCollision(failure)) {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Collision, failure)
            } else {
                ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Generic, failure)
            }
        }
    }

    override fun delete(documentUri: String, treeUri: String?): ContentDocumentMutation {
        if (!hasWriteAccess(treeUri)) {
            return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
        }
        val uri = parseDocumentUri(documentUri)
            ?: return ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
        return try {
            val deleted = DocumentsContract.deleteDocument(contentResolver, uri)
            if (deleted) {
                ContentDocumentMutation.Success(documentUri, "")
            } else {
                when (presence(documentUri, treeUri)) {
                    CompletedDestinationPresence.Missing ->
                        ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
                    CompletedDestinationPresence.AccessUnavailable ->
                        ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable)
                    CompletedDestinationPresence.Readable ->
                        ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Refused)
                }
            }
        } catch (denied: SecurityException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, denied)
        } catch (_: FileNotFoundException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Missing)
        } catch (unavailable: UnsupportedOperationException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, unavailable)
        } catch (unavailable: IllegalStateException) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.AccessUnavailable, unavailable)
        } catch (failure: Exception) {
            ContentDocumentMutation.Failure(ContentDocumentMutation.Failure.Reason.Generic, failure)
        }
    }

    private fun hasWriteAccess(treeUri: String?): Boolean {
        val tree = treeUri?.trim().orEmpty()
        if (tree.isEmpty()) return false
        return try {
            hasTreeWriteGrant(tree)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun childDisplayNameExists(
        treeUri: String?,
        displayName: String,
        excluding: Uri,
    ): Boolean {
        val tree = treeUri?.trim().orEmpty()
        if (tree.isEmpty()) return false
        val treeParsed = runCatching { Uri.parse(tree) }.getOrNull() ?: return false
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeParsed,
                DocumentsContract.getTreeDocumentId(treeParsed),
            )
        } catch (_: RuntimeException) {
            return false
        }
        return try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor == null) return@use false
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex < 0) return@use false
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    if (!name.equals(displayName, ignoreCase = false)) continue
                    val documentId = if (idIndex >= 0) cursor.getString(idIndex) else null
                    val childUri = try {
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeParsed,
                            documentId ?: continue,
                        )
                    } catch (_: RuntimeException) {
                        continue
                    }
                    if (childUri != excluding) return@use true
                }
                false
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isDirectoryDocument(uri: Uri): Boolean {
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

    private fun parseDocumentUri(documentUri: String): Uri? {
        val trimmed = documentUri.trim()
        if (!CompletedFileDestination.isShareableContentUri(trimmed)) return null
        return runCatching { Uri.parse(trimmed) }.getOrNull()
    }

    private fun looksLikeCollision(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return "exist" in message || "collision" in message || "already" in message
    }
}
