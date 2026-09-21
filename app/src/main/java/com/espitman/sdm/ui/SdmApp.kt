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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private enum class Destination(val label: String, val icon: ImageVector) {
    Downloads("Downloads", SdmIcons.Download),
    Browser("Browser", SdmIcons.Browser),
    Add("Add", SdmIcons.Add),
    Files("Files", SdmIcons.Folder),
    Settings("Settings", SdmIcons.Settings),
}

private data class DownloadUi(
    val type: String,
    val name: String,
    val size: String,
    val state: String,
    val progress: Float,
    val progressLabel: String,
    val trailing: String,
    val queued: Boolean = false,
)

private val sampleDownloads = listOf(
    DownloadUi("MKV", "Dune.Part.Two.2024.2160p.BluRay.mkv", "2.18 GB", "12.4 MB/s", .72f, "72% · 1.57 GB", "01:04 left"),
    DownloadUi("APK", "SDM.Premium.v4.8.2.apk", "186 MB", "6.2 MB/s", .38f, "38% · 70.7 MB", "00:19 left"),
    DownloadUi("ZIP", "Editorial_Assets_September.zip", "4.83 GB", "Queued", 0f, "Next in queue", "Wi-Fi only", queued = true),
)

@Composable
fun SdmApp() {
    var destination by remember { mutableStateOf(Destination.Downloads) }
    var showAddDownload by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SdmBackground,
        contentColor = SdmText,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (destination) {
                Destination.Downloads, Destination.Add -> DownloadsScreen()
                Destination.Browser -> BrowserScreen()
                Destination.Files -> FilesScreen()
                Destination.Settings -> SettingsScreen()
            }
            BottomNavigation(
                selected = destination,
                onSelect = {
                    if (it == Destination.Add) showAddDownload = true else destination = it
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    if (showAddDownload) {
        AddDownloadSheet(onDismiss = { showAddDownload = false })
    }
}

@Composable
private fun DownloadsScreen() {
    Column(Modifier.fillMaxSize()) {
        AppHeader("Downloads")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard() }
            item { Column { Spacer(Modifier.height(8.dp)); DownloadToolbar() } }
            item { DownloadFilters() }
            items(sampleDownloads.size) { index -> DownloadCard(sampleDownloads[index]) }
        }
    }
}

@Composable
internal fun AppHeader(title: String, privateMode: Boolean = false, showSort: Boolean = false, showMore: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SdmBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF171712), RoundedCornerShape(11.dp))
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
                HeaderAction(SdmIcons.Search, "Search")
                if (showSort) { Spacer(Modifier.width(6.dp)); HeaderAction(SdmIcons.Sort, "Sort files") }
                if (showMore) { Spacer(Modifier.width(6.dp)); HeaderAction(SdmIcons.More, "More options") }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SdmGold.copy(alpha = .14f))
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, description: String) {
    IconButton(onClick = {}, modifier = Modifier.size(42.dp)) {
        Icon(icon, description, tint = SdmText, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun StatusCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = SdmSurface),
        border = BorderStroke(1.dp, SdmGold.copy(alpha = .34f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
          Text("SDM", color = SdmGold.copy(alpha = .055f), fontSize = 86.sp, lineHeight = 86.sp, letterSpacing = (-6.8).sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp))
          Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PREMIUM STATUS", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(7.dp).background(SdmSuccess, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("2 active", color = SdmSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("18.6", color = SdmText, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.8).sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(6.dp))
                Text("MB/s", color = SdmText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text("Aggregate download speed", color = SdmMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("8.42 GB", "Downloaded today", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(36.dp).background(SdmLine))
                Stat("725 MB", "Active remaining", Modifier.weight(1f).padding(start = 10.dp))
                Box(Modifier.width(1.dp).height(36.dp).background(SdmLine))
                Stat("16", "Connections", Modifier.weight(1f).padding(start = 10.dp))
            }
        }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 7.dp)) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = SdmMuted, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DownloadToolbar() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Downloads", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Text("3 items", color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
        }
        CompactButton("Download All", SdmIcons.DownloadAll, true)
        Spacer(Modifier.width(6.dp))
        CompactButton("Pause All", SdmIcons.Pause, false)
    }
}

@Composable
private fun CompactButton(label: String, icon: ImageVector, highlighted: Boolean) {
    Button(
        onClick = {},
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (highlighted) SdmGold.copy(alpha = .6f) else SdmLine),
        colors = ButtonDefaults.buttonColors(containerColor = if (highlighted) Color(0xFF211F16) else SdmSurface, contentColor = if (highlighted) SdmGoldHigh else SdmMuted),
        contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = Modifier.height(38.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DownloadFilters() {
    var selected by remember { mutableStateOf("Downloading") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SdmSurface)
            .padding(4.dp),
    ) {
        listOf("Downloading", "Queued", "Completed").forEach { label ->
            val active = selected == label
            val color by animateColorAsState(if (active) Color(0xFF25251F) else Color.Transparent, label = "filter")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color)
                    .then(if (active) Modifier.border(1.dp, SdmGold.copy(alpha = .35f), RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { selected = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) SdmGoldHigh else SdmMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadUi) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SdmSurface),
        border = BorderStroke(1.dp, SdmLine),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 52.dp)
                        .background(if (item.queued) Color(0xFF17181A) else Color(0xFF181813), RoundedCornerShape(12.dp))
                        .border(1.dp, if (item.queued) SdmLine else SdmGold.copy(alpha = .38f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(item.type, color = if (item.queued) SdmMuted else SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("${item.size}  ·  ${item.state}", color = SdmMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    color = SdmSurfaceAlt,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp),
                    onClick = {},
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (item.queued) SdmIcons.Play else SdmIcons.Pause, contentDescription = if (item.queued) "Start" else "Pause", tint = SdmGoldHigh, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF34332F), CircleShape)) {
                Box(Modifier.fillMaxWidth(item.progress).height(3.dp).background(SdmGold, CircleShape))
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(item.progressLabel, color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(item.trailing, color = SdmMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .height(68.dp)
            .background(Color(0xEF1B1F24), RoundedCornerShape(30.dp))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(30.dp))
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
                    Icon(item.icon, contentDescription = item.label, tint = if (active) SdmGoldHigh else SdmMuted, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = if (active) SdmGoldHigh else SdmMuted, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium)
                }
            }
        }
    }
}
