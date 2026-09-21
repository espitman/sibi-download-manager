package com.espitman.sdm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun AddDownloadSheet(onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "Download" }
    val fileType = fileName.substringAfterLast('.', "FILE").uppercase().take(5)
    val motion = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissAnimated: () -> Unit = {
        if (!closing) scope.launch {
            closing = true
            motion.animateTo(0f, tween(260, easing = CubicBezierEasing(.4f, 0f, .3f, 1f)))
            onDismiss()
        }
    }
    LaunchedEffect(Unit) {
        motion.animateTo(1f, tween(320, easing = CubicBezierEasing(.2f, .82f, .24f, 1f)))
    }
    Dialog(onDismissRequest = dismissAnimated, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setWindowAnimations(0)
            }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f * motion.value)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismissAnimated)
                .statusBarsPadding().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, bottom = 36.dp, top = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).graphicsLayer {
                    translationY = (size.height + 36.dp.toPx()) * (1f - motion.value)
                }.clickable(remember { MutableInteractionSource() }, indication = null) {},
                color = sdmColor(0xFF151618, 0xFFFAF8F2), contentColor = SdmText,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
                border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp, bottom = 2.dp).size(width = 42.dp, height = 4.dp).background(Color(0xFF514F48), RoundedCornerShape(99.dp)))
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("New download", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = dismissAnimated, modifier = Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp))) {
                            Icon(SdmIcons.Close, "Close add download", tint = SdmMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
                        Text("Download link", color = SdmMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp)
                        Spacer(Modifier.height(8.dp))
                        BasicTextField(
                            value = url, onValueChange = { url = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 12.sp, lineHeight = 17.4.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            cursorBrush = SolidColor(SdmGold),
                            minLines = 3, maxLines = 4,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 104.dp).background(SdmSurface, RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                            decorationBox = { inner -> Box { if (url.isEmpty()) Text("Paste a direct download URL", color = SdmMuted, fontSize = 12.sp); inner() } },
                        )
                        Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FieldTool("Paste", SdmIcons.Paste) { clipboard.getText()?.text?.let { url = it } }
                            FieldTool("Clear", SdmIcons.Close) { url = "" }
                        }
                        if (url.isNotBlank()) {
                            Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 9.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(sdmColor(0xFF242218, 0xFFF3EDDD), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                    Text(fileType, color = SdmGoldHigh, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(fileType, color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = dismissAnimated, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = SdmMuted), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.weight(.58f).height(48.dp)) {
                            Text("Queue", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Button(onClick = dismissAnimated, enabled = url.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = SdmGold, contentColor = Color(0xFF080808)), shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1.2f).height(48.dp)) {
                            Icon(SdmIcons.Download, null, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("Download", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldTool(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.height(32.dp).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = SdmMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = SdmMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
