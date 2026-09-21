package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.espitman.sdm.ui.theme.sdmColor
import kotlin.math.roundToInt

private data class FileUi(val type: String, val name: String, val meta: String, val verified: Boolean = false)

@Composable
internal fun FilesScreen(showHeader: Boolean = true) {
    var filter by remember { mutableStateOf("All") }
    val context = LocalContext.current
    val records by AppRepositories.downloads(context).downloads.collectAsState()
    val files = records.filter { it.state == DownloadState.COMPLETED }.map {
        FileUi(it.fileName.substringAfterLast('.', "FILE").uppercase().take(5), it.fileName, formatBytes(it.downloadedBytes))
    }
    val visibleFiles = files.filter { filter == "All" || when (it.type) {
        "MP4", "MKV", "WEBM", "AVI" -> "Video"
        "MP3", "FLAC", "WAV", "M4A" -> "Audio"
        "APK" -> "APK"
        "ZIP", "RAR", "7Z", "TAR", "GZ" -> "Archives"
        else -> "Documents"
    } == filter }
    val storage by produceState<Pair<Long, Long>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath).let { it.totalBytes - it.availableBytes to it.totalBytes } }.getOrNull()
        }
    }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) AppHeader("Files", showSort = true)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StorageCard(storage) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("All", "Video", "Audio", "Documents", "APK", "Archives").forEach { label ->
                        Box(
                            Modifier.height(38.dp).background(if (filter == label) sdmColor(0xFF252218, 0xFFF5EDD4) else SdmSurface, RoundedCornerShape(11.dp))
                                .border(1.dp, if (filter == label) SdmGold.copy(alpha = .55f) else SdmLine, RoundedCornerShape(11.dp))
                                .clickable { filter = label }.padding(horizontal = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (filter == label) SdmGoldHigh else SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { Text("RECENT FILES", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            if (visibleFiles.isEmpty()) {
                item {
                    SdmEmptyState("No matching files", "Try another search or file type.")
                }
            } else items(visibleFiles.size) { FileRow(visibleFiles[it]) }
        }
    }
}

@Composable
private fun StorageCard(storage: Pair<Long, Long>?) {
    val usedFraction = storage?.takeIf { it.second > 0 }?.let { (it.first.toFloat() / it.second).coerceIn(0f, 1f) }
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
                    Text(storage?.first?.let(::formatBytes) ?: "—", color = SdmGoldHigh, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(storage?.second?.let { "OF ${formatBytes(it)}" } ?: "—", color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(sdmColor(0xFF34332F, 0xFFDED8CB))) {
                Box(Modifier.fillMaxWidth(usedFraction ?: 0f).height(5.dp).background(SdmGold))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(storage?.let { "${formatBytes((it.second - it.first).coerceAtLeast(0))} available" } ?: "—", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(usedFraction?.let { "${(it * 100).roundToInt()}% used" } ?: "—", color = SdmMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FileRow(file: FileUi) {
    Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmLine), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 42.dp, height = 48.dp).background(sdmColor(0xFF191914, 0xFFF2EAD2), RoundedCornerShape(11.dp)).border(1.dp, SdmGold.copy(alpha = .32f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text(file.type, color = SdmGoldHigh, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.meta, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                Text(if (file.verified) "✓ Verified" else "✓ Complete", color = SdmSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            }
            Spacer(Modifier.width(11.dp))
            IconButton(onClick = {}, modifier = Modifier.size(40.dp)) { Icon(SdmIcons.More, "File actions", tint = SdmMuted, modifier = Modifier.size(18.dp)) }
        }
    }
}
