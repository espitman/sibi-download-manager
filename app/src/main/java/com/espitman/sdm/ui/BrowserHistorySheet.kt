package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

@Composable
internal fun BrowserHistorySheet(
    entries: List<BrowserHistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    val sheetHost = rememberSdmSheetHost()
    val motion = rememberSdmSheetMotion(sheetHost.visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    fun close() { sheetHost.dismissThen(onDismiss) }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisableDialogWindowDim()
        Box(
            Modifier.fillMaxSize().background(sdmSheetScrim(motion.scrim)).clickable(onClick = { close() }),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = sdmColor(0xFF17181A, 0xFFFFFFFF),
                contentColor = SdmText,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = designOverlayBottomInset())
                    .onSizeChanged { panelHeight = it.height }
                    .sdmSheetPanel(motion, panelHeight, extraTravel)
                    .clickable {},
            ) {
                Column(Modifier.padding(16.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).size(42.dp, 4.dp)
                            .background(sdmColor(0xFF514F48, 0xFFB8B2A7), CircleShape),
                    )
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(44.dp).background(sdmColor(0xFF211F16, 0xFFF2EAD2), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(SdmIcons.Refresh, null, tint = SdmGoldHigh, modifier = Modifier.size(21.dp)) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("HISTORY", color = SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            Text("History", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Recently visited pages.", color = SdmMuted, fontSize = 11.sp)
                        }
                        Box(Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp)).clickable(onClick = ::close), contentAlignment = Alignment.Center) {
                            Icon(SdmIcons.Close, "Close", tint = SdmMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (entries.isEmpty()) {
                        SdmEmptyState("No browsing history", "Pages you visit in regular tabs will appear here.")
                    } else {
                        Column(
                            Modifier.padding(top = 18.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            entries.forEach { entry ->
                                Row(
                                    Modifier.fillMaxWidth().height(62.dp)
                                        .background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(14.dp))
                                        .clickable { sheetHost.dismissThen { onOpen(entry.url) } }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(32.dp).background(sdmColor(0xFF25261F, 0xFFF2EAD2), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(SdmIcons.Browser, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp)) }
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(entry.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(browserHistoryDisplayHost(entry.url), color = SdmMuted, fontSize = 10.sp, maxLines = 1)
                                    }
                                    Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp)
                                .background(sdmColor(0xFF191414, 0xFFFFF5F2), RoundedCornerShape(14.dp))
                                .clickable(onClick = onClear).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(SdmIcons.Delete, null, tint = Color(0xFFEF756B), modifier = Modifier.size(20.dp))
                            Text("Clear history", color = Color(0xFFEF756B), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}
