package com.espitman.sdm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

@Composable
internal fun BrowserSettingsSheet(
    onDismiss: () -> Unit,
    onClearData: () -> Unit,
    onToast: (String) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320)) }
    fun close() { scope.launch { progress.animateTo(0f, tween(200)); onDismiss() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f * progress.value)).clickable(onClick = ::close),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = sdmColor(0xFF17181A, 0xFFFFFFFF),
                contentColor = SdmText,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = designOverlayBottomInset())
                    .graphicsLayer {
                        translationY = (1f - progress.value) * 96.dp.toPx()
                        scaleX = .985f + .015f * progress.value
                        scaleY = .985f + .015f * progress.value
                        alpha = .72f + .28f * progress.value
                    }.clickable {},
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
                    BrowserSettingRow("Private by default", "Open new tabs without saving history", SdmIcons.Lock, true) {
                        onToast("All browser tabs are private")
                    }
                    BrowserSettingRow("Block trackers", "Reduce cross-site tracking", SdmIcons.Lock, true) {
                        onToast("Tracker blocking is enabled")
                    }
                    BrowserSettingRow("Clear on exit", "Remove tabs and browsing data", SdmIcons.Delete, false) {
                        onToast("Private tabs and browsing data clear when Browser closes")
                    }
                    Text("Search", color = SdmText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    BrowserSettingRow("Search engine", "Used from the address bar · Google", SdmIcons.Search, null) {
                        onToast("Google is the current search engine")
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp)
                            .background(sdmColor(0xFF191414, 0xFFFFF5F2), RoundedCornerShape(14.dp))
                            .clickable(onClick = onClearData).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(SdmIcons.Delete, null, tint = Color(0xFFEF756B), modifier = Modifier.size(20.dp))
                        Text("Clear browsing data", color = Color(0xFFEF756B), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
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
