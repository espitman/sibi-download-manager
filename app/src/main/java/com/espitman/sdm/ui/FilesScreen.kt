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

private data class FileUi(val type: String, val name: String, val meta: String, val verified: Boolean = false)

private val files = listOf(
    FileUi("MKV", "Dune.Part.Two.2024.2160p.BluRay.mkv", "2.18 GB · Today, 14:32"),
    FileUi("APK", "SDM.Premium.v4.8.2.apk", "186 MB · Today, 13:08", verified = true),
    FileUi("ZIP", "Editorial_Assets_September.zip", "4.83 GB · Yesterday, 22:41"),
    FileUi("FLAC", "Hans_Zimmer_A_Time_of_Quiet.flac", "84 MB · Sep 18, 19:20"),
    FileUi("PDF", "SDM_User_Guide.pdf", "12.6 MB · Sep 17, 08:12"),
)

@Composable
internal fun FilesScreen() {
    var filter by remember { mutableStateOf("All") }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        AppHeader("Files", showSort = true)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StorageCard() }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("All", "Video", "Audio", "Documents", "APK", "Archives").forEach { label ->
                        Box(
                            Modifier.height(38.dp).background(if (filter == label) Color(0xFF252218) else SdmSurface, RoundedCornerShape(11.dp))
                                .border(1.dp, if (filter == label) SdmGold.copy(alpha = .55f) else SdmLine, RoundedCornerShape(11.dp))
                                .clickable { filter = label }.padding(horizontal = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (filter == label) SdmGoldHigh else SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { Text("RECENT FILES", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            items(files.size) { FileRow(files[it]) }
        }
    }
}

@Composable
private fun StorageCard() {
    Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("DEVICE STORAGE", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp)
                    Text("Downloads", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("SDM files across video, audio, apps, and archives.", color = SdmMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp, end = 12.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("82.4 GB", color = SdmGoldHigh, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("OF 128 GB", color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0xFF34332F))) {
                Box(Modifier.fillMaxWidth(.64f).height(5.dp).background(SdmGold))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("45.6 GB available", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("64% used", color = SdmMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FileRow(file: FileUi) {
    Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmLine), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 42.dp, height = 48.dp).background(Color(0xFF191914), RoundedCornerShape(11.dp)).border(1.dp, SdmGold.copy(alpha = .32f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
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
