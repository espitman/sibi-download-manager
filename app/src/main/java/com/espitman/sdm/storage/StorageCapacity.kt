package com.espitman.sdm.storage

/**
 * Capacity of a save location filesystem or document-provider root.
 *
 * Missing fields stay unknown rather than inventing figures from another volume.
 * Total and used are populated only when they can be derived accurately.
 */
data class StorageCapacity(
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val usedBytes: Long? = null,
) {
    val isUnknown: Boolean
        get() = totalBytes == null && availableBytes == null && usedBytes == null

    companion object {
        val Unknown = StorageCapacity()

        fun from(totalBytes: Long?, availableBytes: Long?): StorageCapacity {
            val total = totalBytes.nonNegativeOrNull()
            val available = availableBytes.nonNegativeOrNull()
            val used = StorageBytes.used(total, available)
            if (total == null && available == null && used == null) return Unknown
            return StorageCapacity(
                totalBytes = total,
                availableBytes = available,
                usedBytes = used,
            )
        }
    }
}

object StorageBytes {
    fun remaining(totalBytes: Long, existingValidBytes: Long): Long {
        val total = totalBytes.coerceAtLeast(0L)
        val existing = existingValidBytes.coerceAtLeast(0L)
        return if (existing >= total) 0L else total - existing
    }

    fun used(totalBytes: Long?, availableBytes: Long?): Long? {
        val total = totalBytes ?: return null
        val available = availableBytes ?: return null
        if (total < 0L || available < 0L) return null
        if (available > total) return null
        return total - available
    }

    fun hasSufficient(availableBytes: Long, requiredBytes: Long): Boolean {
        if (availableBytes < 0L || requiredBytes < 0L) return false
        return availableBytes >= requiredBytes
    }
}

/**
 * True when [treeDocumentId] is the root itself or a document under it.
 *
 * ExternalStorageProvider roots are typically `primary:` while a selected
 * folder is `primary:Download`. Matching requires a document-id path boundary
 * (`:` or `/`) so `primary` does not match `primary2:` or `primary:Down` vs
 * `primary:Download`.
 */
internal fun rootMatchesTreeDocument(
    treeDocumentId: String,
    rootId: String?,
    rootDocumentId: String?,
): Boolean {
    if (treeDocumentId.isBlank()) return false
    return isSameDocumentOrDescendant(treeDocumentId, rootDocumentId) ||
        isSameDocumentOrDescendant(treeDocumentId, rootId)
}

internal fun isSameDocumentOrDescendant(documentId: String, ancestorId: String?): Boolean {
    if (ancestorId.isNullOrBlank()) return false
    if (documentId == ancestorId) return true
    if (!documentId.startsWith(ancestorId)) return false
    if (ancestorId.endsWith(':') || ancestorId.endsWith('/')) {
        return documentId.length > ancestorId.length
    }
    val next = documentId[ancestorId.length]
    return next == ':' || next == '/'
}

private fun Long?.nonNegativeOrNull(): Long? = this?.takeIf { it >= 0L }
