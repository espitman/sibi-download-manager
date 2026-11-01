package com.espitman.sdm.web

import android.app.Activity
import android.content.Context
import com.espitman.sdm.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Browser-owned, expiring requests; only the phone's Android picker can grant new access. */
internal class WebFolderAccess(
    private val now: () -> Long = System::currentTimeMillis,
    private val approved: (String) -> Boolean = { id -> WebPanelRuntime.access.browsers.value.any { it.id == id } },
    private val saveStore: (Context) -> SaveLocationStore = { SaveLocationStore.get(it) },
    private val categoryStore: (Context) -> CategoryFolderStore = { CategoryFolderStore.get(it) },
) {
    data class Request(val id: String, val owner: String, val category: FileCategory?, val expires: Long,
        val state: String = "pending", val message: String = "Choose the folder on your phone")
    private val records = linkedMapOf<String, Request>()
    private val mutablePending = MutableStateFlow<Request?>(null)
    val pending = mutablePending.asStateFlow()

    @Synchronized fun create(owner: String, category: String?): JSONObject {
        expire()
        require(approved(owner)) { "Browser access was removed" }
        val type = category?.let(FileCategory::valueOf)
        mutablePending.value?.let { request ->
            check(request.owner == owner && request.category == type) { "Finish the pending folder request on your phone first" }
            return json(request)
        }
        check(records.size < 128) { "Too many folder requests. Try again shortly" }
        val request = Request(UUID.randomUUID().toString(), owner, type, now() + 120_000)
        records[request.id] = request; mutablePending.value = request
        return json(request)
    }
    @Synchronized fun status(owner: String, id: String): JSONObject {
        expire()
        val request = records[id]?.takeIf { it.owner == owner } ?: error("Folder request expired")
        return json(request)
    }
    @Synchronized fun cancel(owner: String, id: String) {
        val request = records[id]?.takeIf { it.owner == owner } ?: return
        finish(request, "cancelled", "Folder selection cancelled")
    }
    @Synchronized fun expire() {
        records.values.toList().filter { it.state == "pending" && (it.expires <= now() || !approved(it.owner)) }
            .forEach { finish(it, "cancelled", "Folder request expired or browser access was removed") }
        records.entries.removeAll { now() > it.value.expires + 120_000 }
    }
    @Synchronized fun cancelAll() {
        records.values.toList().filter { it.state == "pending" }.forEach { finish(it, "cancelled", "Web panel stopped") }
    }
    private fun json(request: Request) = JSONObject().put("id", request.id).put("state", request.state).put("message", request.message)
    private fun finish(request: Request, state: String, message: String) {
        if (records[request.id]?.state != "pending") return
        records[request.id] = request.copy(state = state, message = message)
        if (mutablePending.value?.id == request.id) mutablePending.value = null
    }
    suspend fun complete(context: Context, id: String, resultCode: Int, uri: String?, flags: Int) = FolderMutationGate.mutex.withLock {
        val request = synchronized(this) { expire(); records[id]?.takeIf { it.state == "pending" } } ?: return@withLock
        if (resultCode != Activity.RESULT_OK || uri == null) {
            synchronized(this) { finish(request, "cancelled", "Folder selection cancelled on your phone") }; return@withLock
        }
        val grants = PersistableTreeUriGrants(context.contentResolver)
        val hadGrant = grants.hasReadWrite(uri)
        var committed = false
        try {
            check(!FolderRenameCoordinator.isPending(context)) { "Finish the pending folder rename first" }
            require(StorageAccessPolicy.validateTreeUri(uri) is TreeUriValidation.Valid) { "Choose a supported download folder" }
            check(grants.takeReadWrite(uri, flags) is PersistableGrantResult.Success) { "Could not keep access to this folder" }
            val inspection = DocumentsContractTreeAccess(context.contentResolver).inspect(uri)
            check(inspection.state == UserTreeState.Writable) { "Folder is not writable" }
            val label = inspection.displayName ?: SaveLocationLabels.SELECTED_FOLDER_FALLBACK
            synchronized(this) {
                expire()
                check(records[id]?.state == "pending") { "Folder request expired or was cancelled" }
                if (request.category == null) saveStore(context).persistUserTree(uri, label)
                else categoryStore(context).save(request.category, CategoryFolderRule(uri, label))
                committed = true
                finish(request, "accepted", "Download folder updated")
            }
        } catch (failure: Exception) {
            synchronized(this) { finish(request, "failed", com.espitman.sdm.domain.ErrorReportSanitizer.sanitize(failure.message, 160).ifBlank { "Could not select folder" }) }
        } finally {
            if (!committed && !hadGrant) grants.releaseReadWrite(uri)
        }
    }
}
