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
