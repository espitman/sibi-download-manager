package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.espitman.sdm.ui.theme.SdmDanger
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

@Composable
internal fun SdmRenameDialog(
    fileName: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(fileName) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmLine)) {
                    Column(Modifier.padding(21.dp)) {
                        Text("Rename", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        BasicTextField(
                            value = value,
                            onValueChange = { if (!submitting) value = it },
                            singleLine = true,
                            enabled = !submitting,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 13.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(SdmGold),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp).height(48.dp)
                                .background(SdmSurface, RoundedCornerShape(14.dp))
                                .border(1.dp, SdmLine, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SdmDialogActionButton("Cancel", false, Modifier.weight(1f), onDismiss)
                            SdmDialogActionButton("Rename", true, Modifier.weight(1f)) {
                                if (!submitting) onConfirm(value)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SdmConfirmDialog(
    title: String,
    message: String,
    dismissLabel: String,
    confirmLabel: String,
    submitting: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}, color = sdmColor(0xFF17181A, 0xFFFFFFFF), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, SdmLine)) {
                    Column(Modifier.padding(21.dp)) {
                        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(message, color = SdmMuted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SdmDialogActionButton(dismissLabel, false, Modifier.weight(1f), onDismiss)
                            Surface(
                                onClick = { if (!submitting) onConfirm() },
                                color = sdmColor(0xFF191A1C, 0xFFECE8DF),
                                contentColor = SdmDanger,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, SdmLine),
                                modifier = Modifier.weight(1f).height(50.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SdmDialogActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (primary) SdmGold else sdmColor(0xFF191A1C, 0xFFECE8DF),
        contentColor = if (primary) Color(0xFF080808) else SdmText,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (primary) SdmGold else SdmLine),
        modifier = modifier.height(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
