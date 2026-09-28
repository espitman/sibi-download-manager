package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download

internal sealed class DetailsOpenFolderAction {
    data class RevealInFiles(val downloadId: String) : DetailsOpenFolderAction()
    data class ShowFileManagerPicker(val treeUri: String) : DetailsOpenFolderAction()
    data class Message(val text: String) : DetailsOpenFolderAction()
}

internal fun resolveDetailsOpenFolderAction(download: Download): DetailsOpenFolderAction {
    val treeUri = download.destinationTreeUri?.trim()?.takeIf { it.isNotEmpty() }
    if (treeUri != null) {
        return DetailsOpenFolderAction.ShowFileManagerPicker(treeUri)
    }
    if (download.destinationPath.isNullOrBlank()) {
        return DetailsOpenFolderAction.Message("Destination folder unavailable")
    }
    return DetailsOpenFolderAction.RevealInFiles(download.id)
}

internal fun performDetailsOpenFolder(
    download: Download,
    discover: (String) -> FileManagerDiscovery?,
    onToast: (String) -> Unit,
    onRevealFileInFiles: (String) -> Unit,
    showPicker: (FileManagerDiscovery) -> Unit,
) {
    when (val action = resolveDetailsOpenFolderAction(download)) {
        is DetailsOpenFolderAction.Message -> onToast(action.text)
        is DetailsOpenFolderAction.RevealInFiles -> {
            onToast(detailsOpenFolderToast(download))
            onRevealFileInFiles(action.downloadId)
        }
        is DetailsOpenFolderAction.ShowFileManagerPicker -> {
            presentFileManagerPicker(
                fileManagerPickerOutcome(discover(action.treeUri)),
                show = showPicker,
                onToast = onToast,
            )
        }
    }
}

internal fun prepareFilesReveal(state: FilesUiState, downloadId: String) {
    state.filter = FileTypeFilter.All
    state.searchOpen = false
    state.query = ""
    state.pendingRevealFileId = downloadId
    state.revealEpoch += 1
}

internal fun filesRevealListIndex(searchOpen: Boolean, fileIndex: Int): Int {
    val headerCount = 3 + if (searchOpen) 1 else 0
    return headerCount + fileIndex
}
