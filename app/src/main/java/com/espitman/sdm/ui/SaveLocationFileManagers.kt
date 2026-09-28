package com.espitman.sdm.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.espitman.sdm.storage.ExternalStorageVolume
import java.io.File

internal data class FileManagerOption(
    val packageName: String,
    val activityName: String,
    val label: String,
    val localFolder: File? = null,
)

internal data class FileManagerDiscovery(
    val folderIntent: Intent,
    val options: List<FileManagerOption>,
)

internal sealed class FileManagerPickerOutcome {
    data object Unavailable : FileManagerPickerOutcome()
    data object PrivateFolder : FileManagerPickerOutcome()
    data class Ready(val discovery: FileManagerDiscovery) : FileManagerPickerOutcome()
}

internal const val FILE_MANAGER_UNAVAILABLE_TOAST = "No file manager can open this folder."
internal const val FILE_MANAGER_PRIVATE_TOAST =
    "This folder is private to SDM. Completed files are listed below."

internal fun fileManagerPickerOutcome(discovery: FileManagerDiscovery?): FileManagerPickerOutcome =
    when {
        discovery == null -> FileManagerPickerOutcome.Unavailable
        discovery.options.isEmpty() -> FileManagerPickerOutcome.PrivateFolder
        else -> FileManagerPickerOutcome.Ready(discovery)
    }

internal fun presentFileManagerPicker(
    outcome: FileManagerPickerOutcome,
    show: (FileManagerDiscovery) -> Unit,
    onToast: (String) -> Unit,
) {
    when (outcome) {
        FileManagerPickerOutcome.Unavailable -> onToast(FILE_MANAGER_UNAVAILABLE_TOAST)
        FileManagerPickerOutcome.PrivateFolder -> onToast(FILE_MANAGER_PRIVATE_TOAST)
        is FileManagerPickerOutcome.Ready -> show(outcome.discovery)
    }
}

internal fun isDocumentsUiFileManager(packageName: String, activityName: String): Boolean {
    if (!packageName.contains("documentsui", ignoreCase = true)) return false
    val activity = activityName.lowercase()
    return !activity.contains("picker") && !activity.contains("uninstaller")
}

internal fun assembleFileManagerOptions(
    esOption: FileManagerOption?,
    documentsUiOptions: List<FileManagerOption>,
): List<FileManagerOption> = buildList {
    esOption?.let(::add)
    addAll(
        documentsUiOptions
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label },
    )
}

internal fun interface FileManagerStarter {
    fun start(context: Context, option: FileManagerOption, folderIntent: Intent)
}

internal object SaveLocationFileManagers {
    const val ES_FILE_EXPLORER_PACKAGE = "com.estrongs.android.pop"
    const val ES_FILE_EXPLORER_ACTIVITY = "com.estrongs.android.pop.view.FileExplorerActivity"
    const val ES_VIEW_FOLDER_ACTION = "com.estrongs.android.pop.action.ACTION_VIEW_FOLDER"
    const val ES_FOLDER_MIME = "resource/folder"

    val DefaultStarter = FileManagerStarter { context, option, folderIntent ->
        context.startActivity(intentFor(context, option, folderIntent))
    }

    fun discover(context: Context, treeUriString: String?): FileManagerDiscovery? {
        if (treeUriString.isNullOrBlank()) return FileManagerDiscovery(Intent(), emptyList())
        return try {
            val treeUri = Uri.parse(treeUriString)
            val folderUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                clipData = ClipData.newUri(context.contentResolver, "Save location", folderUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val documentsUi = context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { handler ->
                    isDocumentsUiFileManager(handler.activityInfo.packageName, handler.activityInfo.name)
                }
                .map { handler ->
                    FileManagerOption(
                        handler.activityInfo.packageName,
                        handler.activityInfo.name,
                        "Files",
                    )
                }
            FileManagerDiscovery(
                folderIntent = intent,
                options = assembleFileManagerOptions(
                    esOption = esFileExplorerOption(context, treeUri),
                    documentsUiOptions = documentsUi,
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun start(context: Context, option: FileManagerOption, folderIntent: Intent) {
        DefaultStarter.start(context, option, folderIntent)
    }

    fun intentFor(context: Context, option: FileManagerOption, folderIntent: Intent): Intent {
        if (option.packageName == ES_FILE_EXPLORER_PACKAGE) {
            val folder = option.localFolder ?: error("ES File Explorer requires a local folder path")
            val viewFolder = esFolderIntent(folder, ES_VIEW_FOLDER_ACTION)
            if (context.packageManager.resolveActivity(viewFolder, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return viewFolder
            }
            val view = esFolderIntent(folder, Intent.ACTION_VIEW)
            if (context.packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return view
            }
            return viewFolder
        }
        return Intent(folderIntent).setClassName(option.packageName, option.activityName)
    }

    private fun esFileExplorerOption(context: Context, treeUri: Uri): FileManagerOption? {
        val folder = localFolderForTree(context, treeUri) ?: return null
        if (!esFileExplorerInstalled(context)) return null
        return FileManagerOption(
            packageName = ES_FILE_EXPLORER_PACKAGE,
            activityName = ES_FILE_EXPLORER_ACTIVITY,
            label = "ES File Explorer",
            localFolder = folder,
        )
    }

    private fun esFileExplorerInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(ES_FILE_EXPLORER_PACKAGE, 0)
            true
        }.getOrDefault(false)

    private fun esFolderIntent(folder: File, action: String): Intent =
        Intent(action).apply {
            setDataAndType(Uri.fromFile(folder), ES_FOLDER_MIME)
            putExtra("path", folder.absolutePath)
            addCategory(Intent.CATEGORY_DEFAULT)
            setClassName(ES_FILE_EXPLORER_PACKAGE, ES_FILE_EXPLORER_ACTIVITY)
        }

    private fun localFolderForTree(context: Context, treeUri: Uri): File? {
        if (!ExternalStorageVolume.isExternalStorageAuthority(treeUri.authority)) return null
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: RuntimeException) {
            return null
        }
        val volume = resolveStorageVolume(context, treeUri, documentId) ?: return null
        val root = directoryOf(volume) ?: return null
        return ExternalStorageVolume.fileOnVolume(root, documentId)
    }

    private fun resolveStorageVolume(context: Context, tree: Uri, documentId: String): StorageVolume? {
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { manager.getStorageVolume(tree) }.getOrNull()?.let { return it }
        }
        val volumeId = ExternalStorageVolume.volumeIdFromDocumentId(documentId) ?: return null
        return manager.storageVolumes.firstOrNull { volume ->
            ExternalStorageVolume.matchesVolumeId(volumeId, volume.isPrimary, volume.uuid)
        }
    }

    private fun directoryOf(volume: StorageVolume): File? {
        val directoryFromVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            null
        }
        @Suppress("DEPRECATION")
        return ExternalStorageVolume.filesystemRoot(
            isPrimary = volume.isPrimary,
            uuid = volume.uuid,
            directoryFromVolume = directoryFromVolume,
            primaryExternalDirectory = Environment.getExternalStorageDirectory(),
        )
    }
}
