package com.espitman.sdm.storage

import android.content.ContentResolver
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

class DocumentsContractTreeAccess(
    private val contentResolver: ContentResolver,
) : UserTreeAccess {
    override fun inspect(treeUri: String): UserTreeInspection {
        val valid = StorageAccessPolicy.validateTreeUri(treeUri) as? TreeUriValidation.Valid
            ?: return UserTreeInspection(UserTreeState.Missing)
        val tree = android.net.Uri.parse(valid.uriString)
        val documentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        } catch (_: RuntimeException) {
            return UserTreeInspection(UserTreeState.Missing)
        }
        val displayName = try {
            queryDisplayName(documentUri)
        } catch (denied: SecurityException) {
            return UserTreeInspection(UserTreeState.PermissionRevoked)
        } catch (_: FileNotFoundException) {
            return UserTreeInspection(UserTreeState.Missing)
        } catch (_: IllegalArgumentException) {
            return UserTreeInspection(UserTreeState.Missing)
        } catch (_: UnsupportedOperationException) {
            return UserTreeInspection(UserTreeState.ProviderUnavailable)
        } catch (_: IllegalStateException) {
            return UserTreeInspection(UserTreeState.ProviderUnavailable)
        } catch (_: Exception) {
            return UserTreeInspection(UserTreeState.ProviderUnavailable)
        }

        val children = try {
            queryChildNames(tree)
        } catch (_: SecurityException) {
            return UserTreeInspection(UserTreeState.PermissionRevoked, displayName)
        } catch (_: Exception) {
            emptySet()
        }

        return if (canCreateAndDeleteProbe(documentUri)) {
            UserTreeInspection(UserTreeState.Writable, displayName, children)
        } else {
            UserTreeInspection(UserTreeState.NotWritable, displayName, children)
        }
    }

    override fun createFile(treeUri: String, mimeType: String, displayName: String): CreatedTreeDocument {
        val valid = StorageAccessPolicy.validateTreeUri(treeUri) as? TreeUriValidation.Valid
            ?: throw IOException("Folder access requires a content tree URI")
        val tree = android.net.Uri.parse(valid.uriString)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val created = try {
            DocumentsContract.createDocument(
                contentResolver,
                parent,
                mimeType.ifBlank { "application/octet-stream" },
                displayName,
            )
        } catch (denied: SecurityException) {
            throw IOException("Folder access was revoked", denied)
        } catch (error: RuntimeException) {
            throw IOException("Could not create $displayName in the selected folder", error)
        } ?: throw IOException("Could not create $displayName in the selected folder")
        return CreatedTreeDocument(created.toString(), displayName)
    }

    override fun writeFrom(documentUri: String, source: File) {
        val uri = android.net.Uri.parse(documentUri)
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not write to the selected folder")
        } catch (denied: SecurityException) {
            throw IOException("Folder access was revoked", denied)
        } catch (error: FileNotFoundException) {
            throw IOException("Selected folder was deleted", error)
        }
    }

    override fun deleteDocument(documentUri: String): Boolean {
        return try {
            DocumentsContract.deleteDocument(contentResolver, android.net.Uri.parse(documentUri))
        } catch (_: Exception) {
            false
        }
    }

    private fun queryDisplayName(documentUri: android.net.Uri): String? {
        contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null) throw IllegalStateException("Tree document query returned null")
            if (!cursor.moveToFirst()) throw FileNotFoundException(documentUri.toString())
            return cursor.getString(0)
        }
    }

    private fun queryChildNames(tree: android.net.Uri): Set<String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null) return emptySet()
            val names = LinkedHashSet<String>()
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0) return emptySet()
            while (cursor.moveToNext()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let(names::add)
            }
            return names
        }
    }

    private fun canCreateAndDeleteProbe(parent: android.net.Uri): Boolean {
        val probeName = ".sdm-write-probe-${PROBE_RANDOM.nextInt(Int.MAX_VALUE)}"
        val created = try {
            DocumentsContract.createDocument(
                contentResolver,
                parent,
                "application/octet-stream",
                probeName,
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            return false
        } ?: return false
        try {
            DocumentsContract.deleteDocument(contentResolver, created)
        } catch (_: Exception) {
            // Probe leftover is harmless; create succeeding is enough to treat as writable.
        }
        return true
    }

    companion object {
        private val PROBE_RANDOM = SecureRandom()
    }
}
