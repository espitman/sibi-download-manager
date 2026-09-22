package com.espitman.sdm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import com.espitman.sdm.ui.theme.SdmSurfaceAlt
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay

private enum class Destination(val label: String, val icon: ImageVector) {
    Downloads("Downloads", SdmIcons.Download),
    Browser("Browser", SdmIcons.Browser),
    Add("Add", SdmIcons.Add),
    Files("Files", SdmIcons.Folder),
    Settings("Settings", SdmIcons.Settings),
}

@Composable
fun SdmApp(
    openDownloads: Boolean = false,
    openDownloadId: String? = null,
    onConsumed: () -> Unit = {},
) {
    val downloadsStateHolder = rememberSaveableStateHolder()
    val downloadsUiState = rememberDownloadsUiState()
    val filesUiState = rememberFilesUiState()
    var destination by remember { mutableStateOf(Destination.Downloads) }
    var showAddDownload by remember { mutableStateOf(false) }
    var browserDownloadRequest by remember { mutableStateOf<BrowserDownloadRequest?>(null) }
    var selectedDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf("") }
    var toastVisible by remember { mutableStateOf(false) }
    var toastSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(toastSequence) {
        if (toastSequence > 0) {
            toastVisible = true
            delay(2200)
            toastVisible = false
        }
    }
    LaunchedEffect(openDownloads, openDownloadId) {
        if (!openDownloads && openDownloadId == null) return@LaunchedEffect
        destination = Destination.Downloads
        showAddDownload = false
        downloadsUiState.searchOpen = false
        downloadsUiState.menuOpen = false
        downloadsUiState.overlay = null
        filesUiState.searchOpen = false
        selectedDownloadId = openDownloadId
        onConsumed()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SdmBackground,
        contentColor = SdmText,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (selectedDownloadId == null) {
                    Crossfade(
                        targetState = destination,
                        modifier = Modifier.fillMaxWidth(),
                        animationSpec = tween(120),
                        label = "mainHeaderTransition",
                    ) { targetDestination ->
                        when (targetDestination) {
                            Destination.Downloads, Destination.Add -> DownloadsTopBar(downloadsUiState)
                            Destination.Browser -> AppHeader("Browser", privateMode = true)
                            Destination.Files -> FilesTopBar(
                                uiState = filesUiState,
                                onToast = { toastMessage = it; toastSequence++ },
                            )
                            Destination.Settings -> AppHeader("Settings", showMore = false, onSearch = { toastMessage = "Settings search ready"; toastSequence++ })
                        }
                    }
                }
                AnimatedContent(
                    targetState = destination,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    transitionSpec = {
                        fadeIn(tween(110)) togetherWith fadeOut(tween(90))
                    },
                    label = "mainBodyTransition",
                ) { targetDestination ->
                    Box(Modifier.fillMaxSize().background(SdmBackground)) {
                        when (targetDestination) {
                            Destination.Downloads, Destination.Add -> downloadsStateHolder.SaveableStateProvider("downloads") { InteractiveDownloadsScreen(
                                uiState = downloadsUiState,
                                selectedDownloadId = selectedDownloadId,
                                showHeader = false,
                                onSelectedDownloadIdChange = { selectedDownloadId = it },
                                onToast = { toastMessage = it; toastSequence++ },
                                onOpenSettings = { selectedDownloadId = null; destination = Destination.Settings },
                            ) }
                            Destination.Browser -> BrowserScreen(
                                showHeader = false,
                                onDownloadRequested = { request ->
                                    browserDownloadRequest = request
                                    showAddDownload = true
                                },
                                onOpenDownloads = { destination = Destination.Downloads },
                                onToast = { toastMessage = it; toastSequence++ },
                            )
                            Destination.Files -> FilesScreen(
                                uiState = filesUiState,
                                showHeader = false,
                                onToast = { toastMessage = it; toastSequence++ },
                            )
                            Destination.Settings -> SettingsScreen(showHeader = false) { toastMessage = it; toastSequence++ }
                        }
                    }
                }
            }
            if (selectedDownloadId == null) {
                BottomNavigation(
                    selected = destination,
                    onSelect = {
                        if (it == Destination.Add) showAddDownload = true
                        else {
                            selectedDownloadId = null
                            downloadsUiState.searchOpen = false
                            downloadsUiState.menuOpen = false
                            if (it != Destination.Files) filesUiState.searchOpen = false
                            destination = it
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            AnimatedVisibility(
                visible = toastVisible,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { 18 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { 18 },
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).background(sdmColor(0xFF20211F, 0xFFFFFFFF), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFFD4AF37).copy(alpha = .35f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).background(Color(0xFF373117), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("✓", color = SdmGoldHigh, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(10.dp))
                    Text(toastMessage, fontSize = 12.sp, lineHeight = 16.2.sp)
                }
            }
        }
    }
    if (showAddDownload) {
        AddDownloadSheet(
            onDismiss = {
                showAddDownload = false
                browserDownloadRequest = null
            },
            initialUrl = browserDownloadRequest?.url.orEmpty(),
            suggestedFileName = browserDownloadRequest?.suggestedFileName,
            requestContext = browserDownloadRequest?.requestContext,
        )
    }
}

@Composable
internal fun AppHeader(title: String, privateMode: Boolean = false, showSort: Boolean = false, showMore: Boolean = true, onSearch: () -> Unit = {}) {
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
            Text(title, color = SdmText, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.36).sp, modifier = Modifier.weight(1f))
            if (privateMode) {
                Row(Modifier.height(30.dp).border(1.dp, SdmGold.copy(alpha = .3f), CircleShape).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(SdmGold, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("Private", color = SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                HeaderAction(SdmIcons.Search, "Search", onSearch)
                if (showSort) { Spacer(Modifier.width(6.dp)); HeaderAction(SdmIcons.Sort, "Sort files") }
                if (showMore) { Spacer(Modifier.width(6.dp)); HeaderAction(SdmIcons.More, "More options") }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SdmGold.copy(alpha = .14f))
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit = {}) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, description, tint = SdmText, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun BottomNavigation(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val inactive = sdmColor(0xFF898C8F, 0xFF77736A)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = designDockInset())
            .height(68.dp)
            .shadow(16.dp, RoundedCornerShape(30.dp))
            .background(sdmColor(0xE61B1F24, 0xE6FFFDF7), RoundedCornerShape(30.dp))
            .border(1.dp, sdmColor(0x14FFFFFF, 0x17181713), RoundedCornerShape(30.dp))
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.entries.forEach { item ->
            val active = selected == item || (item == Destination.Downloads && selected == Destination.Add)
            if (item == Destination.Add) {
                Column(
                    modifier = Modifier.weight(1f).requiredHeight(62.dp).offset(y = (-6).dp).clickable { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(52.dp).background(SdmGold, CircleShape).border(4.dp, SdmBackground, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(SdmIcons.Add, contentDescription = "Add download", tint = Color(0xFF080808), modifier = Modifier.size(23.dp))
                    }
                    Text("Add", color = SdmGoldHigh, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(if (active) SdmGold.copy(alpha = .13f) else Color.Transparent)
                        .clickable { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = if (active) SdmGoldHigh else inactive, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = if (active) SdmGoldHigh else inactive, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium)
                }
            }
        }
    }
}
