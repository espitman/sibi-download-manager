package com.espitman.sdm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.storage.OpenDocumentTreeAccess
import com.espitman.sdm.storage.SaveLocationStore
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.web.WebPanelRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun WebFolderAccessSheet() {
    val context = LocalContext.current
    val requests = WebPanelRuntime.folderAccess
    val request by requests.pending.collectAsState()
    val approvals by WebPanelRuntime.access.requests.collectAsState()
    val scope = rememberCoroutineScope()
    var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = launchedId
        if (id != null) scope.launch {
            withContext(Dispatchers.IO) {
                requests.complete(context, id, result.resultCode, result.data?.data?.toString(), result.data?.flags ?: 0)
            }
            launchedId = null
        }
    }
    if (approvals.isNotEmpty()) return
    val pending = request ?: return
    val busy = launchedId != null
    val dismiss = { if (!busy) requests.cancel(pending.owner, pending.id) }
    SettingsSheet(SdmIcons.Folder, "LOCAL NETWORK", "Choose download folder",
        "Requested from your approved web panel.", dismiss, true) {
        Text(if (pending.category == null) "Default download folder" else "${pending.category.label} download folder",
            fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
        Text("Select a folder using Android. Existing files and downloads keep their access.",
            color = SdmMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArchiveButton("Decline", false, Modifier.weight(1f), !busy, onClick = dismiss)
            ArchiveButton(if (busy) "Selecting…" else "Choose folder", true, Modifier.weight(1.5f), !busy) {
                launchedId = pending.id
                try { picker.launch(OpenDocumentTreeAccess.createPickerIntent(SaveLocationStore.get(context).read().treeUri)) }
                catch (_: RuntimeException) { launchedId = null; requests.cancel(pending.owner, pending.id) }
            }
        }
    }
}
