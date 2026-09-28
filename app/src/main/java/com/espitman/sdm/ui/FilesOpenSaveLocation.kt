package com.espitman.sdm.ui

internal sealed class FilesOpenSaveLocationAction {
    data object ShowInAppFolder : FilesOpenSaveLocationAction()
    data class ShowFileManagerPicker(val treeUri: String) : FilesOpenSaveLocationAction()
}

internal fun resolveFilesOpenSaveLocationAction(treeUri: String?): FilesOpenSaveLocationAction {
    val tree = treeUri?.trim()?.takeIf { it.isNotEmpty() }
        ?: return FilesOpenSaveLocationAction.ShowInAppFolder
    return FilesOpenSaveLocationAction.ShowFileManagerPicker(tree)
}

internal fun performFilesOpenSaveLocation(
    treeUri: String?,
    discover: (String) -> FileManagerDiscovery?,
    onToast: (String) -> Unit,
    showInAppFolder: () -> Unit,
    showPicker: (FileManagerDiscovery) -> Unit,
) {
    when (val action = resolveFilesOpenSaveLocationAction(treeUri)) {
        FilesOpenSaveLocationAction.ShowInAppFolder -> showInAppFolder()
        is FilesOpenSaveLocationAction.ShowFileManagerPicker -> {
            when (val outcome = fileManagerPickerOutcome(discover(action.treeUri))) {
                is FileManagerPickerOutcome.Ready -> showPicker(outcome.discovery)
                FileManagerPickerOutcome.Unavailable,
                FileManagerPickerOutcome.PrivateFolder -> {
                    onToast(FILE_MANAGER_UNAVAILABLE_TOAST)
                    showInAppFolder()
                }
            }
        }
    }
}

internal fun prepareFilesFolderView(state: FilesUiState) {
    state.filter = FileTypeFilter.All
    state.searchOpen = false
    state.query = ""
    state.refreshEpoch += 1
}
