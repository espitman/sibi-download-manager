package com.espitman.sdm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.espitman.sdm.data.settings.SettingsRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.espitman.sdm.ui.theme.*

private enum class SettingsOverlay { Connections, Simultaneous, DailySchedule, SpeedLimit, Theme, Reset }

@Composable
internal fun SettingsScreen(
    showHeader: Boolean = true,
    uiState: SettingsUiState = rememberSettingsUiState(),
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { SettingsRepository.get(context) }
    val settings by repository.settings.collectAsState()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0" }
    val connections = settings.connections
    val simultaneous = settings.simultaneous
    val autoResume = settings.autoResume
    val wifiOnly = settings.wifiOnly
    val downloadComplete = settings.downloadComplete
    val speedAlerts = settings.speedAlerts
    val appliedTheme = settings.theme
    val themeLabel = if (appliedTheme == "light") "Light & Gold" else "Black & Gold"
    var backupOpen by remember { mutableStateOf(false) }
    if (backupOpen) BackupRestoreSheet({ backupOpen = false }, onToast)
    var pendingTheme by remember { mutableStateOf(appliedTheme) }
    var overlay by remember { mutableStateOf<SettingsOverlay?>(null) }
    var renderedOverlay by remember { mutableStateOf<SettingsOverlay?>(null) }
    LaunchedEffect(overlay) {
        if (overlay != null) renderedOverlay = overlay
        else {
            delay(SDM_SHEET_TRAVEL_MS.toLong())
            renderedOverlay = null
        }
    }

    fun toast(message: String) = onToast(message)
    val saveLocation = rememberSaveLocationActions(onToast)
    val activeQuery = settingsSearchActiveQuery(uiState.searchOpen, uiState.query)
    val visibleRows = remember(activeQuery, saveLocation.label, themeLabel, version) {
        filterSettingsRows(
            settingsSearchCatalog(
                saveLocationLabel = saveLocation.label,
                themeLabel = themeLabel,
                versionName = version,
            ),
            activeQuery,
        ).toSet()
    }
    val showAccount = activeQuery.isBlank()
    val showEmpty = activeQuery.isNotBlank() && visibleRows.isEmpty()
    BackHandler(uiState.searchOpen) { closeSettingsSearch(uiState) }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) AppHeader("Settings", showMore = false, onSearch = { toggleSettingsSearch(uiState) })
        AnimatedVisibility(uiState.searchOpen, enter = fadeIn(tween(140)), exit = fadeOut(tween(120))) {
            SettingsSearchPanel(
                query = uiState.query,
                onQueryChange = { uiState.query = it },
                onTrailingAction = { applySettingsSearchTrailingAction(uiState) },
            )
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp)) {
            if (showAccount) item { AccountCard() }
            if (showEmpty) {
                item { SdmEmptyState("No matching settings", "Try another search.") }
            } else {
                item {
                    FilteredSettingsGroup("DOWNLOAD BEHAVIOR", visibleRows) {
                        add(SettingsSearchRow.Connections) {
                            ValueRow(SdmIcons.Connections, "Connections", "Parallel threads per download", connections.toString(), chevron = true) { overlay = SettingsOverlay.Connections }
                        }
                        add(SettingsSearchRow.Simultaneous) {
                            ValueRow(SdmIcons.Simultaneous, "Simultaneous downloads", "Maximum active downloads", simultaneous.toString(), chevron = true) { overlay = SettingsOverlay.Simultaneous }
                        }
                        add(SettingsSearchRow.AutoResume) {
                            ToggleRow(SdmIcons.Refresh, "Auto-resume", "Continue interrupted downloads", autoResume) { repository.update { current -> current.copy(autoResume = it) }; toast(if (it) "Auto-resume enabled" else "Auto-resume disabled") }
                        }
                        add(SettingsSearchRow.DailySchedule) {
                            ValueRow(SdmIcons.Schedule, "Scheduled downloads", "Daily resume all and pause all", if (settings.dailyBulkScheduleEnabled) "On" else "Off", chevron = true) { overlay = SettingsOverlay.DailySchedule }
                        }
                    }
                }
                item {
                    FilteredSettingsGroup("NETWORK", visibleRows) {
                        add(SettingsSearchRow.WifiOnly) {
                            ToggleRow(SdmIcons.Wifi, "Wi-Fi only", "Pause downloads on mobile data", wifiOnly) { repository.update { current -> current.copy(wifiOnly = it) }; toast(if (it) "Wi-Fi only enabled" else "Mobile data downloads allowed") }
                        }
                        add(SettingsSearchRow.SpeedLimit) {
                            ValueRow(SdmIcons.Gauge, "Speed limit", "Combined download speed", if (settings.unlimitedSpeed) "Unlimited" else "${settings.speedLimitMbps.toInt()} MB/s", chevron = true) { overlay = SettingsOverlay.SpeedLimit }
                        }
                    }
                }
                item {
                    FilteredSettingsGroup("STORAGE", visibleRows) {
                        add(SettingsSearchRow.SaveLocation) {
                            ValueRow(SdmIcons.Folder, "Save location", saveLocation.label, chevron = true) { saveLocation.openPicker() }
                        }

                        add(SettingsSearchRow.Backup) {
                            ValueRow(SdmIcons.Folder, "Backup & restore", "Download list and app settings", chevron = true) { backupOpen = true }
                        }
                    }
                }
                item {
                    FilteredSettingsGroup("NOTIFICATIONS", visibleRows) {
                        add(SettingsSearchRow.DownloadComplete) {
                            ToggleRow(SdmIcons.Notifications, "Download complete", "Notify when a transfer finishes", downloadComplete) { repository.update { current -> current.copy(downloadComplete = it) }; toast(if (it) "Completion alerts enabled" else "Completion alerts disabled") }
                        }
                        add(SettingsSearchRow.SpeedAlerts) {
                            ToggleRow(SdmIcons.Speed, "Speed alerts", "Warn when transfers stall", speedAlerts) { repository.update { current -> current.copy(speedAlerts = it) }; toast(if (it) "Speed alerts enabled" else "Speed alerts disabled") }
                        }
                    }
                }
                item {
                    FilteredSettingsGroup("APPEARANCE", visibleRows) {
                        add(SettingsSearchRow.Theme) {
                            ValueRow(SdmIcons.Theme, "Theme", themeLabel, themeSwatch = true, lightSwatch = appliedTheme == "light") { pendingTheme = appliedTheme; overlay = SettingsOverlay.Theme }
                        }
                        add(SettingsSearchRow.Language) {
                            ValueRow(SdmIcons.Language, "Language", "English only", "Fixed")
                        }
                    }
                }
                item {
                    FilteredSettingsGroup("ABOUT", visibleRows) {
                        add(SettingsSearchRow.Version) {
                            ValueRow(SdmIcons.Info, "SDM version", "Sibi Download Manager", version)
                        }
                    }
                }
                if (SettingsSearchRow.Reset in visibleRows) {
                    item {
                        Surface(color = sdmColor(0xFF191414, 0xFFFFF4F2), contentColor = SdmDanger, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFFEF756B).copy(alpha = .32f)), modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(50.dp).clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { overlay = SettingsOverlay.Reset }) {
                            Box(contentAlignment = Alignment.Center) { Text("Reset settings", fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }
            }
        }
    }

    when (renderedOverlay) {
        SettingsOverlay.Connections -> SettingsSheet(SdmIcons.Connections, "DOWNLOAD BEHAVIOR", "Connections per download", "Choose the number of parallel threads used for each file.", { overlay = null }, overlay != null) {
            NumberGrid(listOf(8, 16, 24, 32), connections, 4) {
                repository.update { current -> current.copy(connections = it) }; overlay = null; toast("$it connections per download")
            }
        }
        SettingsOverlay.Simultaneous -> SettingsSheet(SdmIcons.Simultaneous, "DOWNLOAD BEHAVIOR", "Simultaneous downloads", "Choose how many downloads SDM can run at once.", { overlay = null }, overlay != null) {
            NumberGrid((1..10).toList(), simultaneous, 5) {
                repository.update { current -> current.copy(simultaneous = it) }; overlay = null; toast("$it simultaneous downloads")
            }
        }
        SettingsOverlay.DailySchedule -> DailyBulkScheduleSheet(
            settings = settings,
            visible = overlay != null,
            onDismiss = { overlay = null },
            onSave = { enabled, resumeMinute, pauseMinute ->
                repository.update { it.copy(dailyBulkScheduleEnabled = enabled, dailyResumeMinute = resumeMinute, dailyPauseMinute = pauseMinute) }
                com.espitman.sdm.download.DailyBulkSchedule.arm(context)
                overlay = null
                toast(if (enabled) "Daily schedule enabled" else "Daily schedule disabled")
            },
        )
        SettingsOverlay.SpeedLimit -> {
            var unlimited by remember { mutableStateOf(settings.unlimitedSpeed) }
            var limit by remember { mutableFloatStateOf(settings.speedLimitMbps) }
            var wifi by remember { mutableStateOf(settings.speedLimitWifiOnly) }
            CompositionLocalProvider(LocalHomeSheetVisible provides (overlay != null)) {
                SpeedLimitSheet(unlimited, { unlimited = it }, limit, { limit = it }, wifi, { wifi = it }, { overlay = null }) { message ->
                    repository.update { it.copy(unlimitedSpeed = unlimited, speedLimitMbps = limit, speedLimitWifiOnly = wifi) }
                    onToast(message)
                }
            }
        }
        SettingsOverlay.Theme -> SettingsSheet(SdmIcons.Theme, "APPEARANCE", "Choose theme", "Select the visual style for every SDM screen.", { overlay = null }, overlay != null) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ThemeChoice("Black & Gold", "Deep black surfaces with premium gold accents", false, pendingTheme == "dark") { pendingTheme = "dark" }
                ThemeChoice("Light & Gold", "Warm ivory surfaces with refined gold accents", true, pendingTheme == "light") { pendingTheme = "light" }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SheetButton("Cancel", false, Modifier.weight(1f)) { overlay = null }
                SheetButton("Apply theme", true, Modifier.weight(1.15f)) {
                    repository.update { it.copy(theme = pendingTheme) }; overlay = null; toast(if (pendingTheme == "light") "Light & Gold applied" else "Black & Gold applied")
                }
            }
        }
        SettingsOverlay.Reset -> ResetDialog(
            visible = overlay != null,
            onDismiss = { overlay = null },
            onReset = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { com.espitman.sdm.storage.CategoryFolderStore.get(context).replace(com.espitman.sdm.storage.CategoryFolderSettings()) }
                        repository.reset(); com.espitman.sdm.download.DailyBulkSchedule.arm(context); pendingTheme = "dark"; overlay = null; toast("Settings restored to defaults")
                    } catch (_: Exception) { toast("Could not reset settings") }
                }
            },
        )
        null -> Unit
    }

}

@Composable
internal fun SettingsSheet(icon: ImageVector, eyebrow: String, title: String, description: String, onDismiss: () -> Unit, visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val motion = rememberSdmSheetMotion(visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisableDialogWindowDim()
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().background(sdmSheetScrim(motion.scrim)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss).padding(start = 16.dp, end = 16.dp, bottom = designOverlayBottomInset()), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).onSizeChanged { panelHeight = it.height }.sdmSheetPanel(motion, panelHeight, extraTravel).clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), contentColor = SdmText, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0xFFD4AF37).copy(alpha = .35f)), shadowElevation = 18.dp) {
                Column(Modifier.padding(17.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp).size(width = 42.dp, height = 4.dp).background(Color(0xFF514F48), CircleShape))
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(sdmColor(0xFF211F16, 0xFFF2EAD2), RoundedCornerShape(13.dp)).border(1.dp, Color(0xFFD4AF37).copy(alpha = .32f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(21.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(eyebrow, color = SdmGoldHigh, fontSize = 11.sp, lineHeight = 13.2.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp)
                            // Preserve the reference's wrapping with the embedded font's narrower advances.
                            Text(title, fontSize = 17.sp, lineHeight = 22.1.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp).fillMaxWidth(.965f))
                            Text(description, color = SdmMuted, fontSize = 11.sp, lineHeight = 13.2.sp, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(.92f))
                        }
                        Box(Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) { Icon(SdmIcons.Close, "Close", tint = SdmMuted, modifier = Modifier.size(18.dp)) }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun NumberGrid(values: List<Int>, selected: Int, columns: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(columns).forEach { rowValues ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowValues.forEach { value ->
                    val active = value == selected
                    Surface(color = if (active) SdmGold else sdmColor(0xFF111214, 0xFFFBFAF6), contentColor = if (active) sdmColor(0xFF090909, 0xFFFFFFFF) else SdmMuted, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (active) SdmGold else SdmLine), modifier = Modifier.weight(1f).height(48.dp).clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onSelect(value) }, shadowElevation = if (active) 8.dp else 0.dp) {
                        Box(contentAlignment = Alignment.Center) { Text(value.toString(), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(title: String, subtitle: String, light: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).background(if (selected) sdmColor(0xFF211F16, 0xFFF5EDD4) else sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(15.dp)).border(1.dp, if (selected) Color(0xFFD4AF37).copy(alpha = .58f) else SdmLine, RoundedCornerShape(15.dp)).clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.size(width = 62.dp, height = 48.dp).background(if (light) Color(0xFFF4F1E9) else Color(0xFF080808), RoundedCornerShape(12.dp)).border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(if (light) Color.White else Color(0xFF1B1C1F), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().weight(1f).background(if (light) Color.White else Color(0xFF131416), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFFD4AF37).copy(alpha = .38f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(7.dp).background(if (light) Color.White else Color(0xFF1B1C1F), RoundedCornerShape(4.dp)))
        }
        Column(Modifier.weight(1f)) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = SdmMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
        Box(Modifier.size(20.dp).border(2.dp, if (selected) SdmGold else Color(0xFF5B5952), CircleShape), contentAlignment = Alignment.Center) { if (selected) Box(Modifier.size(9.dp).background(SdmGoldHigh, CircleShape)) }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Surface(modifier = modifier.height(50.dp).clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (primary) SdmGold else SdmLine), color = if (primary) SdmGold else SdmSurfaceAlt, contentColor = if (primary) Color(0xFF080808) else if (danger) SdmDanger else SdmText) { Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 13.sp, letterSpacing = 0.sp, fontWeight = FontWeight.ExtraBold) } }
}

@Composable
private fun ResetDialog(visible: Boolean, onDismiss: () -> Unit, onReset: () -> Unit) {
    val motion = rememberSdmSheetMotion(visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisableDialogWindowDim()
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().background(sdmSheetScrim(motion.scrim)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss).padding(start = 16.dp, end = 16.dp, bottom = designOverlayBottomInset()), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).onSizeChanged { panelHeight = it.height }.sdmSheetPanel(motion, panelHeight, extraTravel).clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), contentColor = SdmText, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmLine)) {
                Column(Modifier.padding(21.dp)) {
                    Text("Reset all settings?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("This restores SDM defaults. Your downloads and saved files will not be removed.", color = SdmMuted, fontSize = 13.sp, lineHeight = 19.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp).fillMaxWidth(.94f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { SheetButton("Keep settings", false, Modifier.weight(1f), onClick = onDismiss); SheetButton("Reset settings", false, Modifier.weight(1f), danger = true, onClick = onReset) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onTrailingAction: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        searchFocusRequester.requestFocus()
    }
    val trailingAction = settingsSearchTrailingAction(query)
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp),
            cursorBrush = SolidColor(SdmGold),
            modifier = Modifier.fillMaxWidth().height(48.dp).background(SdmSurface, RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(start = 46.dp, end = 42.dp).focusRequester(searchFocusRequester),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search settings", color = sdmColor(0xFF77746D, 0xFF77736A), fontSize = 13.sp)
                    inner()
                }
            },
        )
        Icon(SdmIcons.Search, null, tint = SdmMuted, modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp))
        IconButton(onClick = onTrailingAction, modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)) {
            Icon(
                SdmIcons.Close,
                if (trailingAction == SettingsSearchTrailingAction.ClearQuery) "Clear search" else "Close search",
                tint = SdmMuted,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private class SettingsGroupRowsScope(private val visible: Set<SettingsSearchRow>) {
    val rows = mutableListOf<@Composable () -> Unit>()
    fun add(row: SettingsSearchRow, content: @Composable () -> Unit) {
        if (row in visible) rows += content
    }
}

@Composable
private fun FilteredSettingsGroup(
    title: String,
    visible: Set<SettingsSearchRow>,
    build: SettingsGroupRowsScope.() -> Unit,
) {
    val shown = SettingsGroupRowsScope(visible).apply(build).rows
    if (shown.isEmpty()) return
    SettingsGroup(title) {
        shown.forEachIndexed { index, row ->
            if (index > 0) SettingDivider()
            row()
        }
    }
}

@Composable private fun AccountCard() { Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, Color(0xFFD4AF37).copy(alpha = .3f)), shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.size(50.dp).background(sdmColor(0xFF191914, 0xFFF2EAD2), RoundedCornerShape(15.dp)).border(1.dp, Color(0xFFD4AF37).copy(alpha = .5f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Text("SD", color = SdmGoldHigh, fontWeight = FontWeight.Black, fontSize = 16.sp) }; Column(Modifier.weight(1f)) { Text("SDM PREMIUM", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp); Text("Sibi Download Manager", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("High-speed downloads with private browsing and smart resume.", color = SdmMuted, fontSize = 11.sp, lineHeight = 15.95.sp, modifier = Modifier.padding(top = 5.dp)); Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(SdmSuccess, CircleShape)); Spacer(Modifier.width(6.dp)); Text("All systems ready", color = SdmSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold) } } } } }

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Column(Modifier.padding(top = 22.dp)) { Text(title, color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 12.dp)); Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmLine), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), content = content) } }
@Composable private fun SettingDivider() = HorizontalDivider(color = SdmLine, thickness = 1.dp)
@Composable private fun SettingIcon(icon: ImageVector) { Box(Modifier.size(36.dp).background(sdmColor(0xFF1D1E1F, 0xFFF2EAD2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp)) } }
@Composable private fun RowScope.SettingCopy(title: String, subtitle: String) { Column(Modifier.weight(1f)) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = SdmMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp)) } }

@Composable
private fun ValueRow(icon: ImageVector, title: String, subtitle: String, value: String = "", chevron: Boolean = false, themeSwatch: Boolean = false, lightSwatch: Boolean = false, onClick: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickModifier = if (onClick != null) Modifier.background(if (pressed) sdmColor(0xFF20211F, 0xFFECE8DF) else Color.Transparent).clickable(interaction, null, role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(clickModifier).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingIcon(icon); SettingCopy(title, subtitle)
        if (value.isNotEmpty()) Text(value, color = SdmGoldHigh, fontSize = if (chevron) 14.sp else 11.sp, fontWeight = FontWeight.ExtraBold)
        if (themeSwatch) Box(Modifier.size(width = 36.dp, height = 28.dp).background(if (lightSwatch) Color(0xFFF4F1E9) else Color(0xFF090909), RoundedCornerShape(9.dp)).border(1.dp, Color(0xFFD4AF37).copy(alpha = .45f), RoundedCornerShape(9.dp)).border(5.dp, if (lightSwatch) Color.White else Color(0xFF17181A), RoundedCornerShape(9.dp)))
        if (chevron) Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable internal fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { SettingIcon(icon); SettingCopy(title, subtitle); Box(Modifier.size(width = 48.dp, height = 28.dp).toggleable(checked, indication = null, interactionSource = remember { MutableInteractionSource() }, role = Role.Switch, onValueChange = onCheckedChange).background(if (checked) sdmColor(0xFF332E15, 0xFFE8DCAE) else sdmColor(0xFF242528, 0xFFECE8DF), CircleShape).border(1.dp, if (checked) Color(0xFFD4AF37).copy(alpha = .7f) else Color(0xFF474641), CircleShape).padding(4.dp)) { Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(20.dp).background(if (checked) SdmGoldHigh else SdmMuted, CircleShape)) } } }
