package com.espitman.sdm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import com.espitman.sdm.ui.theme.*
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DailyTransferAccounting
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPriorityMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.DownloadChecksumVerifier
import com.espitman.sdm.download.DownloadRenameCoordinator
import com.espitman.sdm.download.DownloadRenameResult
import com.espitman.sdm.download.DownloadTransferService
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class HomeOverlay { KeepActive, SpeedLimit, Preferences }
private val LocalHomeSheetVisible = staticCompositionLocalOf { true }

@Stable
internal class DownloadsUiState {
    var category by mutableStateOf(DownloadCategory.Downloading)
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var menuOpen by mutableStateOf(false)
    var overlay by mutableStateOf<HomeOverlay?>(null)
}

internal fun saveDownloadsUiState(state: DownloadsUiState): List<Any> = listOf(
    state.category.name,
    state.searchOpen,
    state.query,
)

internal fun restoreDownloadsUiState(saved: List<*>): DownloadsUiState {
    val restored = DownloadsUiState()
    restored.category = (saved.getOrNull(0) as? String)
        ?.let { name -> DownloadCategory.entries.firstOrNull { it.name == name } }
        ?: DownloadCategory.Downloading
    restored.searchOpen = saved.getOrNull(1) as? Boolean ?: false
    restored.query = saved.getOrNull(2) as? String ?: ""
    restored.menuOpen = false
    restored.overlay = null
    return restored
}

private val DownloadsUiStateSaver = listSaver<DownloadsUiState, Any>(
    save = { saveDownloadsUiState(it) },
    restore = { restoreDownloadsUiState(it) },
)

@Composable
internal fun rememberDownloadsUiState(): DownloadsUiState =
    rememberSaveable(saver = DownloadsUiStateSaver) { DownloadsUiState() }

internal fun resolveSelectedDownload(records: List<Download>, id: String?): Download? {
    if (id == null) return null
    return records.firstOrNull { it.id == id }
}

internal enum class TransferCardAction {
    Pause,
    Resume,
    Retry,
    None,
}

internal fun transferCardAction(state: DownloadState): TransferCardAction = when (state) {
    DownloadState.PAUSED -> TransferCardAction.Resume
    DownloadState.CONNECTING, DownloadState.DOWNLOADING -> TransferCardAction.Pause
    DownloadState.FAILED -> TransferCardAction.Retry
    else -> TransferCardAction.None
}

internal fun detailsPrimaryAction(state: DownloadState): TransferCardAction = when (transferCardAction(state)) {
    TransferCardAction.Retry -> TransferCardAction.Retry
    TransferCardAction.Resume -> TransferCardAction.Resume
    TransferCardAction.Pause, TransferCardAction.None -> TransferCardAction.Pause
}

internal fun detailsPrimaryActionLabel(action: TransferCardAction): String = when (action) {
    TransferCardAction.Retry -> "Retry"
    TransferCardAction.Resume -> "Resume"
    TransferCardAction.Pause, TransferCardAction.None -> "Pause"
}

internal fun dispatchTransferCardAction(
    action: TransferCardAction,
    pause: () -> Unit,
    resumeOrRetry: () -> Unit,
) {
    when (action) {
        TransferCardAction.Pause -> pause()
        TransferCardAction.Resume, TransferCardAction.Retry -> resumeOrRetry()
        TransferCardAction.None -> Unit
    }
}

internal fun confirmCancelDownload(
    dispatchCancel: () -> Unit,
    closeDialog: () -> Unit,
    returnToList: () -> Unit,
) {
    dispatchCancel()
    closeDialog()
    returnToList()
}

internal fun priorityToggleToast(highPriority: Boolean): String =
    if (highPriority) "High priority enabled" else "Priority returned to normal"

@Composable
internal fun InteractiveDownloadsScreen(
    uiState: DownloadsUiState,
    selectedDownloadId: String?,
    showHeader: Boolean = true,
    onSelectedDownloadIdChange: (String?) -> Unit,
    onToast: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val repository = AppRepositories.downloads(context)
    val records by repository.downloads.collectAsState()
    val speedTracker = remember { RecentTransferSpeedTracker() }
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasActive = remember(records) {
        records.any { it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING }
    }
    LaunchedEffect(hasActive) {
        while (true) {
            nowEpochMillis = System.currentTimeMillis()
            delay(
                nextDownloadsStatusRefreshDelayMillis(
                    nowEpochMillis = nowEpochMillis,
                    zoneId = ZoneId.systemDefault(),
                    hasActiveTransfers = hasActive,
                ),
            )
        }
    }
    val zoneId = ZoneId.systemDefault()
    val dayKey = DailyTransferAccounting.dayKey(nowEpochMillis, zoneId)
    var downloadedTodayBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(records, dayKey) {
        downloadedTodayBytes = transferredBytesForLocalDayOrZero {
            repository.transferredBytesForLocalDay(nowEpochMillis, zoneId)
        }
    }
    val recentBytesPerSecond = remember(records, nowEpochMillis) {
        speedTracker.aggregateBytesPerSecond(records, nowEpochMillis)
    }
    val downloads = records.map { record -> mapDownloadToCard(record, nowEpochMillis) }
    var overlayClosing by remember { mutableStateOf(false) }
    val overlayScope = rememberCoroutineScope()
    val dismissOverlay: () -> Unit = {
        if (!overlayClosing) overlayScope.launch {
            overlayClosing = true
            delay(320)
            uiState.overlay = null
            overlayClosing = false
        }
    }
    val settingsRepository = SettingsRepository.get(LocalContext.current)
    val settings by settingsRepository.settings.collectAsState()
    LaunchedEffect(selectedDownloadId) {
        val id = selectedDownloadId ?: return@LaunchedEffect
        repository.awaitInitialized()
        if (repository.get(id) == null) onSelectedDownloadIdChange(null)
    }
    BackHandler(uiState.searchOpen) { uiState.searchOpen = false }

    val selectedRecord = resolveSelectedDownload(records, selectedDownloadId)
    if (selectedDownloadId != null) {
        if (selectedRecord != null) {
            DownloadDetailsScreen(
                download = selectedRecord,
                nowEpochMillis = nowEpochMillis,
                priorityActive = DownloadPriorityMutation.isHigh(selectedRecord.priority),
                actionScope = overlayScope,
                onBack = { onSelectedDownloadIdChange(null) },
                onToast = onToast,
                onRename = { fileName ->
                    DownloadRenameCoordinator.rename(
                        downloadId = selectedRecord.id,
                        rawFilename = fileName,
                        repository = repository,
                        clock = Clock.SystemClock,
                    )
                },
                onMoveToTop = {
                    overlayScope.launch {
                        val after = repository.moveToTop(
                            selectedRecord.id,
                            System.currentTimeMillis(),
                        )
                        AppRepositories.queueScheduler(context).schedule()
                        onToast(moveToTopActionMessage(selectedRecord, after))
                    }
                },
                onPause = {
                    dispatchTransferCardAction(
                        action = transferCardAction(selectedRecord.state),
                        pause = { DownloadTransferService.pauseTransfer(context, selectedRecord.id) },
                        resumeOrRetry = { DownloadTransferService.resumeTransfer(context, selectedRecord.id) },
                    )
                },
                onCancel = {
                    DownloadTransferService.cancelTransfer(context, selectedRecord.id)
                },
                onPriority = {
                    val previousPriority = selectedRecord.priority
                    overlayScope.launch {
                        val updated = AppRepositories.queueScheduler(context).togglePriority(selectedRecord.id)
                            ?: return@launch
                        if (updated.priority != previousPriority) {
                            onToast(priorityToggleToast(DownloadPriorityMutation.isHigh(updated.priority)))
                        }
                    }
                },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) DownloadsTopBar(uiState)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
        ) {
            item { DownloadStatusCard(records, downloadedTodayBytes, recentBytesPerSecond) }
            item { Spacer(Modifier.height(18.dp)); DownloadToolbar(downloads.size,
                onDownloadAll = {
                    overlayScope.launch {
                        AppRepositories.queueScheduler(context).downloadAll()
                        onToast("All downloads started")
                    }
                },
                onPauseAll = {
                    overlayScope.launch {
                        AppRepositories.queueScheduler(context).pauseAll { id ->
                            DownloadTransferService.pauseTransfer(context, id)
                        }
                        onToast("All active downloads paused")
                    }
                }) }
            item { Spacer(Modifier.height(8.dp)); DownloadTabs(uiState.category) { uiState.category = it; uiState.query = "" }; Spacer(Modifier.height(12.dp)) }
            val visibleDownloads = filterDownloadCards(downloads, uiState.category, uiState.query)
            if (visibleDownloads.isEmpty()) {
                item { EmptyDownloads(uiState.category) }
            } else {
                items(visibleDownloads, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onOpen = { onSelectedDownloadIdChange(item.id) },
                        onAction = {
                            val record = records.firstOrNull { it.id == item.id }
                            val action = record?.let { transferCardAction(it.state) }
                            if (action == null || action == TransferCardAction.None) {
                                if (item.showPlayAction) {
                                    onToast("Download engine is not connected yet")
                                }
                            } else {
                                dispatchTransferCardAction(
                                    action = action,
                                    pause = { DownloadTransferService.pauseTransfer(context, item.id) },
                                    resumeOrRetry = { DownloadTransferService.resumeTransfer(context, item.id) },
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    CompositionLocalProvider(LocalHomeSheetVisible provides !overlayClosing) { when (uiState.overlay) {
        HomeOverlay.KeepActive -> {
            var enabled by remember { mutableStateOf(settings.keepActive) }
            var duration by remember { mutableStateOf(settings.keepActiveDuration) }
            KeepActiveSheet(enabled, { enabled = it }, duration, { duration = it }, dismissOverlay) { message ->
                settingsRepository.update { it.copy(keepActive = enabled, keepActiveDuration = duration) }
                onToast(message)
            }
        }
        HomeOverlay.SpeedLimit -> {
            var unlimited by remember { mutableStateOf(settings.unlimitedSpeed) }
            var limit by remember { mutableFloatStateOf(settings.speedLimitMbps) }
            var wifiOnly by remember { mutableStateOf(settings.speedLimitWifiOnly) }
            SpeedLimitSheet(unlimited, { unlimited = it }, limit, { limit = it }, wifiOnly, { wifiOnly = it }, dismissOverlay) { message ->
                settingsRepository.update { it.copy(unlimitedSpeed = unlimited, speedLimitMbps = limit, speedLimitWifiOnly = wifiOnly) }
                onToast(message)
            }
        }
        HomeOverlay.Preferences -> PreferencesSheet(dismissOverlay, onToast, onOpenSettings)
        else -> Unit
    } }
}

@Composable
internal fun DownloadsTopBar(uiState: DownloadsUiState) {
    DownloadsHeader(
        searchOpen = uiState.searchOpen,
        query = uiState.query,
        onQueryChange = { uiState.query = it },
        menuOpen = uiState.menuOpen,
        onSearch = {
            uiState.searchOpen = !uiState.searchOpen
            uiState.menuOpen = false
            if (!uiState.searchOpen) uiState.query = ""
        },
        onMenu = { uiState.menuOpen = !uiState.menuOpen },
        onDismissMenu = { uiState.menuOpen = false },
        onKeepActive = { uiState.menuOpen = false; uiState.overlay = HomeOverlay.KeepActive },
        onSpeedLimit = { uiState.menuOpen = false; uiState.overlay = HomeOverlay.SpeedLimit },
        onPreferences = { uiState.menuOpen = false; uiState.overlay = HomeOverlay.Preferences },
    )
}

@Composable
private fun DownloadsHeader(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    menuOpen: Boolean,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onKeepActive: () -> Unit,
    onSpeedLimit: () -> Unit,
    onPreferences: () -> Unit,
) {
    val density = LocalDensity.current
    val menuOffsetY = with(density) { WindowInsets.statusBars.getTop(this) + 58.dp.roundToPx() }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(50)
            searchFocusRequester.requestFocus()
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(SdmBackground)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(63.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(sdmColor(0xFF171712, 0xFFF2EAD2), RoundedCornerShape(11.dp)).border(1.dp, SdmGold.copy(alpha = .5f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text("SD", color = SdmGoldHigh, fontSize = 13.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(10.dp))
                Text("Downloads", color = SdmText, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.36).sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onSearch, modifier = Modifier.size(42.dp)) { Icon(SdmIcons.Search, "Search downloads", tint = SdmText, modifier = Modifier.size(21.dp)) }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onMenu, modifier = Modifier.size(42.dp)) { Icon(SdmIcons.More, "More options", tint = SdmText, modifier = Modifier.size(21.dp)) }
            }
            HorizontalDivider(thickness = 1.dp, color = SdmGold.copy(alpha = .14f))
            AnimatedVisibility(searchOpen, enter = fadeIn(tween(140)), exit = fadeOut(tween(120))) {
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    BasicTextField(
                        value = query, onValueChange = onQueryChange, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp), cursorBrush = SolidColor(SdmGold),
                        modifier = Modifier.fillMaxWidth().height(48.dp).background(SdmSurface, RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(start = 46.dp, end = 42.dp).focusRequester(searchFocusRequester),
                        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { if (query.isEmpty()) Text("Search filenames", color = sdmColor(0xFF77746D, 0xFF77736A), fontSize = 13.sp); inner() } },
                    )
                    Icon(SdmIcons.Search, null, tint = SdmMuted, modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp))
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)) { Icon(SdmIcons.Close, "Clear search", tint = SdmMuted, modifier = Modifier.size(17.dp)) }
                }
            }
        }
        if (menuOpen) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(with(density) { (-14).dp.roundToPx() }, menuOffsetY),
                onDismissRequest = onDismissMenu,
                properties = PopupProperties(focusable = true),
            ) {
                Surface(color = sdmColor(0xFF1B1C1F, 0xFFFFFFFF), contentColor = SdmText, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine), shadowElevation = 18.dp, modifier = Modifier.width(232.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        HomeMenuItem(SdmIcons.KeepActive, "Keep active", "Prevent interrupted transfers", onKeepActive)
                        HomeMenuItem(SdmIcons.Gauge, "Speed limit", "Control global bandwidth", onSpeedLimit)
                        HomeMenuItem(SdmIcons.Settings, "Preferences", "Quick download settings", onPreferences)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).background(sdmColor(0xFF242318, 0xFFF2EAD2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(17.dp)) }
        Column(Modifier.weight(1f)) { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(subtitle, color = SdmMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun DownloadStatusCard(
    records: List<Download>,
    downloadedTodayBytes: Long,
    recentBytesPerSecond: Long,
) {
    val values = downloadStatusCardValues(records, downloadedTodayBytes, recentBytesPerSecond)
    Surface(color = SdmSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .34f)), modifier = Modifier.fillMaxWidth()) {
        Box {
            Text("SDM", color = SdmGold.copy(alpha = .055f), fontSize = 86.sp, lineHeight = 86.sp, letterSpacing = (-6.8).sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Top) { Text("PREMIUM STATUS", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp, modifier = Modifier.weight(1f)); Box(Modifier.padding(top = 3.dp).size(7.dp).background(SdmSuccess, CircleShape)); Spacer(Modifier.width(6.dp)); Text("${values.activeCount} active", color = SdmSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Row(Modifier.padding(top = 10.dp).height(40.dp), verticalAlignment = Alignment.Bottom) { Text(values.speedValue, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.8).sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(6.dp)); Text("MB/s", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp)) }
                Text("Aggregate download speed", color = SdmMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
                Row(Modifier.fillMaxWidth()) { DownloadStat(values.downloadedToday, "Downloaded today", Modifier.weight(1f)); VerticalDivider(); DownloadStat(values.remaining, "Remaining", Modifier.weight(1f).padding(start = 10.dp)); VerticalDivider(); DownloadStat(values.connections, "Connections", Modifier.weight(1f).padding(start = 10.dp)) }
            }
        }
    }
}

@Composable private fun VerticalDivider() = Box(Modifier.width(1.dp).height(36.dp).background(SdmLine))
@Composable private fun DownloadStat(value: String, label: String, modifier: Modifier) { Column(modifier.padding(end = 7.dp)) { Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1); Text(label, color = SdmMuted, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp)) } }

@Composable
private fun DownloadToolbar(count: Int, onDownloadAll: () -> Unit, onPauseAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Downloads", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); Text("$count items", color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp)) }
        BulkButton("Download All", SdmIcons.DownloadAll, true, onDownloadAll); Spacer(Modifier.width(6.dp)); BulkButton("Pause All", SdmIcons.Pause, false, onPauseAll)
    }
}

@Composable
private fun BulkButton(label: String, icon: ImageVector, highlighted: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (highlighted) sdmColor(0xFF211F16, 0xFFF5EDD4) else SdmSurface, contentColor = if (highlighted) SdmGoldHigh else SdmMuted, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (highlighted) SdmGold.copy(alpha = .48f) else SdmLine), modifier = Modifier.height(38.dp)) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

@Composable
private fun DownloadTabs(selected: DownloadCategory, onSelect: (DownloadCategory) -> Unit) {
    Row(Modifier.fillMaxWidth().background(SdmSurface, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DownloadCategory.entries.forEach { category ->
            val active = selected == category
            Box(Modifier.weight(1f).height(40.dp).background(if (active) sdmColor(0xFF25251F, 0xFFF5EDD4) else Color.Transparent, RoundedCornerShape(10.dp)).then(if (active) Modifier.border(1.dp, SdmGold.copy(alpha = .28f), RoundedCornerShape(10.dp)) else Modifier).clickable { onSelect(category) }, contentAlignment = Alignment.Center) { Text(category.label, color = if (active) SdmGoldHigh else SdmMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

@Composable
private fun EmptyDownloads(category: DownloadCategory) {
    SdmEmptyState(
        if (category == DownloadCategory.Completed) "No completed downloads" else "No ${category.label.lowercase()} downloads",
        "Finished files will appear here.",
    )
}

@Composable
internal fun SdmEmptyState(title: String, description: String) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.8.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = TextAlign.Center,
    )
    // A native paragraph rounds its 24.8sp line box up. Round the complete
    // line-height + CSS margin once so a fractional density does not add a
    // second pixel between the two lines.
    val lineGap = with(LocalDensity.current) {
        ((textStyle.lineHeight.toPx() + 5.dp.toPx()).roundToInt() - ceil(textStyle.lineHeight.toPx()).toInt()).toDp()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = SdmText, fontWeight = FontWeight.Bold, style = textStyle)
        Text(description, color = SdmMuted, style = textStyle, modifier = Modifier.padding(top = lineGap))
    }
}

@Composable
private fun DownloadCard(item: DownloadCardModel, onOpen: (() -> Unit)?, onAction: () -> Unit) {
    val queued = item.category == DownloadCategory.Queued
    Surface(color = SdmSurface, contentColor = SdmText, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(width = 46.dp, height = 52.dp).background(if (queued) sdmColor(0xFF17181A, 0xFFF0ECE3) else sdmColor(0xFF181813, 0xFFF2EAD2), RoundedCornerShape(12.dp)).border(1.dp, if (queued) SdmLine else SdmGold.copy(alpha = .3f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(item.type, color = if (queued) SdmMuted else SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(item.name, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(item.size, color = SdmMuted, fontSize = 11.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.size(3.dp).background(sdmColor(0xFF5E5C56, 0xFF8C887E), CircleShape)); Spacer(Modifier.width(8.dp)); Text(item.metadataValue, color = SdmMuted, fontSize = 11.sp) } }
                Spacer(Modifier.width(12.dp))
                Surface(onClick = onAction, color = sdmColor(0xFF1C1D1F, 0xFFECE8DF), contentColor = SdmGoldHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(if (item.showPlayAction) SdmIcons.Play else SdmIcons.Pause, if (item.trailing == "Retry") "Retry" else if (item.showPlayAction) "Start" else "Pause", modifier = Modifier.size(19.dp)) } }
            }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(3.dp).background(sdmColor(0xFF34332F, 0xFFDED8CB), CircleShape)) { Box(Modifier.fillMaxWidth(if (queued) 0f else item.progress).height(3.dp).background(SdmGold, CircleShape)) }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp)) { Text(item.progressLabel, color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(item.trailing, color = SdmMuted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun HomeSheet(icon: ImageVector, eyebrow: String, title: String, description: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val visible = LocalHomeSheetVisible.current
    val slide = remember { Animatable(0f) }
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        launch { slide.animateTo(if (visible) 1f else 0f, tween(320, easing = CubicBezierEasing(.2f, .82f, .24f, 1f))) }
        launch { scrim.animateTo(if (visible) 1f else 0f, tween(200)) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().statusBarsPadding().background(Color.Black.copy(alpha = .72f * scrim.value)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).graphicsLayer { translationY = (size.height + 24.dp.toPx()) * (1f - slide.value); scaleX = .985f + .015f * slide.value; scaleY = scaleX; alpha = .72f + .28f * scrim.value }.clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), contentColor = SdmText, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)), shadowElevation = 18.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(17.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp).size(width = 42.dp, height = 4.dp).background(sdmColor(0xFF514F48, 0xFFB8B2A7), CircleShape))
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(44.dp).background(sdmColor(0xFF211F16, 0xFFF2EAD2), RoundedCornerShape(13.dp)).border(1.dp, SdmGold.copy(alpha = .32f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(21.dp)) }
                            Column(Modifier.weight(1f)) { Text(eyebrow, color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp); Text(title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp)); Text(description, color = SdmMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
                            Box(Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) { Icon(SdmIcons.Close, "Close", tint = SdmMuted, modifier = Modifier.size(18.dp)) }
                        }
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepActiveSheet(enabled: Boolean, onEnabled: (Boolean) -> Unit, duration: String, onDuration: (String) -> Unit, onDismiss: () -> Unit, onToast: (String) -> Unit) {
    HomeSheet(SdmIcons.KeepActive, "POWER MANAGEMENT", "Keep downloads active", "Keep ongoing transfers reliable while SDM runs in the background.", onDismiss) {
        Column(Modifier.padding(top = 16.dp)) {
            SheetSetting("Keep SDM awake", "Use a lightweight wake lock only while transfers need it", enabled, onEnabled)
            Spacer(Modifier.height(10.dp))
            ChoiceRow("While downloading", "Release when active transfers finish", duration == "downloading") { onDuration("downloading") }
            Spacer(Modifier.height(7.dp)); ChoiceRow("Until queue completes", "Stay active while active or queued items remain", duration == "queue") { onDuration("queue") }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).background(sdmColor(0xFF111816, 0xFFEAF5EF), RoundedCornerShape(13.dp)).border(1.dp, SdmSuccess.copy(alpha = .22f), RoundedCornerShape(13.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(SdmSuccess, CircleShape)); Spacer(Modifier.width(10.dp)); Column { Text("Managed automatically", color = sdmColor(0xFFA6DFC2, 0xFF236948), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(if (duration == "queue") "SDM stays awake only while active or queued items remain." else "Only active downloads keep the service awake.", color = SdmMuted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp)) } }
            SheetActions(onDismiss) { onDismiss(); onToast(if (!enabled) "Keep active disabled" else "Keep active saved · ${if (duration == "queue") "Until queue completes" else "While downloading"}") }
        }
    }
}

@Composable
private fun SpeedLimitSheet(unlimited: Boolean, onUnlimited: (Boolean) -> Unit, speed: Float, onSpeed: (Float) -> Unit, wifi: Boolean, onWifi: (Boolean) -> Unit, onDismiss: () -> Unit, onToast: (String) -> Unit) {
    HomeSheet(SdmIcons.Gauge, "BANDWIDTH CONTROL", "Global speed limit", "Cap total SDM traffic without changing individual downloads.", onDismiss) {
        Column(Modifier.padding(top = 16.dp)) {
            SheetSetting("Unlimited speed", "Use all available bandwidth", unlimited, onUnlimited)
            Text(if (unlimited) "Unlimited" else "${speed.toInt()} MB/s", color = SdmGoldHigh, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 15.dp)); Text("Combined download limit", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.alpha(if (unlimited) .38f else 1f)) {
            SpeedRange(speed, !unlimited, onSpeed)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("1 MB/s", color = SdmMuted, fontSize = 9.sp); Text("30 MB/s", color = SdmMuted, fontSize = 9.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(2, 5, 10, 20).forEach { preset -> PresetChip("$preset MB/s", speed.toInt() == preset && !unlimited, Modifier.weight(1f)) { if (!unlimited) onSpeed(preset.toFloat()) } } }
            }
            Spacer(Modifier.height(12.dp)); SheetSetting("Apply to Wi-Fi only", "Leave mobile-data traffic unrestricted", wifi, onWifi)
            SheetActions(onDismiss, primaryLabel = "Apply") { onDismiss(); onToast(if (unlimited) "Speed limit removed" else "Speed limited to ${speed.toInt()} MB/s") }
        }
    }
}

@Composable
private fun SpeedRange(value: Float, enabled: Boolean, onValueChange: (Float) -> Unit) {
    val gold = SdmGold
    val track = sdmColor(0xFFEFEFEF, 0xFFEFEFEF)
    val outline = sdmColor(0xFFB2B2B2, 0xFFB2B2B2)
    fun change(x: Float, width: Float, radius: Float) { if (enabled) onValueChange((1f + ((x - radius) / (width - radius * 2f)).coerceIn(0f, 1f) * 29f).roundToInt().toFloat()) }
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(34.dp)
        .semantics { contentDescription = "Global speed limit in megabytes per second"; progressBarRangeInfo = ProgressBarRangeInfo(value, 1f..30f, 28); setProgress { if (enabled) { onValueChange(it.roundToInt().coerceIn(1, 30).toFloat()); true } else false } }
        .pointerInput(enabled) { detectTapGestures { change(it.x, size.width.toFloat(), 8.dp.toPx()) } }
        .pointerInput(enabled) { detectDragGestures(onDragStart = { change(it.x, size.width.toFloat(), 8.dp.toPx()) }) { event, _ -> event.consume(); change(event.position.x, size.width.toFloat(), 8.dp.toPx()) } }) {
        val radius = 8.dp.toPx(); val thumbX = radius + (size.width - 2f * radius) * (value - 1f) / 29f
        drawLine(outline, Offset(radius, center.y), Offset(size.width - radius, center.y), 8.dp.toPx(), StrokeCap.Round)
        drawLine(track, Offset(radius, center.y), Offset(size.width - radius, center.y), 6.dp.toPx(), StrokeCap.Round)
        drawLine(gold, Offset(radius, center.y), Offset(thumbX, center.y), 8.dp.toPx(), StrokeCap.Round)
        drawCircle(gold, radius, Offset(thumbX, center.y))
    }
}

@Composable
private fun PreferencesSheet(onDismiss: () -> Unit, onToast: (String) -> Unit, onOpenSettings: () -> Unit) {
    val repository = SettingsRepository.get(LocalContext.current)
    val settings by repository.settings.collectAsState()
    var wifi by remember { mutableStateOf(settings.wifiOnly) }
    var resume by remember { mutableStateOf(settings.autoResume) }
    var notifications by remember { mutableStateOf(settings.downloadComplete) }
    var connections by remember { mutableIntStateOf(settings.connections) }
    var connectionsOpen by remember { mutableStateOf(false) }
    fun save() { repository.update { it.copy(wifiOnly = wifi, autoResume = resume, downloadComplete = notifications, connections = connections) } }
    HomeSheet(SdmIcons.DownloadPreferences, "QUICK SETUP", "Preferences", "Adjust the download controls you use most.", onDismiss) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetSetting("Wi-Fi only", "Pause downloads on mobile data", wifi) { wifi = it }; Spacer(Modifier.height(0.dp)); SheetSetting("Auto-resume", "Continue interrupted downloads", resume) { resume = it }; Spacer(Modifier.height(0.dp)); SheetSetting("Download notifications", "Alert when a transfer finishes", notifications) { notifications = it }; Spacer(Modifier.height(0.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Connections", fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("Parallel threads per download", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp)) }
                Box {
                    Surface(onClick = { connectionsOpen = true }, color = sdmColor(0xFF1B1C1F, 0xFFF6F3EC), contentColor = SdmGoldHigh, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.size(70.dp, 38.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { Text(connections.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Icon(SdmIcons.Chevron, null, modifier = Modifier.size(14.dp).rotate(90f)) } }
                    DropdownMenu(expanded = connectionsOpen, onDismissRequest = { connectionsOpen = false }, containerColor = sdmColor(0xFF1B1C1F, 0xFFFFFFFF), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SdmLine)) {
                        listOf(8, 16, 24, 32).forEach { value -> DropdownMenuItem(text = { Text(value.toString(), color = if (value == connections) SdmGoldHigh else SdmText, fontWeight = FontWeight.Bold) }, onClick = { connections = value; connectionsOpen = false }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).clickable { onToast("Save location editor opened") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(SdmIcons.FolderPlain, null, tint = SdmGoldHigh, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text("Save location", fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("/Download/SDM", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp)) }; Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(16.dp)) }
            Surface(onClick = { save(); onDismiss(); onOpenSettings() }, color = sdmColor(0xFF1E1C13, 0xFFF5EDD4), contentColor = SdmGoldHigh, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .46f)), modifier = Modifier.fillMaxWidth().height(48.dp)) { Box(contentAlignment = Alignment.Center) { Text("Open all settings", fontWeight = FontWeight.ExtraBold) } }
            Surface(onClick = { save(); onDismiss(); onToast("Preferences saved") }, color = sdmColor(0xFF191A1C, 0xFFECE8DF), contentColor = SdmText, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.fillMaxWidth().height(50.dp)) { Box(contentAlignment = Alignment.Center) { Text("Done", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) } }
            Text("Changes here stay synchronized with the full Settings screen.", color = SdmMuted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(start = 2.dp, top = 1.dp, end = 2.dp))
        }
    }
}

@Composable
private fun SheetSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = SdmMuted, fontSize = 10.sp, lineHeight = 13.5.sp, modifier = Modifier.padding(top = 3.dp)) }; SdmSwitch(title, checked, onChange) }
}

@Composable
private fun SdmSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) { Box(Modifier.size(width = 48.dp, height = 28.dp).semantics { contentDescription = title; stateDescription = if (checked) "On" else "Off" }.background(if (checked) sdmColor(0xFF332E15, 0xFFE8DCAE) else sdmColor(0xFF242528, 0xFFDED8CB), CircleShape).border(1.dp, if (checked) SdmGold.copy(alpha = .7f) else sdmColor(0xFF474641, 0xFFD8D2C4), CircleShape).clickable(role = Role.Switch) { onChange(!checked) }.padding(4.dp)) { Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(20.dp).background(if (checked) SdmGoldHigh else SdmMuted, CircleShape)) } }

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) sdmColor(0xFF211F16, 0xFFF5EDD4) else sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(13.dp)).border(1.dp, if (selected) SdmGold.copy(alpha = .55f) else SdmLine, RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(11.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(18.dp).border(2.dp, if (selected) SdmGold else SdmMuted, CircleShape), contentAlignment = Alignment.Center) { if (selected) Box(Modifier.size(8.dp).background(SdmGoldHigh, CircleShape)) }; Spacer(Modifier.width(10.dp)); Column { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp)) } } }

@Composable
private fun PresetChip(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) { Surface(onClick = onClick, color = if (active) sdmColor(0xFF262317, 0xFFF5EDD4) else sdmColor(0xFF111214, 0xFFFBFAF6), contentColor = if (active) SdmGoldHigh else SdmMuted, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (active) SdmGold.copy(alpha = .55f) else SdmLine), modifier = modifier.height(38.dp)) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) } } }

@Composable
private fun SheetActions(onCancel: () -> Unit, primaryLabel: String = "Save", onApply: () -> Unit) { Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { SheetActionButton("Cancel", false, Modifier.weight(1f), onCancel); SheetActionButton(primaryLabel, true, Modifier.weight(1.15f), onApply) } }

@Composable
private fun SheetActionButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) { Surface(onClick = onClick, color = if (primary) SdmGold else sdmColor(0xFF191A1C, 0xFFECE8DF), contentColor = if (primary) Color(0xFF080808) else SdmText, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (primary) SdmGold else SdmLine), modifier = modifier.height(50.dp)) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) } } }

@Composable
private fun DownloadDetailsScreen(
    download: Download,
    nowEpochMillis: Long,
    priorityActive: Boolean,
    actionScope: CoroutineScope,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onRename: suspend (String) -> DownloadRenameResult,
    onMoveToTop: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onPriority: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var headersOpen by remember(download.id) { mutableStateOf(false) }
    var segmentsOpen by remember(download.id) { mutableStateOf(false) }
    var cancelOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val speedTracker = remember(download.id) { DownloadDetailsSpeedTracker() }
    val hero = mapDownloadToDetailsPresentation(download, nowEpochMillis)
    val telemetry = remember(download, nowEpochMillis) {
        mapDownloadDetailsTelemetry(download, speedTracker.observe(download, nowEpochMillis))
    }
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = onBack)
    val density = LocalDensity.current
    val menuOffsetY = with(density) { WindowInsets.statusBars.getTop(this) + 58.dp.roundToPx() }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(SdmIcons.Back, "Back to downloads", tint = SdmText, modifier = Modifier.size(21.dp)) }; Text("Download details", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = { menuOpen = !menuOpen }, modifier = Modifier.size(48.dp)) { Icon(SdmIcons.More, "More download options", tint = SdmText, modifier = Modifier.size(21.dp)) } }
            if (menuOpen) Popup(alignment = Alignment.TopEnd, offset = IntOffset(with(density) { (-14).dp.roundToPx() }, menuOffsetY), onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = true)) {
                Surface(color = sdmColor(0xFF1B1C1F, 0xFFFFFFFF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine), shadowElevation = 18.dp, modifier = Modifier.width(232.dp)) {
                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("Rename", "Verify checksum", "Move to top").forEach { label ->
                            Box(
                                Modifier.fillMaxWidth().height(54.dp).clickable {
                                    menuOpen = false
                                    when (label) {
                                        "Rename" -> renameOpen = true
                                        "Verify checksum" -> {
                                            if (verifying) return@clickable
                                            verifying = true
                                            val selected = download
                                            actionScope.launch {
                                                try {
                                                    val result = DownloadChecksumVerifier.verify(selected)
                                                    onToast(checksumVerificationMessage(result))
                                                } finally {
                                                    verifying = false
                                                }
                                            }
                                        }
                                        "Move to top" -> onMoveToTop()
                                    }
                                }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(label, fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = SdmGold.copy(alpha = .14f))
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(bottom = 67.dp)) {
                item { DetailsHero(hero) }
                item { MetricsGrid(telemetry.metrics) }
                item { SpeedChart(telemetry) }
                item { DetailsActions(detailsPrimaryAction(download.state), priorityActive, onPause = onPause, onCancel = { cancelOpen = true }, onPriority = onPriority, onCopy = { clipboard.setText(AnnotatedString(hero.sourceUrl)); onToast("Source URL copied") }) }
                item { TechnicalInfo(telemetry.technical) }
                item {
                    DisclosureInfo(
                        headersOpen = headersOpen && telemetry.requestHeaders.available,
                        segmentsOpen = segmentsOpen && telemetry.segments.available,
                        headers = telemetry.requestHeaders,
                        segments = telemetry.segments,
                        onHeaders = { if (telemetry.requestHeaders.available) headersOpen = !headersOpen },
                        onSegments = { if (telemetry.segments.available) segmentsOpen = !segmentsOpen },
                    )
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(sdmColor(0xFF0D0E0F, 0xFFFAF8F2))) { HorizontalDivider(color = SdmLine); Surface(onClick = { onToast(detailsOpenFolderToast(download.destinationPath)) }, color = sdmColor(0xFF161612, 0xFFF5EDD4), contentColor = SdmGoldHigh, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .44f)), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(50.dp)) { Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(SdmIcons.FolderPlain, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)); Text("Open folder", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) } } }
        }
    }
    if (cancelOpen) CancelDownloadDialog({ cancelOpen = false }) {
        confirmCancelDownload(
            dispatchCancel = onCancel,
            closeDialog = { cancelOpen = false },
            returnToList = onBack,
        )
    }
    if (renameOpen) RenameDownloadDialog(
        fileName = download.fileName,
        submitting = renaming,
        onDismiss = { if (!renaming) renameOpen = false },
        onConfirm = { submittedName ->
            if (renaming) return@RenameDownloadDialog
            renaming = true
            actionScope.launch {
                try {
                    val result = onRename(submittedName)
                    onToast(downloadRenameActionMessage(result))
                    if (shouldCloseRenameDialog(result)) renameOpen = false
                } finally {
                    renaming = false
                }
            }
        },
    )
}

@Composable
private fun DetailsHero(hero: DownloadDetailsPresentation) {
    val ringTrack = sdmColor(0xFF252622, 0xFFDED8CB)
    val ringProgress = SdmGold
    val stateColor = when (hero.stateTone) {
        DownloadDetailsStateTone.Success -> SdmSuccess
        DownloadDetailsStateTone.Danger -> SdmDanger
        DownloadDetailsStateTone.Muted -> SdmMuted
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(178.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val radius = size.minDimension * (70f / 180f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val diameter = radius * 2f
                drawCircle(ringTrack, radius = radius, style = Stroke(stroke))
                drawArc(ringProgress, -90f, hero.ringSweepDegrees, false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(diameter, diameter), topLeft = topLeft)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(hero.percentLabel, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2.1).sp)
                Text(hero.stateLabel, color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
            }
        }
        Text(hero.fileName, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).widthIn(max = 310.dp), maxLines = 2)
        Text(hero.destinationDisplay, color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun MetricsGrid(metrics: DownloadDetailsMetricValues) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(metrics.speedValue to metrics.speedUnit, metrics.sizeValue to metrics.sizeUnit, metrics.remaining to "Remaining", metrics.connections to "Connections").forEach { (v, l) -> Column(Modifier.weight(1f).heightIn(min = 62.dp).background(SdmSurface, RoundedCornerShape(12.dp)).border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 11.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(l.uppercase(), color = SdmMuted, fontSize = 9.sp, letterSpacing = .54.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) } } } }

@Composable private fun SpeedChart(telemetry: DownloadDetailsTelemetryPresentation) {
    val gridColor = SdmLine
    val chartColor = SdmGold
    val points = normalizeDownloadDetailsSpeedChartPoints(telemetry.speedSamples)
    val caption = downloadDetailsSpeedChartCaption(
        samples = telemetry.speedSamples,
        speedValue = telemetry.metrics.speedValue,
        speedUnit = telemetry.metrics.speedUnit,
    )
    Surface(color = SdmSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, gridColor), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
        Column(Modifier.padding(15.dp)) {
            Row { Text("Speed history", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(caption, color = SdmMuted, fontSize = 10.sp) }
            Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(72.dp)) {
                listOf(12f, 36f, 60f).forEach { v -> val y = size.height * v / 72f; drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
                if (points.isEmpty()) return@Canvas
                val mapped = points.map { Offset(it.x * size.width, it.y * size.height) }
                val path = Path()
                if (mapped.size == 1) {
                    path.moveTo(0f, mapped[0].y)
                    path.lineTo(size.width, mapped[0].y)
                } else {
                    mapped.forEachIndexed { i, point -> if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
                }
                val areaPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(areaPath, chartColor.copy(alpha = .08f))
                drawPath(path, chartColor, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable private fun DetailsActions(primaryAction: TransferCardAction, priorityActive: Boolean, onPause: () -> Unit, onCancel: () -> Unit, onPriority: () -> Unit, onCopy: () -> Unit) { val primaryIcon = when (primaryAction) { TransferCardAction.Retry -> SdmIcons.Refresh; TransferCardAction.Resume -> SdmIcons.Play; TransferCardAction.Pause, TransferCardAction.None -> SdmIcons.Pause }; Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(Triple(primaryIcon,detailsPrimaryActionLabel(primaryAction),onPause),Triple(SdmIcons.Close,"Cancel",onCancel),Triple(SdmIcons.Star,"Priority",onPriority),Triple(SdmIcons.Copy,"Copy URL",onCopy)).forEachIndexed { i,(icon,label,action)-> val emphasized = i == 0 || (i == 2 && priorityActive); Column(Modifier.weight(1f).heightIn(min=68.dp).background(SdmSurface,RoundedCornerShape(13.dp)).border(1.dp,if(i == 0)SdmGold.copy(alpha=.38f)else SdmLine,RoundedCornerShape(13.dp)).clickable(onClick=action),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=if(i==1)sdmColor(0xFFF39A92,0xFFC2473E)else if(emphasized)SdmGoldHigh else SdmText,modifier=Modifier.size(20.dp));Text(label,color=if(i==1)sdmColor(0xFFF39A92,0xFFC2473E)else if(emphasized)SdmGoldHigh else SdmText,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))} } } }

@Composable private fun TechnicalInfo(technical: DownloadDetailsTechnicalValues) {
    val rows = buildList {
        add("Source host" to technical.sourceHost)
        add("Save path" to technical.savePath)
        add("Security" to technical.security)
        add("Resume support" to technical.resumeSupport)
        add("Connection threads" to technical.connectionThreads)
        technical.lastError?.let { add("Last error" to it) }
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text("TECHNICAL INFORMATION", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 12.dp))
        Surface(color = SdmSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine)) {
            Column {
                rows.forEachIndexed { i, (a, b) ->
                    if (i > 0) HorizontalDivider(color = SdmLine)
                    Row(Modifier.fillMaxWidth().heightIn(min = if (i == 0) 55.dp else 53.dp).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a, color = SdmMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            b,
                            color = when {
                                a == "Resume support" && b == "Available" -> SdmSuccess
                                a == "Last error" -> SdmDanger
                                else -> SdmText
                            },
                            fontSize = 12.sp,
                            maxLines = if (a == "Last error") 1 else Int.MAX_VALUE,
                            overflow = if (a == "Last error") TextOverflow.Ellipsis else TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureInfo(
    headersOpen: Boolean,
    segmentsOpen: Boolean,
    headers: DownloadDetailsRequestHeaders,
    segments: DownloadDetailsSegmentBreakdown,
    onHeaders: () -> Unit,
    onSegments: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).background(SdmSurface, RoundedCornerShape(16.dp)).border(1.dp, SdmLine, RoundedCornerShape(16.dp)).padding(1.dp)) {
        DisclosureRow("Request headers", headers.summary, headersOpen, onHeaders)
        if (headersOpen && headers.entries.isNotEmpty()) Text(
            headers.entries.joinToString("\n") { "${it.first}: ${it.second}" },
            color = SdmMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.5.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp).background(sdmColor(0xFF0B0C0D, 0xFFECE8DF), RoundedCornerShape(10.dp)).padding(10.dp),
        )
        DisclosureRow("Segment breakdown", segments.summary, segmentsOpen, onSegments)
        if (segmentsOpen && segments.segments.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
            segments.segments.forEach { segment ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(segment.label, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                    Box(Modifier.weight(1f).height(3.dp).background(sdmColor(0xFF30302C, 0xFFDED8CB), CircleShape)) { Box(Modifier.fillMaxWidth(segment.fraction.coerceIn(0f, 1f)).height(3.dp).background(SdmGold, CircleShape)) }
                    Text(segment.percentLabel, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                }
            }
        }
    }
}
@Composable private fun DisclosureRow(title:String,value:String,open:Boolean,onClick:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=54.dp).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(value,fontSize=12.sp);Icon(SdmIcons.Chevron,null,tint=SdmMuted,modifier=Modifier.padding(start=8.dp).size(16.dp).rotate(if(open)90f else 0f))}}

@Composable
private fun RenameDownloadDialog(
    fileName: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(fileName) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmLine)) {
                    Column(Modifier.padding(21.dp)) {
                        Text("Rename", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        BasicTextField(
                            value = value,
                            onValueChange = { if (!submitting) value = it },
                            singleLine = true,
                            enabled = !submitting,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(SdmGold),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp).height(48.dp)
                                .background(SdmSurface, RoundedCornerShape(14.dp))
                                .border(1.dp, SdmLine, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SheetActionButton("Cancel", false, Modifier.weight(1f), onDismiss)
                            SheetActionButton("Rename", true, Modifier.weight(1f)) {
                                if (!submitting) onConfirm(value)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CancelDownloadDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmLine)) {
                    Column(Modifier.padding(21.dp)) {
                        Text("Cancel download?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("The partial file will be kept so you can resume later.", color = SdmMuted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SheetActionButton("Keep downloading", false, Modifier.weight(1f), onDismiss)
                            Surface(onClick = onConfirm, color = sdmColor(0xFF191A1C, 0xFFECE8DF), contentColor = SdmDanger, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.weight(1f).height(50.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text("Cancel download", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                            }
                        }
                    }
                }
            }
        }
    }
}
