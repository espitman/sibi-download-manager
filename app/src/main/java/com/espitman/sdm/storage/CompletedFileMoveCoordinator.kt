package com.espitman.sdm.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Copies with bounded memory, verifies the new file, then commits the record before removing the source. */
object CompletedFileMoveCoordinator {
    data class Target(val folder: File? = null, val tree: String? = null, val document: Uri? = null, val label: String)
    suspend fun move(context: Context, id: String, target: Target, repository: DownloadRepository): String = withContext(Dispatchers.IO) {
        val record = repository.get(id) ?: throw IllegalArgumentException("File no longer exists")
        require(record.state == DownloadState.COMPLETED && !record.isTorrent) { "Select a completed file" }
        val source = record.destinationPath ?: throw IllegalArgumentException("File is unavailable")
        val resolver = context.contentResolver
        val documents = DocumentsContractContentDocuments(context)
        val contentSource = DownloadDestinationRef.isContentUri(source)
        if (contentSource) require(documents.presence(source, record.destinationTreeUri) == CompletedDestinationPresence.Readable) { "Folder access was removed" }
        else require(File(source).isFile && File(source).canRead()) { "File is unavailable" }
        if (target.folder != null && !contentSource && File(source).parentFile?.canonicalFile == target.folder.canonicalFile) return@withContext record.fileName
        target.tree?.let { require(PersistableTreeUriGrants(resolver).hasReadWrite(it)) { "Grant folder access on your phone" } }
        val capacity = if (target.folder != null) AndroidStorageCapacityProbe(context).queryLocalPath(target.folder)
            else AndroidStorageCapacityProbe(context).queryTree(target.tree!!)
        capacity.availableBytes?.let { require(it >= record.downloadedBytes) { "Not enough storage in the selected folder" } }
        val tempName = ".sdm-move-${UUID.randomUUID()}.part"
        var newLocation: String? = null
        var committed = false
        fun input(location: String): InputStream = if (DownloadDestinationRef.isContentUri(location))
            resolver.openInputStream(Uri.parse(location)) ?: throw IllegalArgumentException("File is unavailable") else File(location).inputStream()
        fun deleteNew() { newLocation?.let { location -> runCatching {
            if (DownloadDestinationRef.isContentUri(location)) DC.deleteDocument(resolver, Uri.parse(location)) else File(location).delete()
        } } }
        fun childExists(name: String): Boolean {
            if (target.folder != null) return File(target.folder, name).exists()
            val parent = target.document ?: throw IllegalArgumentException("Folder is unavailable")
            val children = DC.buildChildDocumentsUriUsingTree(parent, DC.getDocumentId(parent))
            resolver.query(children, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
                require(cursor != null) { "Folder is unavailable" }
                while (cursor.moveToNext()) if (cursor.getString(0) == name) return true
            }
            return false
        }
        try {
            require(!childExists(record.fileName)) { "A file with that name already exists" }
            newLocation = if (target.folder != null) {
                val file = File(target.folder, tempName)
                check(file.createNewFile()) { "Unable to create file" }; file.path
            } else DC.createDocument(resolver, target.document!!, record.mimeType ?: "application/octet-stream", tempName)?.toString()
                ?: throw IllegalArgumentException("Unable to create file")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val output = if (DownloadDestinationRef.isContentUri(newLocation!!)) resolver.openOutputStream(Uri.parse(newLocation), "w")
                ?: throw IllegalArgumentException("Unable to write file") else File(newLocation!!).outputStream()
            output.use { out -> input(source).use { sourceInput ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = sourceInput.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    out.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count
                }
                out.flush()
            } }
            require(copied == record.downloadedBytes) { "File changed while moving. Try again" }
            val expected = digest.digest()
            val actual = MessageDigest.getInstance("SHA-256")
            input(newLocation!!).use { checkInput ->
                val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val count = checkInput.read(buffer); if (count < 0) break; if (count > 0) actual.update(buffer, 0, count) }
            }
            check(MessageDigest.isEqual(expected, actual.digest())) { "File copy could not be verified" }
            val sourceNow = MessageDigest.getInstance("SHA-256")
            input(source).use { checkInput ->
                val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val count = checkInput.read(buffer); if (count < 0) break; if (count > 0) sourceNow.update(buffer, 0, count) }
            }
            require(MessageDigest.isEqual(expected, sourceNow.digest())) { "File changed while moving. Try again" }
            require(!childExists(record.fileName)) { "A file with that name already exists" }
            withContext(NonCancellable) {
                if (target.folder != null) {
                    val destination = File(target.folder, record.fileName)
                    java.nio.file.Files.move(File(newLocation!!).toPath(), destination.toPath())
                    newLocation = destination.path
                } else {
                    val renamed = DC.renameDocument(resolver, Uri.parse(newLocation), record.fileName)
                        ?: throw IllegalArgumentException("Unable to finish file move")
                    newLocation = renamed.toString()
                    resolver.query(renamed, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
                        require(cursor != null && cursor.moveToFirst() && cursor.getString(0) == record.fileName) { "A file with that name already exists" }
                    }
                }
                val current = repository.get(id)
                require(current == record) { "File changed while moving. Try again" }
                repository.relocateCompleted(id, source, newLocation!!, target.tree, target.label,
                    maxOf(System.currentTimeMillis(), record.updatedAtEpochMillis))
                val deleted = runCatching { if (contentSource) DC.deleteDocument(resolver, Uri.parse(source)) else File(source).delete() }.getOrDefault(false)
                if (!deleted) {
                    try { repository.relocateCompleted(id, newLocation!!, source, record.destinationTreeUri, record.destinationDisplayLabel,
                        maxOf(System.currentTimeMillis(), record.updatedAtEpochMillis)) } catch (rollback: Throwable) { committed = true; throw rollback }
                    throw IllegalArgumentException("Unable to remove the original file. The move was rolled back")
                }
                committed = true
            }
            record.fileName
        } finally { if (!committed) deleteNew() }
    }
}
