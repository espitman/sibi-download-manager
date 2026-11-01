package com.espitman.sdm.web

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.espitman.sdm.storage.*
import org.json.JSONArray
import org.json.JSONObject

/** Changes defaults for future submissions without revoking grants used by existing downloads. */
internal class WebStorageSettings(private val context: Context, private val folders: WebFolders,
    private val saveStore: SaveLocationStore = SaveLocationStore.get(context),
    private val categoryStore: CategoryFolderStore = CategoryFolderStore.get(context)) {
    fun snapshot(): JSONObject {
        val categories = categoryStore.settings.value
        return JSONObject().put("label", saveStore.read().displayLabel)
            .put("renamePending", FolderRenameCoordinator.isPending(context))
            .put("name", runCatching { FolderRenameCoordinator(context).currentName() }.getOrDefault(""))
            .put("enabled", categories.enabled).put("categories", JSONArray(FileCategory.entries.map { category ->
                JSONObject().put("id", category.name).put("name", category.label)
                    .put("label", categories.rules[category]?.label ?: "Default download folder")
                    .put("custom", categories.rules.containsKey(category))
                    .put("unavailable", category in categoryStore.unavailable.value)
            }))
    }

    suspend fun rename(body: JSONObject): JSONObject {
        require(body.optBoolean("confirmed")) { "Confirm the folder rename first" }
        val coordinator = FolderRenameCoordinator(context)
        val complete = coordinator.rename(body.getString("name"))
        return snapshot().put("complete", complete).put("message", if (complete) coordinator.completionMessage
            else "Open SDM Settings on your phone and choose Rename save folder to confirm access. Downloads stay paused until access is restored.")
    }

    fun update(owner: String, body: JSONObject): JSONObject {
        check(!FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
        when (body.getString("action")) {
            "enabled" -> categoryStore.setEnabled(body.getBoolean("enabled"))
            "clear" -> categoryStore.save(FileCategory.valueOf(body.getString("category")), null)
            "default", "category" -> {
                val category = if (body.getString("action") == "category") FileCategory.valueOf(body.getString("category")) else null
                val folder = folders.resolve(owner, body.getString("folder"))
                if (folder.file != null) {
                    require(folder.file.canonicalFile == AppSpecificDownloadsDirectory.from(context).canonicalFile) {
                        "Choose App Downloads. Custom subfolders can be selected for individual downloads"
                    }
                    if (category == null) saveStore.persistAppSpecific() else categoryStore.save(category, null)
                } else {
                    val tree = requireNotNull(folder.tree)
                    require(DocumentsContract.getDocumentId(requireNotNull(folder.document)) == DocumentsContract.getTreeDocumentId(Uri.parse(tree))) {
                        "Grant this folder on your phone before using it as a default"
                    }
                    require(DocumentsContractTreeAccess(context.contentResolver).inspect(tree).state == UserTreeState.Writable) {
                        "Folder is not writable. Check access on your phone"
                    }
                    if (category == null) saveStore.persistUserTree(tree, folder.name)
                    else categoryStore.save(category, CategoryFolderRule(tree, folder.name))
                }
            }
            else -> throw IllegalArgumentException("Unknown storage setting")
        }
        return snapshot()
    }
}
