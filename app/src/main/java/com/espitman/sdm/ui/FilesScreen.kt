package com.espitman.sdm.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.espitman.sdm.storage.CompletedFileAction
import com.espitman.sdm.storage.CompletedFileIdentity
import com.espitman.sdm.storage.CompletedFileShareAccess
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.CompletedFileDeleteCoordinator
import com.espitman.sdm.download.DownloadRenameCoordinator
import com.espitman.sdm.storage.CompletedFileReconciliation
import com.espitman.sdm.storage.ContentResolverCompletedFileProbe
import com.espitman.sdm.storage.DocumentsContractContentDocuments
import com.espitman.sdm.storage.SaveLocationStore
import com.espitman.sdm.storage.StorageCapacity
import com.espitman.sdm.ui.theme.SdmDanger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.SdmBackground
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSuccess
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor
import java.time.ZoneId

@Stable
internal class FilesUiState {
    var filter by mutableStateOf(FileTypeFilter.All)
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var sort by mutableStateOf(FileSortOption.NewestFirst)
    var refreshEpoch by mutableIntStateOf(0)
}

internal fun saveFilesUiState(state: FilesUiState): List<Any> = listOf(
    state.filter.name,
    state.searchOpen,
    state.query,
    state.sort.name,
)

internal fun restoreFilesUiState(saved: List<*>): FilesUiState {
    val restored = FilesUiState()
    restored.filter = (saved.getOrNull(0) as? String)
        ?.let { name -> FileTypeFilter.entries.firstOrNull { it.name == name } }
        ?: FileTypeFilter.All
    restored.searchOpen = saved.getOrNull(1) as? Boolean ?: false
    restored.query = saved.getOrNull(2) as? String ?: ""
    restored.sort = (saved.getOrNull(3) as? String)
        ?.let { name -> FileSortOption.entries.firstOrNull { it.name == name } }
        ?: FileSortOption.NewestFirst
    return restored
}

private val FilesUiStateSaver = listSaver<FilesUiState, Any>(
    save = { saveFilesUiState(it) },
    restore = { restoreFilesUiState(it) },
)

@Composable
internal fun rememberFilesUiState(): FilesUiState =
    rememberSaveable(saver = FilesUiStateSaver) { FilesUiState() }

@Composable
internal fun FilesTopBar(
    uiState: FilesUiState,
    onToast: (String) -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val saveLocationStore = remember(context) { SaveLocationStore.get(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SdmBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = designHeaderInset())
                .height(63.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(sdmColor(0xFF171712, 0xFFF2EAD2), RoundedCornerShape(11.dp))
                    .border(1.dp, SdmGold.copy(alpha = .5f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("SD", color = SdmGoldHigh, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text("Files", color = SdmText, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.36).sp, modifier = Modifier.weight(1f))
            FilesHeaderAction(SdmIcons.Search, "Search files") {
                uiState.searchOpen = !uiState.searchOpen
            }
            Spacer(Modifier.width(6.dp))
            FilesHeaderAction(
                if (uiState.sort == FileSortOption.NewestFirst) SdmIcons.Sort else SdmIcons.SortAscending,
                "Sort files, ${if (uiState.sort == FileSortOption.NewestFirst) "newest" else "oldest"} first",
            ) {
                uiState.sort = if (uiState.sort == FileSortOption.NewestFirst) {
                    FileSortOption.OldestFirst
                } else {
                    FileSortOption.NewestFirst
                }
                onToast(uiState.sort.toast)
            }
            Spacer(Modifier.width(6.dp))
            Box {
                FilesHeaderAction(SdmIcons.More, "More file options") { menuOpen = !menuOpen }
                if (menuOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, with(LocalDensity.current) { 44.dp.roundToPx() }),
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Surface(
                            color = sdmColor(0xFF1B1C1F, 0xFFFFFFFF),
                            contentColor = SdmText,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, SdmLine),
                            shadowElevation = 18.dp,
                            modifier = Modifier.width(212.dp),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                FilesHeaderMenuItem(SdmIcons.Refresh, "Refresh files") {
                                    menuOpen = false
                                    uiState.refreshEpoch++
                                    onToast("Refreshing files")
                                }
                                FilesHeaderMenuItem(SdmIcons.FolderPlain, "Open save location") {
                                    menuOpen = false
                                    openSaveLocation(context, saveLocationStore.read().treeUri)?.let(onToast)
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SdmGold.copy(alpha = .14f))
    }
}

private fun openSaveLocation(context: Context, treeUriString: String?): String? {
    if (treeUriString.isNullOrBlank()) {
        return "This folder is private to SDM. Completed files are listed below."
    }
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
        context.startActivity(intent)
        null
    } catch (_: Exception) {
        "No file manager can open this folder."
    }
}

@Composable
private fun FilesHeaderMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = SdmText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilesHeaderAction(icon: ImageVector, description: String, onClick: () -> Unit = {}) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, description, tint = SdmText, modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun FilesScreen(
    uiState: FilesUiState = rememberFilesUiState(),
    showHeader: Boolean = true,
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = AppRepositories.downloads(context)
    val records by repository.downloads.collectAsState()
    val probe = remember(context) { ContentResolverCompletedFileProbe(context) }
    val contentDocuments = remember(context) { DocumentsContractContentDocuments(context) }
    val actionScope = rememberCoroutineScope()
    var completedRows by remember { mutableStateOf<List<FileRowModel>>(emptyList()) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(records, probe, contentDocuments, refreshEpoch, uiState.refreshEpoch) {
        val snapshot = records
        completedRows = withContext(Dispatchers.IO) {
            CompletedFileReconciliation.reconcile(
                records = snapshot,
                repository = repository,
                contentDocuments = contentDocuments,
            )
            snapshot.mapNotNull { download ->
                mapCompletedFile(
                    download = download,
                    nowEpochMillis = System.currentTimeMillis(),
                    zoneId = ZoneId.systemDefault(),
                    probe = probe,
                )
            }
        }
    }
    val files = remember(completedRows, uiState.filter, uiState.query, uiState.sort) {
        filterAndSortFiles(completedRows, uiState.filter, uiState.query, uiState.sort)
    }
    val shareAccess = remember(context) { CompletedFileShareAccess(context) }
    val saveLocationStore = remember(context) { SaveLocationStore.get(context) }
    val saveLocation by saveLocationStore.location.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var menuForId by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<FileRowModel?>(null) }
    var deleteFor by remember { mutableStateOf<FileRowModel?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun refreshFiles() {
        refreshEpoch += 1
    }
    fun performFileAction(action: CompletedFileAction, identity: CompletedFileIdentity) {
        menuForId = null
        shareAccess.perform(action, identity).message?.let(onToast)
    }
    val storage by produceState(
        StorageCapacity.Unknown,
        saveLocation,
        filesStorageReloadKey(completedRows),
        uiState.refreshEpoch,
    ) {
        value = withContext(Dispatchers.IO) {
            AppRepositories.storageCapacity(context).queryActive()
        }
    }
    BackHandler(uiState.searchOpen) { uiState.searchOpen = false }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) FilesTopBar(uiState, onToast)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.searchOpen) {
                item {
                    FilesSearchPanel(
                        query = uiState.query,
                        onQueryChange = { uiState.query = it },
                    )
                }
            }
            item { StorageCard(storage) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FileTypeFilter.entries.forEach { option ->
                        val selected = uiState.filter == option
                        Box(
                            Modifier.height(38.dp).background(if (selected) sdmColor(0xFF252218, 0xFFF5EDD4) else SdmSurface, RoundedCornerShape(11.dp))
                                .border(1.dp, if (selected) SdmGold.copy(alpha = .55f) else SdmLine, RoundedCornerShape(11.dp))
                                .clickable { uiState.filter = option }.padding(horizontal = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(option.label, color = if (selected) SdmGoldHigh else SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { Text("RECENT FILES", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            if (files.isEmpty()) {
                item {
                    SdmEmptyState("No matching files", "Try another search or file type.")
                }
            } else {
                items(files.size, key = { files[it].id }) { index ->
                    val file = files[index]
                    FileRow(
                        file = file,
                        selected = selectedId == file.id,
                        menuOpen = menuForId == file.id,
                        onToggleSelect = {
                            if (selectedId == file.id) {
                                selectedId = null
                                onToast("Selection cleared")
                            } else {
                                selectedId = file.id
                                onToast("File selected")
                            }
                        },
                        onOpenMenu = { menuForId = file.id },
                        onDismissMenu = { menuForId = null },
                        onOpen = { performFileAction(CompletedFileAction.Open, file.identity) },
                        onShare = { performFileAction(CompletedFileAction.Share, file.identity) },
                        onRename = {
                            menuForId = null
                            renameFor = file
                        },
                        onDelete = {
                            menuForId = null
                            deleteFor = file
                        },
                    )
                }
            }
        }
    }
    val renamingFile = renameFor
    if (renamingFile != null) {
        SdmRenameDialog(
            fileName = renamingFile.name,
            submitting = renaming,
            onDismiss = { if (!renaming) renameFor = null },
            onConfirm = { submittedName ->
                if (renaming) return@SdmRenameDialog
                renaming = true
                actionScope.launch {
                    try {
                        val result = DownloadRenameCoordinator.rename(
                            downloadId = renamingFile.id,
                            rawFilename = submittedName,
                            repository = repository,
                            clock = Clock.SystemClock,
                            contentDocuments = contentDocuments,
                        )
                        onToast(downloadRenameActionMessage(result))
                        if (shouldCloseRenameDialog(result)) renameFor = null
                    } finally {
                        renaming = false
                        refreshFiles()
                    }
                }
            },
        )
    }
    val deletingFile = deleteFor
    if (deletingFile != null) {
        SdmConfirmDialog(
            title = "Delete file?",
            message = "This permanently removes the downloaded file.",
            dismissLabel = "Keep file",
            confirmLabel = "Delete file",
            submitting = deleting,
            onDismiss = { if (!deleting) deleteFor = null },
            onConfirm = {
                if (deleting) return@SdmConfirmDialog
                deleting = true
                actionScope.launch {
                    try {
                        val result = CompletedFileDeleteCoordinator.delete(
                            downloadId = deletingFile.id,
                            repository = repository,
                            contentDocuments = contentDocuments,
                        )
                        onToast(completedFileDeleteActionMessage(result))
                        if (shouldCloseDeleteDialog(result)) {
                            if (selectedId == deletingFile.id) selectedId = null
                            deleteFor = null
                        }
                    } finally {
                        deleting = false
                        refreshFiles()
                    }
                }
            },
        )
    }
}

@Composable
private fun FilesSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        searchFocusRequester.requestFocus()
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp),
            cursorBrush = SolidColor(SdmGold),
            modifier = Modifier.fillMaxWidth().height(48.dp).background(SdmSurface, RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(start = 46.dp, end = 42.dp).focusRequester(searchFocusRequester),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search downloaded files", color = sdmColor(0xFF77746D, 0xFF77736A), fontSize = 13.sp)
                    inner()
                }
            },
        )
        Icon(SdmIcons.Search, null, tint = SdmMuted, modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp))
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)) {
                Icon(SdmIcons.Close, "Clear search", tint = SdmMuted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageCapacity) {
    val figures = storageCardFigures(storage)
    Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)), shape = RoundedCornerShape(20.dp)) {
        // CSS storage-card has 18px padding inside a 1px border. Compose draws
        // its border inside the card, so include that border in the inset.
        Column(Modifier.padding(19.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("DEVICE STORAGE", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp)
                    Text("Downloads", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("SDM files across video, audio, apps, and archives.", color = SdmMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp, end = 12.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(figures.usedLabel, color = SdmGoldHigh, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(figures.ofTotalLabel, color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(sdmColor(0xFF34332F, 0xFFDED8CB))) {
                Box(Modifier.fillMaxWidth(figures.usedFraction).height(5.dp).background(SdmGold))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(figures.availableLabel, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(figures.usedPercentLabel, color = SdmMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileRowModel,
    selected: Boolean,
    menuOpen: Boolean,
    onToggleSelect: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) sdmColor(0xFF1D1C16, 0xFFF5EDD4) else SdmSurface,
        ),
        border = BorderStroke(1.dp, if (selected) SdmGold.copy(alpha = .62f) else SdmLine),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(onClick = onToggleSelect),
    ) {
        Box {
            if (selected) {
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(SdmGold))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 42.dp, height = 48.dp)
                        .background(sdmColor(0xFF191914, 0xFFF2EAD2), RoundedCornerShape(11.dp))
                        .border(1.dp, SdmGold.copy(alpha = .32f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(file.type, color = SdmGoldHigh, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.meta, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    Text(
                        if (file.verified) "✓ Verified" else "✓ Complete",
                        color = SdmMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Box {
                    IconButton(onClick = { if (menuOpen) onDismissMenu() else onOpenMenu() }, modifier = Modifier.size(40.dp)) {
                        Icon(SdmIcons.More, "File actions", tint = SdmMuted, modifier = Modifier.size(18.dp))
                    }
                    if (menuOpen) {
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(0, with(density) { 40.dp.roundToPx() }),
                            onDismissRequest = onDismissMenu,
                            properties = PopupProperties(focusable = true),
                        ) {
                            FileActionMenu(
                                onOpen = onOpen,
                                onShare = onShare,
                                onRename = onRename,
                                onDelete = onDelete,
                            )
                        }
                    }
                }
            }
            if (selected) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(3.dp)
                            .background(SdmGold),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileActionMenu(
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = sdmColor(0xFF1B1C1F, 0xFFFFFFFF),
        contentColor = SdmText,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SdmLine),
        shadowElevation = 18.dp,
        modifier = Modifier.width(232.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FileActionMenuItem(SdmIcons.Open, "Open", "Open with another app", onOpen)
            FileActionMenuItem(SdmIcons.Share, "Share", "Send to another app", onShare)
            FileActionMenuItem(SdmIcons.Rename, "Rename", "Change the file name", onRename)
            FileActionMenuItem(SdmIcons.Delete, "Delete", "Remove this file", onDelete, danger = true)
        }
    }
}

@Composable
private fun FileActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val titleColor = if (danger) SdmDanger else SdmText
    val iconTint = if (danger) SdmDanger else SdmGoldHigh
    val iconBackground = if (danger) sdmColor(0xFF191414, 0xFFFFF4F2) else sdmColor(0xFF242318, 0xFFF2EAD2)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(iconBackground, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                subtitle,
                color = if (danger) SdmDanger.copy(alpha = .78f) else SdmMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
