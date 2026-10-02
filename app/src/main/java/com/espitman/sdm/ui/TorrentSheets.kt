package com.espitman.sdm.ui

import com.espitman.sdm.notification.rememberTransferNotificationPermissionPreparer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.storage.*
import com.espitman.sdm.torrent.*
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.*

@Composable
internal fun TorrentAddSheet(initialLink: String, onDismiss: () -> Unit, onToast: (String) -> Unit, requestContext: com.espitman.sdm.network.ScopedRequestContext? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.takeUnless { it.startsWith("content:") }.orEmpty()) }
    var fileMode by remember { mutableStateOf(initialLink.isBlank()) }
    var contents by remember { mutableStateOf<TorrentContents?>(null) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var useBrowser by remember { mutableStateOf(true) }
    var seed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    val location = remember { AppRepositories.saveLocation(context).resolveForNewDownload() }
    var folderUri by remember { mutableStateOf(location.treeUri) }
    var folderLabel by remember { mutableStateOf(location.displayLabel) }
    fun resolved(value: TorrentContents) { contents = value; selected = value.files.map { it.index }.toSet(); error = null }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        work = scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Unable to add torrent" }
            catch (_: UnsatisfiedLinkError) { error = "Torrent engine is unavailable on this device" }
            finally { busy = false; work = null }
        }
    }
    LaunchedEffect(initialLink) {
        if (initialLink.startsWith("content:")) run { resolved(TorrentResolver.file(context, Uri.parse(initialLink))) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { resolved(TorrentResolver.file(context, uri)) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) run {
            val uri = result.data!!.data!!.toString()
            val label = withContext(Dispatchers.IO) {
                val outcome = StorageAccessPolicy.interpretPickerResult(result.resultCode, uri, result.data?.flags ?: 0)
                require(outcome is OpenDocumentTreeOutcome.Accepted) { "Cannot use this folder" }
                check(PersistableTreeUriGrants(context.contentResolver).takeReadWrite(uri, outcome.takeFlags) is PersistableGrantResult.Success) { "Cannot keep folder access" }
                val inspection = DocumentsContractTreeAccess(context.contentResolver).inspect(uri)
                check(inspection.state == UserTreeState.Writable) { "Folder is unavailable" }
                SaveLocationLabels.fromTree(uri, inspection.displayName)
            }
            folderUri = uri; folderLabel = label
        }
    }
    val notifications = rememberTransferNotificationPermissionPreparer {}
    fun add(startNow: Boolean) {
        val value = contents ?: return
        val submit = { run {
            TorrentSubmission.submit(context, value, selected, seed, startNow, folderUri, folderLabel)
            onToast("Torrent added"); onDismiss()
        } }
        if (startNow) notifications.prepareForForegroundTransfer(submit) else submit()
    }
    SettingsSheet(SdmIcons.Download, "TORRENT & MAGNET", if (contents == null) "Add torrent" else "Torrent contents",
        if (contents == null) "Select files before downloading." else contents!!.name,
        { work?.cancel(); onDismiss() }, true) {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (contents == null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorrentButton("Link / Magnet", !fileMode, Modifier.weight(1f), !busy) { fileMode = false }
                    TorrentButton("Torrent file", fileMode, Modifier.weight(1f), !busy) { fileMode = true }
                }
                if (fileMode) TorrentButton("Pick .torrent file", false, Modifier.fillMaxWidth(), !busy) {
                    picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                } else BasicTextField(link, { link = it; error = null }, enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(color = SdmText, fontSize = 12.sp), cursorBrush = SolidColor(SdmGold),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 140.dp).background(SdmBackground, RoundedCornerShape(14.dp))
                        .border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(14.dp),
                    decorationBox = { inner -> Box { if (link.isBlank()) Text("magnet:?xt=urn:btih:…", color = SdmMuted, fontSize = 12.sp); inner() } })
                if (requestContext != null && !fileMode) {
                    TorrentChoice("Use this browser session", useBrowser, !busy) { useBrowser = !useBrowser }
                    Text("Used only to read the torrent file. Browser sessions are never sent to peers or trackers.", color = SdmMuted, fontSize = 11.sp)
                }
                if (!fileMode) TorrentButton(if (busy) "Resolving metadata…" else "Resolve metadata", true, Modifier.fillMaxWidth(), !busy && link.isNotBlank()) {
                    run { resolved(TorrentResolver.link(context, link, requestContext?.takeIf { useBrowser })) }
                }
            } else {
                val value = contents!!
                Text("${value.files.size} files · ${formatBytes(value.files.sumOf { it.size })}", color = SdmMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                TorrentChoice("Select all", selected.size == value.files.size, !busy) {
                    selected = if (selected.size == value.files.size) emptySet() else value.files.map { it.index }.toSet()
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                    items(value.files, key = { it.index }) { file ->
                        TorrentChoice(file.path, file.index in selected, !busy, formatBytes(file.size)) {
                            selected = if (file.index in selected) selected - file.index else selected + file.index
                        }
                    }
                }
                Text("${selected.size} files selected · ${formatBytes(value.files.filter { it.index in selected }.sumOf { it.size })}", color = SdmGold, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                }.border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Save location", fontSize = 12.sp)
                    Text(folderLabel, color = SdmGold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                }
                TorrentChoice("Seeding", seed, !busy) { seed = !seed }
                Text(if (seed) "Keep sharing selected files until you pause."
                    else "Finish when downloaded. Pieces may be uploaded while downloading.", color = SdmMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TorrentButton("Add to queue", false, Modifier.weight(1f), !busy && selected.isNotEmpty()) { add(false) }
                    TorrentButton(if (busy) "Adding…" else "Download", true, Modifier.weight(1f), !busy && selected.isNotEmpty()) { add(true) }
                }
            }
            error?.let { Text(it, color = SdmDanger, fontSize = 12.sp) }
            if (busy) TorrentButton("Cancel", false, Modifier.fillMaxWidth()) { work?.cancel() }
        }
    }
}

@Composable
internal fun TorrentDetailsSheet(download: Download, onDismiss: () -> Unit, onTransferAction: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telemetry by TorrentRuntime.telemetry.collectAsState()
    val stats = telemetry[download.id] ?: TorrentTelemetry()
    var contents by remember(download.id) { mutableStateOf<TorrentContents?>(null) }
    var selection by remember(download.id) { mutableStateOf<TorrentSelection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(download.id) {
        try {
            withContext(Dispatchers.IO) {
                val store = TorrentStore(context)
                contents = TorrentStore.inspect(store.metadata(download.id)); selection = store.selection(download.id)
            }
        } catch (_: Exception) { error = "Torrent metadata is unavailable" }
    }
    SettingsSheet(SdmIcons.Download, "TORRENT", "Torrent info", download.fileName, onDismiss, true) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${formatBytes(download.downloadedBytes)} downloaded", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Text("${stats.peers} peers · ${stats.seeds} seeds", fontSize = 12.sp)
            Text("↓ ${formatBytes(stats.downloadRate.toLong())}/s   ↑ ${formatBytes(stats.uploadRate.toLong())}/s", color = SdmGold, fontSize = 12.sp)
            Text("Uploaded ${formatBytes(stats.uploaded)} · Share ratio ${"%.2f".format(java.util.Locale.US, stats.ratio)}", color = SdmMuted, fontSize = 12.sp)
            val current = selection
            if (current != null && download.state != com.espitman.sdm.domain.DownloadState.COMPLETED) {
                TorrentChoice("Seeding", current.seed) {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { TorrentStore(context).updateSelection(download.id) { it.copy(seed = !current.seed) } }; selection = current.copy(seed = !current.seed) }
                        catch (_: Exception) { error = "Unable to update seeding" }
                    }
                }
                Text(if (current.seed) "Keep sharing until paused." else "Finish when downloaded.", color = SdmMuted, fontSize = 11.sp)
            }
            contents?.files?.filter { it.index in selection?.selected.orEmpty() }?.forEach { file ->
                val published = selection?.published?.get(file.index)
                Row(Modifier.fillMaxWidth().clickable(enabled = download.state == com.espitman.sdm.domain.DownloadState.COMPLETED) {
                    try {
                        val uri = if (published != null) Uri.parse(published) else androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", TorrentPaths.resolve(java.io.File(download.destinationPath!!), file.path))
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.path.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { error = "No app can open this file" }
                }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.path, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatBytes(file.size), color = SdmMuted, fontSize = 11.sp)
                }
            }
            onTransferAction?.let { action ->
                if (download.state != com.espitman.sdm.domain.DownloadState.COMPLETED)
                    TorrentButton(if (download.state in setOf(com.espitman.sdm.domain.DownloadState.DOWNLOADING, com.espitman.sdm.domain.DownloadState.CONNECTING)) "Pause" else "Resume", true, Modifier.fillMaxWidth(), action = action)
            }
            error?.let { Text(it, color = SdmDanger, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun TorrentButton(text: String, primary: Boolean, modifier: Modifier, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) SdmGold else SdmBackground,
            contentColor = if (primary) SdmBackground else SdmText), border = androidx.compose.foundation.BorderStroke(1.dp, if (primary) SdmGold else SdmLine),
        modifier = modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 9.dp)) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TorrentChoice(label: String, checked: Boolean, enabled: Boolean = true, detail: String? = null, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(checked, enabled, Role.Checkbox) { action() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp).background(if (checked) SdmGold else SdmBackground, RoundedCornerShape(5.dp))
            .border(1.dp, if (checked) SdmGold else SdmLine, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (checked) Icon(SdmIcons.Check, null, tint = SdmBackground, modifier = Modifier.size(14.dp))
        }
        Text(label, color = SdmText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        detail?.let { Text(it, color = SdmMuted, fontSize = 11.sp) }
    }
}
