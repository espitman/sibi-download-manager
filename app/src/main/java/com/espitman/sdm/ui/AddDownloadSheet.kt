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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import androidx.compose.ui.platform.LocalContext
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.download.DownloadSubmissionCoordinator
import com.espitman.sdm.download.SubmissionResult
import com.espitman.sdm.notification.NotificationPermissionHandoff
import com.espitman.sdm.notification.rememberTransferNotificationPermissionPreparer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.espitman.sdm.ui.theme.*
import com.espitman.sdm.network.ScopedRequestContext
import kotlinx.coroutines.launch

@Composable
internal fun AddDownloadSheet(
    onDismiss: () -> Unit,
    initialUrl: String = "",
    suggestedFileName: String? = null,
    requestContext: ScopedRequestContext? = null,
    coordinator: DownloadSubmissionCoordinator = AppRepositories.submissionCoordinator(LocalContext.current),
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var savedHandoffPhase by rememberSaveable {
        mutableStateOf(NotificationPermissionHandoff.Phase.Consumed.savedName)
    }
    var savedHandoffUrl by rememberSaveable { mutableStateOf("") }
    val permissionHandoff = NotificationPermissionHandoff.restore(savedHandoffPhase, savedHandoffUrl)
    fun publish(handoff: NotificationPermissionHandoff) {
        savedHandoffPhase = handoff.savedPhase
        savedHandoffUrl = handoff.savedPendingUrl
    }
    val clipboard = LocalClipboardManager.current
    val fileName = suggestedFileName?.takeIf { it.isNotBlank() }
        ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "Download" }
    val fileType = fileName.substringAfterLast('.', "FILE").uppercase().take(5)
    val motion = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismissAnimated: () -> Unit = {
        if (!closing && !isSubmitting) scope.launch {
            closing = true
            motion.animateTo(0f, tween(260, easing = CubicBezierEasing(.4f, 0f, .3f, 1f)))
            onDismiss()
        }
    }
    fun applyUrl(value: String) {
        if (isSubmitting) return
        url = value
        urlError = if (urlError == null) {
            null
        } else {
            (DownloadUrl.validate(value) as? DownloadUrlResult.Invalid)?.let { DownloadUrl.errorMessage(it.error) }
        }
    }
    fun launchSubmit(validatedUrl: String, startNow: Boolean) {
        scope.launch {
            try {
                when (val submissionResult = coordinator.submit(validatedUrl, startNow, requestContext)) {
                    is SubmissionResult.Success -> {
                        isSubmitting = false
                        dismissAnimated()
                    }
                    is SubmissionResult.Failure -> {
                        urlError = submissionResult.message
                        isSubmitting = false
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                urlError = "Failed to submit download"
                isSubmitting = false
            }
        }
    }
    val prepareForegroundNotifications = rememberTransferNotificationPermissionPreparer {
        val current = NotificationPermissionHandoff.restore(savedHandoffPhase, savedHandoffUrl)
        publish(current.onSystemResult())
    }
    fun submit(startNow: Boolean) {
        if (isSubmitting) return
        when (val result = DownloadUrl.validate(url)) {
            is DownloadUrlResult.Valid -> {
                url = result.url
                urlError = null
                isSubmitting = true
                if (startNow) {
                    val waiting = NotificationPermissionHandoff.awaitingPermission(result.url)
                    publish(waiting)
                    prepareForegroundNotifications.prepareForForegroundTransfer {
                        publish(waiting.onSystemResult())
                    }
                } else {
                    launchSubmit(result.url, startNow = false)
                }
            }
            is DownloadUrlResult.Invalid -> urlError = DownloadUrl.errorMessage(result.error)
        }
    }
    LaunchedEffect(permissionHandoff.phase) {
        if (permissionHandoff.phase == NotificationPermissionHandoff.Phase.ReadyToSubmit) {
            publish(permissionHandoff.markSubmitting())
        }
    }
    LaunchedEffect(permissionHandoff.phase, permissionHandoff.pendingUrl) {
        if (permissionHandoff.phase != NotificationPermissionHandoff.Phase.Submitting) return@LaunchedEffect
        val pendingUrl = permissionHandoff.pendingUrl ?: return@LaunchedEffect
        try {
            when (val submissionResult = coordinator.submit(pendingUrl, true, requestContext)) {
                is SubmissionResult.Success -> {
                    publish(permissionHandoff.consume())
                    isSubmitting = false
                    dismissAnimated()
                }
                is SubmissionResult.Failure -> {
                    urlError = submissionResult.message
                    publish(permissionHandoff.consume())
                    isSubmitting = false
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            urlError = "Failed to submit download"
            publish(permissionHandoff.consume())
            isSubmitting = false
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
                        IconButton(onClick = dismissAnimated, enabled = !isSubmitting, modifier = Modifier.size(44.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp))) {
                            Icon(SdmIcons.Close, "Close add download", tint = SdmMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
                        Text("Download link", color = SdmMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp)
                        Spacer(Modifier.height(8.dp))
                        BasicTextField(
                            value = url, onValueChange = ::applyUrl,
                            enabled = !isSubmitting,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 12.sp, lineHeight = 17.4.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            cursorBrush = SolidColor(SdmGold),
                            minLines = 3, maxLines = 4,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 104.dp)
                                .background(SdmSurface, RoundedCornerShape(14.dp))
                                .border(1.dp, if (urlError == null) SdmLine else SdmDanger, RoundedCornerShape(14.dp))
                                .semantics { urlError?.let { error(it) } }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            decorationBox = { inner -> Box { if (url.isEmpty()) Text("Paste a direct download URL", color = SdmMuted, fontSize = 12.sp); inner() } },
                        )
                        Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FieldTool("Paste", SdmIcons.Paste, enabled = !isSubmitting) { clipboard.getText()?.text?.let(::applyUrl) }
                            FieldTool("Clear", SdmIcons.Close, enabled = !isSubmitting) { applyUrl("") }
                        }
                        urlError?.let {
                            Text(
                                text = it,
                                color = SdmDanger,
                                fontSize = 11.sp,
                                lineHeight = 15.4.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp).padding(top = 8.dp),
                            )
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
                        Button(onClick = { submit(startNow = false) }, enabled = !isSubmitting, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = SdmMuted), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.weight(.58f).height(48.dp)) {
                            Text("Queue", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Button(onClick = { submit(startNow = true) }, enabled = url.isNotBlank() && !isSubmitting, colors = ButtonDefaults.buttonColors(containerColor = SdmGold, contentColor = Color(0xFF080808)), shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1.2f).height(48.dp)) {
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
private fun FieldTool(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) SdmMuted else SdmMuted.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) SdmMuted else SdmMuted.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
