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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class DownloadCategory(val label: String) { Downloading("Downloading"), Queued("Queued"), Completed("Completed") }
internal enum class HomeOverlay { KeepActive, SpeedLimit, Preferences }
private val LocalHomeSheetVisible = staticCompositionLocalOf { true }

@Stable
internal class DownloadsUiState {
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var menuOpen by mutableStateOf(false)
    var overlay by mutableStateOf<HomeOverlay?>(null)
}

@Composable
internal fun rememberDownloadsUiState(): DownloadsUiState = remember { DownloadsUiState() }

private class DownloadItemState(
    val id: String,
    val type: String,
    val name: String,
    val size: String,
    val progress: Float,
    val progressLabel: String,
    val trailing: String,
    initialCategory: DownloadCategory,
    initialState: String,
) {
    var category by mutableStateOf(initialCategory)
    var state by mutableStateOf(initialState)
    var paused by mutableStateOf(false)
}

private val DownloadsSaver = listSaver<List<DownloadItemState>, Any>(
    save = { items -> items.flatMap { listOf(it.id, it.type, it.name, it.size, it.progress, it.progressLabel, it.trailing, it.category.name, it.state, it.paused) } },
    restore = { values -> values.chunked(10).map { DownloadItemState(it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as Float, it[5] as String, it[6] as String, DownloadCategory.valueOf(it[7] as String), it[8] as String).apply { paused = it[9] as Boolean } } },
)

@Composable
internal fun InteractiveDownloadsScreen(
    uiState: DownloadsUiState,
    showDetails: Boolean,
    showHeader: Boolean = true,
    onDetailsChange: (Boolean) -> Unit,
    onToast: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val downloads = rememberSaveable(saver = DownloadsSaver) {
        listOf(
            DownloadItemState("dune", "MKV", "Dune.Part.Two.2024.2160p.BluRay.mkv", "2.18 GB", .72f, "72% · 1.57 GB", "01:04 left", DownloadCategory.Downloading, "12.4 MB/s"),
            DownloadItemState("sdm", "APK", "SDM.Premium.v4.8.2.apk", "186 MB", .38f, "38% · 70.7 MB", "00:19 left", DownloadCategory.Downloading, "6.2 MB/s"),
            DownloadItemState("editorial", "ZIP", "Editorial_Assets_September.zip", "4.83 GB", 0f, "Next in queue", "Wi-Fi only", DownloadCategory.Queued, "Queued"),
        )
    }
    var filter by rememberSaveable { mutableStateOf(DownloadCategory.Downloading) }
    var filterApplied by rememberSaveable { mutableStateOf(false) }
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
    var keepAwake by rememberSaveable { mutableStateOf(true) }
    var awakeDuration by rememberSaveable { mutableStateOf("downloading") }
    var unlimitedSpeed by rememberSaveable { mutableStateOf(false) }
    var speedLimit by rememberSaveable { mutableFloatStateOf(10f) }
    var speedWifiOnly by rememberSaveable { mutableStateOf(false) }
    if (showDetails) {
        DownloadDetailsScreen(downloads.first(), onBack = { onDetailsChange(false) }, onToast = onToast)
        return
    }
    BackHandler(uiState.searchOpen) { uiState.searchOpen = false }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) DownloadsTopBar(uiState)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
        ) {
            item { DownloadStatusCard() }
            item { Spacer(Modifier.height(18.dp)); DownloadToolbar(downloads.size, onDownloadAll = {
                downloads.forEach { if (it.category != DownloadCategory.Completed) { it.category = DownloadCategory.Downloading; it.paused = false; if (it.state == "Queued") it.state = "Connecting…" } }
                filter = DownloadCategory.Downloading; onToast("All downloads started")
            }, onPauseAll = {
                downloads.filter { it.category == DownloadCategory.Downloading }.forEach { it.paused = true }
                onToast("All active downloads paused")
            }) }
            item { Spacer(Modifier.height(8.dp)); DownloadTabs(filter) { filter = it; filterApplied = true; uiState.query = "" }; Spacer(Modifier.height(12.dp)) }
            val visibleDownloads = downloads.filter { (!filterApplied || it.category == filter) && it.name.contains(uiState.query, ignoreCase = true) }
            if (visibleDownloads.isEmpty()) {
                item { EmptyDownloads(filter) }
            } else {
                items(visibleDownloads, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onOpen = if (item.id == "dune") ({ onDetailsChange(true) }) else null,
                        onAction = {
                            if (item.category == DownloadCategory.Queued) {
                                item.category = DownloadCategory.Downloading; item.state = "Connecting…"; item.paused = false; filter = DownloadCategory.Downloading
                                onToast("Queued file moved to downloading")
                            } else {
                                item.paused = !item.paused
                                onToast(if (item.paused) "Download paused" else "Download resumed")
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    CompositionLocalProvider(LocalHomeSheetVisible provides !overlayClosing) { when (uiState.overlay) {
        HomeOverlay.KeepActive -> KeepActiveSheet(keepAwake, { keepAwake = it }, awakeDuration, { awakeDuration = it }, dismissOverlay, onToast)
        HomeOverlay.SpeedLimit -> SpeedLimitSheet(unlimitedSpeed, { unlimitedSpeed = it }, speedLimit, { speedLimit = it }, speedWifiOnly, { speedWifiOnly = it }, dismissOverlay, onToast)
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
private fun DownloadStatusCard() {
    Surface(color = SdmSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .34f)), modifier = Modifier.fillMaxWidth()) {
        Box {
            Text("SDM", color = SdmGold.copy(alpha = .055f), fontSize = 86.sp, lineHeight = 86.sp, letterSpacing = (-6.8).sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Top) { Text("PREMIUM STATUS", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp, modifier = Modifier.weight(1f)); Box(Modifier.padding(top = 3.dp).size(7.dp).background(SdmSuccess, CircleShape)); Spacer(Modifier.width(6.dp)); Text("2 active", color = SdmSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Row(Modifier.padding(top = 10.dp).height(40.dp), verticalAlignment = Alignment.Bottom) { Text("18.6", fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.8).sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(6.dp)); Text("MB/s", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp)) }
                Text("Aggregate download speed", color = SdmMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
                Row(Modifier.fillMaxWidth()) { DownloadStat("8.42 GB", "Downloaded today", Modifier.weight(1f)); VerticalDivider(); DownloadStat("725 MB", "Active remaining", Modifier.weight(1f).padding(start = 10.dp)); VerticalDivider(); DownloadStat("16", "Connections", Modifier.weight(1f).padding(start = 10.dp)) }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(if (category == DownloadCategory.Completed) "No completed downloads" else "No ${category.label.lowercase()} downloads", fontWeight = FontWeight.Bold); Text("Finished files will appear here.", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
}

@Composable
private fun DownloadCard(item: DownloadItemState, onOpen: (() -> Unit)?, onAction: () -> Unit) {
    val queued = item.category == DownloadCategory.Queued
    Surface(color = SdmSurface, contentColor = SdmText, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(width = 46.dp, height = 52.dp).background(if (queued) sdmColor(0xFF17181A, 0xFFF0ECE3) else sdmColor(0xFF181813, 0xFFF2EAD2), RoundedCornerShape(12.dp)).border(1.dp, if (queued) SdmLine else SdmGold.copy(alpha = .3f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(item.type, color = if (queued) SdmMuted else SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(item.name, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(item.size, color = SdmMuted, fontSize = 11.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.size(3.dp).background(sdmColor(0xFF5E5C56, 0xFF8C887E), CircleShape)); Spacer(Modifier.width(8.dp)); Text(if (item.paused) "Paused" else item.state, color = SdmMuted, fontSize = 11.sp) } }
                Spacer(Modifier.width(12.dp))
                Surface(onClick = onAction, color = sdmColor(0xFF1C1D1F, 0xFFECE8DF), contentColor = SdmGoldHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(if (queued || item.paused) SdmIcons.Play else SdmIcons.Pause, if (queued || item.paused) "Start" else "Pause", modifier = Modifier.size(19.dp)) } }
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
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("sdm_settings", 0) }
    var wifi by rememberSaveable { mutableStateOf(prefs.getBoolean("wifi_only", true)) }; var resume by rememberSaveable { mutableStateOf(prefs.getBoolean("auto_resume", true)) }; var notifications by rememberSaveable { mutableStateOf(prefs.getBoolean("download_complete", true)) }; var connections by rememberSaveable { mutableIntStateOf(prefs.getInt("connections", 16)) }
    var connectionsOpen by remember { mutableStateOf(false) }
    fun save() { prefs.edit().putBoolean("wifi_only", wifi).putBoolean("auto_resume", resume).putBoolean("download_complete", notifications).putInt("connections", connections).apply() }
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
private fun DownloadDetailsScreen(item: DownloadItemState, onBack: () -> Unit, onToast: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }; var headersOpen by remember { mutableStateOf(false) }; var segmentsOpen by remember { mutableStateOf(false) }; var cancelOpen by remember { mutableStateOf(false) }
    var priorityActive by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = onBack)
    val density = LocalDensity.current
    val menuOffsetY = with(density) { WindowInsets.statusBars.getTop(this) + 58.dp.roundToPx() }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(SdmIcons.Back, "Back to downloads", tint = SdmText, modifier = Modifier.size(21.dp)) }; Text("Download details", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = { menuOpen = !menuOpen }, modifier = Modifier.size(48.dp)) { Icon(SdmIcons.More, "More download options", tint = SdmText, modifier = Modifier.size(21.dp)) } }
            if (menuOpen) Popup(alignment = Alignment.TopEnd, offset = IntOffset(with(density) { (-14).dp.roundToPx() }, menuOffsetY), onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = true)) {
                Surface(color = sdmColor(0xFF1B1C1F, 0xFFFFFFFF), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine), shadowElevation = 18.dp, modifier = Modifier.width(232.dp)) { Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { listOf("Rename" to "Download renamed", "Verify checksum" to "Integrity check scheduled", "Move to top" to "Download moved to top").forEach { (label, message) -> Box(Modifier.fillMaxWidth().height(54.dp).clickable { menuOpen = false; onToast(message) }.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) { Text(label, fontSize = 16.sp) } } } }
            }
        }
        HorizontalDivider(color = SdmGold.copy(alpha = .14f))
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(bottom = 67.dp)) {
                item { DetailsHero(item) }
                item { MetricsGrid() }
                item { SpeedChart() }
                item { DetailsActions(item.paused, priorityActive, onPause = { item.paused = !item.paused; onToast(if (item.paused) "Download paused" else "Download resumed") }, onCancel = { cancelOpen = true }, onPriority = { priorityActive = !priorityActive; onToast(if (priorityActive) "High priority enabled" else "Priority returned to normal") }, onCopy = { clipboard.setText(AnnotatedString("https://media.sibicdn.net/releases/Dune.Part.Two.2024.2160p.BluRay.mkv")); onToast("Source URL copied") }) }
                item { TechnicalInfo() }
                item { DisclosureInfo(headersOpen, segmentsOpen, { headersOpen = !headersOpen }, { segmentsOpen = !segmentsOpen }) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(sdmColor(0xFF0D0E0F, 0xFFFAF8F2))) { HorizontalDivider(color = SdmLine); Surface(onClick = { onToast("Opening /Download/SDM") }, color = sdmColor(0xFF161612, 0xFFF5EDD4), contentColor = SdmGoldHigh, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SdmGold.copy(alpha = .44f)), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(50.dp)) { Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(SdmIcons.FolderPlain, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)); Text("Open folder", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) } } }
        }
    }
    if (cancelOpen) CancelDownloadDialog({ cancelOpen = false }) { cancelOpen = false; item.state = "Canceled"; onBack(); onToast("Download canceled · Partial file kept") }
}

@Composable
private fun DetailsHero(item: DownloadItemState) {
    val ringTrack = sdmColor(0xFF252622, 0xFFDED8CB)
    val ringProgress = SdmGold
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(178.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val radius = size.minDimension * (70f / 180f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val diameter = radius * 2f
                drawCircle(ringTrack, radius = radius, style = Stroke(stroke))
                drawArc(ringProgress, -90f, 259.2f, false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(diameter, diameter), topLeft = topLeft)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("72%", fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2.1).sp)
                Text(if (item.paused) "PAUSED" else "ACTIVE", color = SdmSuccess, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
            }
        }
        Text(item.name, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).widthIn(max = 310.dp), maxLines = 2)
        Text("/Download/SDM", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun MetricsGrid() { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("12.4" to "MB/s", "1.57/2.18" to "GB", "01:04" to "Remaining", "16" to "Connections").forEach { (v, l) -> Column(Modifier.weight(1f).heightIn(min = 62.dp).background(SdmSurface, RoundedCornerShape(12.dp)).border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 11.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(l.uppercase(), color = SdmMuted, fontSize = 9.sp, letterSpacing = .54.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) } } } }

@Composable private fun SpeedChart() {
    val gridColor = SdmLine
    val chartColor = SdmGold
    Surface(color = SdmSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, gridColor), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
        Column(Modifier.padding(15.dp)) {
            Row { Text("Speed history", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("Last 60 sec · 12.4 MB/s", color = SdmMuted, fontSize = 10.sp) }
            Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(72.dp)) {
                val points = listOf(57f,50f,53f,40f,43f,28f,34f,25f,31f,19f,24f,16f,22f,13f,18f,11f,15f,9f)
                val step = size.width / (points.size - 1)
                listOf(12f, 36f, 60f).forEach { v -> val y = size.height * v / 72f; drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
                val path = Path()
                points.forEachIndexed { i, value -> val x = i * step; val y = value * size.height / 72f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
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

@Composable private fun DetailsActions(paused: Boolean, priorityActive: Boolean, onPause: () -> Unit, onCancel: () -> Unit, onPriority: () -> Unit, onCopy: () -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(Triple(if(paused) SdmIcons.Play else SdmIcons.Pause,if(paused)"Resume" else "Pause",onPause),Triple(SdmIcons.Close,"Cancel",onCancel),Triple(SdmIcons.Star,"Priority",onPriority),Triple(SdmIcons.Copy,"Copy URL",onCopy)).forEachIndexed { i,(icon,label,action)-> val emphasized = i == 0 || (i == 2 && priorityActive); Column(Modifier.weight(1f).heightIn(min=68.dp).background(SdmSurface,RoundedCornerShape(13.dp)).border(1.dp,if(i == 0)SdmGold.copy(alpha=.38f)else SdmLine,RoundedCornerShape(13.dp)).clickable(onClick=action),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=if(i==1)sdmColor(0xFFF39A92,0xFFC2473E)else if(emphasized)SdmGoldHigh else SdmText,modifier=Modifier.size(20.dp));Text(label,color=if(i==1)sdmColor(0xFFF39A92,0xFFC2473E)else if(emphasized)SdmGoldHigh else SdmText,fontSize=10.sp,modifier=Modifier.padding(top=7.dp))} } } }

@Composable private fun TechnicalInfo() {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text("TECHNICAL INFORMATION", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 12.dp))
        Surface(color = SdmSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, SdmLine)) {
            Column {
                listOf("Source host" to "media.sibicdn.net", "Save path" to "/Download/SDM", "Security" to "HTTPS · TLS 1.3", "Resume support" to "Available", "Connection threads" to "16 parallel").forEachIndexed { i, (a, b) ->
                    if (i > 0) HorizontalDivider(color = SdmLine)
                    Row(Modifier.fillMaxWidth().heightIn(min = if (i == 0) 55.dp else 53.dp).padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a, color = SdmMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(b, color = if (a == "Resume support") SdmSuccess else SdmText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclosureInfo(headers: Boolean, segments: Boolean, onHeaders: () -> Unit, onSegments: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).background(SdmSurface, RoundedCornerShape(16.dp)).border(1.dp, SdmLine, RoundedCornerShape(16.dp)).padding(1.dp)) {
        DisclosureRow("Request headers", "2 headers", headers, onHeaders)
        if (headers) Text("Accept: video/x-matroska\nUser-Agent: SDM/4.8 Android", color = SdmMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.5.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp).background(sdmColor(0xFF0B0C0D, 0xFFECE8DF), RoundedCornerShape(10.dp)).padding(10.dp))
        DisclosureRow("Segment breakdown", "16 threads", segments, onSegments)
        if (segments) Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
            listOf("#01–04" to .86f, "#05–08" to .74f, "#09–12" to .68f, "#13–16" to .6f).forEach { (label, progress) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                    Box(Modifier.weight(1f).height(3.dp).background(sdmColor(0xFF30302C, 0xFFDED8CB), CircleShape)) { Box(Modifier.fillMaxWidth(progress).height(3.dp).background(SdmGold, CircleShape)) }
                    Text("${(progress * 100).toInt()}%", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                }
            }
        }
    }
}
@Composable private fun DisclosureRow(title:String,value:String,open:Boolean,onClick:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=54.dp).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(value,fontSize=12.sp);Icon(SdmIcons.Chevron,null,tint=SdmMuted,modifier=Modifier.padding(start=8.dp).size(16.dp).rotate(if(open)90f else 0f))}}

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
