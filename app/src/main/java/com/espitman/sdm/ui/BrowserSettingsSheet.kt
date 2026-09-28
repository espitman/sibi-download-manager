package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

@Composable
internal fun BrowserSettingsSheet(
    preferences: BrowserPreferences,
    onDismiss: () -> Unit,
    onClearData: () -> Unit,
    onPreferencesChange: (BrowserPreferences) -> Unit,
    onToast: (String) -> Unit,
) {
    val sheetHost = rememberSdmSheetHost()
    val motion = rememberSdmSheetMotion(sheetHost.visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    var enginePickerOpen by remember { mutableStateOf(false) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    fun close() { sheetHost.dismissThen(onDismiss) }
    fun toggle(label: String, enabled: Boolean, next: BrowserPreferences) {
        onPreferencesChange(next)
        onToast("$label ${if (enabled) "enabled" else "disabled"}")
    }
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
                        ) { Icon(SdmIcons.Settings, null, tint = SdmGoldHigh, modifier = Modifier.size(21.dp)) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("BROWSER", color = SdmGoldHigh, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Browser settings", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Privacy and search preferences.", color = SdmMuted, fontSize = 11.sp)
                        }
                        Box(Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp)).clickable(onClick = ::close), contentAlignment = Alignment.Center) {
                            Icon(SdmIcons.Close, "Close", tint = SdmMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text("Privacy", color = SdmText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    BrowserSettingRow("Private by default", "Start privately when Browser opens", SdmIcons.Lock, preferences.privateByDefault) {
                        toggle("Private browsing", !preferences.privateByDefault, preferences.copy(privateByDefault = !preferences.privateByDefault))
                    }
                    BrowserSettingRow("Block trackers", "Reduce cross-site tracking", SdmIcons.Lock, preferences.blockTrackers) {
                        toggle("Tracker blocking", !preferences.blockTrackers, preferences.copy(blockTrackers = !preferences.blockTrackers))
                    }
                    BrowserSettingRow("Clear on exit", "Remove tabs and browsing data", SdmIcons.Delete, preferences.clearOnExit) {
                        toggle("Clear on exit", !preferences.clearOnExit, preferences.copy(clearOnExit = !preferences.clearOnExit))
                    }
                    Text("Search", color = SdmText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    BrowserSettingRow("Search engine", "Used from the address bar · ${preferences.searchEngine.label}", SdmIcons.Search, null) {
                        enginePickerOpen = true
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp)
                            .background(sdmColor(0xFF191414, 0xFFFFF5F2), RoundedCornerShape(14.dp))
                            .clickable(onClick = { sheetHost.dismissThen(onClearData) }).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(SdmIcons.Delete, null, tint = Color(0xFFEF756B), modifier = Modifier.size(20.dp))
                        Text("Clear browsing data", color = Color(0xFFEF756B), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
    if (enginePickerOpen) {
        BrowserSearchEngineSheet(
            selected = preferences.searchEngine,
            onDismiss = { enginePickerOpen = false },
            onSelect = { engine ->
                onPreferencesChange(preferences.copy(searchEngine = engine))
                onToast("${engine.label} is the current search engine")
                enginePickerOpen = false
            },
        )
    }
}

@Composable
private fun BrowserSearchEngineSheet(
    selected: BrowserSearchEngine,
    onDismiss: () -> Unit,
    onSelect: (BrowserSearchEngine) -> Unit,
) {
    SettingsSheet(
        SdmIcons.Search,
        "SEARCH",
        "Search engine",
        "Used from the address bar.",
        onDismiss,
        visible = true,
    ) {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            BrowserSearchEngine.entries.forEach { engine ->
                val active = engine == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(if (active) sdmColor(0xFF211F16, 0xFFF5EDD4) else sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(13.dp))
                        .border(1.dp, if (active) SdmGold.copy(alpha = .55f) else SdmLine, RoundedCornerShape(13.dp))
                        .clickable { onSelect(engine) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(18.dp).border(2.dp, if (active) SdmGold else Color(0xFF5B5952), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (active) Box(Modifier.size(8.dp).background(SdmGoldHigh, CircleShape))
                    }
                    Column {
                        Text(engine.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(engine.hostLabel, color = SdmMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserSettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).height(62.dp)
            .background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(sdmColor(0xFF25261F, 0xFFF2EAD2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = SdmMuted, fontSize = 10.sp)
        }
        if (checked != null) {
            Box(
                Modifier.size(width = 38.dp, height = 22.dp)
                    .background(if (checked) sdmColor(0xFF393216, 0xFFF5EDD4) else sdmColor(0xFF25272B, 0xFFECE8DF), CircleShape),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(horizontal = 3.dp).size(14.dp).background(if (checked) SdmGoldHigh else SdmMuted, CircleShape))
            }
        } else Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(16.dp))
    }
}
