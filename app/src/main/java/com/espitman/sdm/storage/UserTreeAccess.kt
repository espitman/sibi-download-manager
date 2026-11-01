package com.espitman.sdm.storage

import java.io.File

enum class UserTreeState {
    Writable,
    Missing,
    ProviderUnavailable,
    PermissionRevoked,
    NotWritable,
}

data class UserTreeInspection(
    val state: UserTreeState,
    val displayName: String? = null,
    val childDisplayNames: Set<String> = emptySet(),
)

data class CreatedTreeDocument(
    val documentUri: String,
    val displayName: String,
)

interface UserTreeAccess {
    fun inspect(treeUri: String): UserTreeInspection
    fun createFile(treeUri: String, mimeType: String, displayName: String): CreatedTreeDocument
    fun inspectDirectory(treeUri: String, parentDocumentUri: String?): UserTreeInspection =
        if (parentDocumentUri == null) inspect(treeUri) else UserTreeInspection(UserTreeState.NotWritable)
    fun createFileInDirectory(treeUri: String, parentDocumentUri: String?, mimeType: String, displayName: String): CreatedTreeDocument {
        require(parentDocumentUri == null) { "Provider subfolder access is unavailable" }
        return createFile(treeUri, mimeType, displayName)
    }
    fun writeFrom(documentUri: String, source: File)
    fun deleteDocument(documentUri: String): Boolean
}

interface TreeUriGrantStore {
    fun takeReadWrite(
        uriString: String,
        takeFlags: Int = StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
    ): PersistableGrantResult

    fun releaseReadWrite(uriString: String): PersistableGrantResult
    fun hasReadWrite(uriString: String): Boolean
}
