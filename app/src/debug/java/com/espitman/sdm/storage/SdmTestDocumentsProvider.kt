package com.espitman.sdm.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SdmTestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        documentsDir(context ?: return false).mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        enforceAccess()
        val columns = projection ?: DEFAULT_ROOT_COLUMNS
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        put(row, DocumentsContract.Root.COLUMN_ROOT_ID, queryRootId)
        put(row, DocumentsContract.Root.COLUMN_DOCUMENT_ID, queryRootDocumentId)
        put(row, DocumentsContract.Root.COLUMN_TITLE, "SDM Test Documents")
        put(row, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
        put(row, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        rootAvailableBytes?.let { put(row, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, it) }
        rootCapacityBytes?.let { put(row, DocumentsContract.Root.COLUMN_CAPACITY_BYTES, it) }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        enforceAccess()
        val columns = projection ?: DEFAULT_DOCUMENT_COLUMNS
        val cursor = MatrixCursor(columns)
        addDocument(cursor, requireDocument(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceAccess()
        require(parentDocumentId == ROOT_DOC_ID) { "Unknown parent $parentDocumentId" }
        val columns = projection ?: DEFAULT_DOCUMENT_COLUMNS
        val cursor = MatrixCursor(columns)
        for (document in catalog().values) {
            addDocument(cursor, document)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        enforceAccess()
        val document = requireDocument(documentId)
        val parsedMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(document.file, parsedMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        enforceAccess()
        require(parentDocumentId == ROOT_DOC_ID) { "Unknown parent $parentDocumentId" }
        if (catalog().values.any { it.displayName == displayName }) {
            throw IllegalArgumentException("A file with that name already exists")
        }
        val id = "doc-${UUID.randomUUID()}"
        val file = File(documentsDir(appContext()), id).apply { writeBytes(ByteArray(0)) }
        catalog()[id] = TestDocument(id, displayName, mimeType.ifBlank { "application/octet-stream" }, file)
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        enforceAccess()
        val current = requireDocument(documentId)
        if (catalog().values.any { it.id != documentId && it.displayName == displayName }) {
            throw IllegalArgumentException("A file with that name already exists")
        }
        val renamedId = "doc-${UUID.randomUUID()}"
        val renamedFile = File(documentsDir(appContext()), renamedId)
        check(current.file.renameTo(renamedFile) || (renamedFile.exists() && !current.file.exists())) {
            "Could not rename test document"
        }
        catalog().remove(documentId)
        catalog()[renamedId] = current.copy(id = renamedId, displayName = displayName, file = renamedFile)
        return renamedId
    }

    override fun deleteDocument(documentId: String) {
        enforceAccess()
        val current = catalog().remove(documentId) ?: throw java.io.FileNotFoundException(documentId)
        current.file.delete()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        enforceAccess()
        if (parentDocumentId != ROOT_DOC_ID) return false
        return documentId == ROOT_DOC_ID || catalog().containsKey(documentId)
    }

    private fun requireDocument(documentId: String): TestDocument {
        if (documentId == ROOT_DOC_ID) {
            return TestDocument(
                id = ROOT_DOC_ID,
                displayName = "SDM Test Documents",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                file = documentsDir(appContext()),
            )
        }
        return catalog()[documentId] ?: throw java.io.FileNotFoundException(documentId)
    }

    private fun addDocument(cursor: MatrixCursor, document: TestDocument) {
        val row = cursor.newRow()
        val flags = if (document.id == ROOT_DOC_ID) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        put(row, DocumentsContract.Document.COLUMN_DOCUMENT_ID, document.id)
        put(row, DocumentsContract.Document.COLUMN_DISPLAY_NAME, document.displayName)
        put(row, DocumentsContract.Document.COLUMN_MIME_TYPE, document.mimeType)
        put(row, DocumentsContract.Document.COLUMN_FLAGS, flags)
        put(row, DocumentsContract.Document.COLUMN_SIZE, if (document.file.isFile) document.file.length() else 0L)
        put(row, DocumentsContract.Document.COLUMN_LAST_MODIFIED, document.file.lastModified())
    }

    private fun put(row: MatrixCursor.RowBuilder, column: String, value: Any) {
        try {
            row.add(column, value)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun enforceAccess() {
        if (accessRevoked) throw SecurityException("Test document tree access was revoked")
    }

    private fun catalog(): ConcurrentHashMap<String, TestDocument> = DOCUMENTS

    private fun appContext(): Context = context ?: error("Provider is not attached")

    private data class TestDocument(
        val id: String,
        val displayName: String,
        val mimeType: String,
        val file: File,
    )

    companion object {
        const val AUTHORITY = "com.espitman.sdm.test.documents"
        const val ROOT_ID = "sdm-test-root"
        const val ROOT_DOC_ID = "root"
        private val DOCUMENTS = ConcurrentHashMap<String, TestDocument>()
        @Volatile var rootAvailableBytes: Long? = null
        @Volatile var rootCapacityBytes: Long? = null
        @Volatile var queryRootId: String = ROOT_ID
        @Volatile var queryRootDocumentId: String = ROOT_DOC_ID
        @Volatile var accessRevoked: Boolean = false
        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun documentsDir(context: Context): File = File(context.filesDir, "sdm-test-docs")

        // Tree URIs carry a document ID. COLUMN_ROOT_ID stays ROOT_ID in queryRoots.
        fun treeUri() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC_ID)

        fun treeUriFor(documentId: String) = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId)

        fun documentUri(documentId: String) =
            DocumentsContract.buildDocumentUriUsingTree(treeUri(), documentId)

        fun parentUri() = documentUri(ROOT_DOC_ID)

        fun reset(context: Context) {
            documentsDir(context).deleteRecursively()
            documentsDir(context).mkdirs()
            DOCUMENTS.clear()
            rootAvailableBytes = null
            rootCapacityBytes = null
            queryRootId = ROOT_ID
            queryRootDocumentId = ROOT_DOC_ID
            accessRevoked = false
        }
    }
}
