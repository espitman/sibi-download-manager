package com.espitman.sdm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.storage.*
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.*

@Composable internal fun RenameSaveFolderSheet(onDismiss: () -> Unit, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val coordinator = remember { FolderRenameCoordinator(context) }
    val scope = rememberCoroutineScope()
    val host = rememberSdmSheetHost()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(FolderRenameCoordinator.isPending(context)) }
    var name by remember { mutableStateOf(runCatching { coordinator.currentName() }.getOrDefault("")) }
    var error by remember { mutableStateOf<String?>(null) }
    val dismiss = { if (!busy) host.dismissThen(onDismiss) }
    fun finished(done: Boolean) {
        pending = FolderRenameCoordinator.isPending(context)
        name = runCatching { coordinator.currentName() }.getOrDefault(name)
        error = coordinator.pendingNotice()
        if (done) { onToast(coordinator.completionMessage); host.dismissThen(onDismiss) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.toString()
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) scope.launch {
            busy = true; error = null
            try {
                val expected = coordinator.pendingTree()
                check(expected != null && FolderRenameCoordinator.sameFolder(uri, expected)) { "Select the renamed folder shown above." }
                check(PersistableTreeUriGrants(context.contentResolver).takeReadWrite(uri, result.data?.flags ?: 0) is PersistableGrantResult.Success) { "Could not keep access to this folder." }
                finished(coordinator.recover(uri))
            } catch (failure: Exception) { error = failure.message ?: "Could not restore folder access." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        if (pending) {
            busy = true
            try { finished(coordinator.recover()) }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    SettingsSheet(SdmIcons.Folder, "STORAGE", "Rename save folder", "Keep existing files and download progress.", dismiss, host.visible) {
        Text(if (pending) "Folder access" else "Folder name", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        BasicTextField(name, { name = it; error = null }, singleLine = true, enabled = !busy && !pending,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp), cursorBrush = SolidColor(SdmGold),
            modifier = Modifier.fillMaxWidth().height(48.dp).background(SdmBackground, RoundedCornerShape(12.dp)).border(1.dp, SdmLine, RoundedCornerShape(12.dp)),
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() } })
        Text(if (pending) "Select ${coordinator.pendingTree()?.let { SaveLocationLabels.fromTree(it, null) } ?: name} to restore access. Downloads stay paused until access is confirmed."
            else "Running downloads pause briefly, then continue. Queued downloads and existing files follow the renamed folder.",
            color = SdmMuted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp))
        if (error != null) Text(error!!, color = SdmDanger, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ArchiveButton(if (pending) "Later" else "Cancel", false, Modifier.weight(1f), !busy, onClick = dismiss)
            ArchiveButton(if (busy) "Renaming…" else if (pending) "Grant access" else "Rename folder", true, Modifier.weight(1.5f), !busy && name.isNotBlank()) {
                if (pending) {
                    val tree = coordinator.pendingTree()
                    val intent = OpenDocumentTreeAccess.createPickerIntent(tree)
                    if (tree != null) {
                        val parsed = android.net.Uri.parse(tree)
                        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            android.provider.DocumentsContract.buildDocumentUri(parsed.authority, android.provider.DocumentsContract.getTreeDocumentId(parsed)))
                    }
                    picker.launch(intent)
                }
                else scope.launch {
                    busy = true; error = null
                    try { finished(coordinator.rename(name)) }
                    catch (failure: Exception) { pending = FolderRenameCoordinator.isPending(context); error = failure.message ?: "Could not rename folder." }
                    finally { busy = false }
                }
            }
        }
    }
}
