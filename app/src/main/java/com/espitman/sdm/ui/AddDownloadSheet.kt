package com.espitman.sdm.ui

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.espitman.sdm.domain.DownloadSchedule
import androidx.compose.ui.platform.LocalContext
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.download.DownloadSubmissionCoordinator
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
    onToast: (String) -> Unit = {},
    coordinator: DownloadSubmissionCoordinator = AppRepositories.submissionCoordinator(LocalContext.current),
) {
    val clipboard = LocalClipboardManager.current
    var url by remember(initialUrl) {
        mutableStateOf(initialDownloadUrl(initialUrl, clipboard.getText()?.text))
    }
    var urlError by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf<DownloadSchedule?>(null) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submittedCount by remember { mutableIntStateOf(0) }
    var submittingTotal by remember { mutableIntStateOf(0) }
    var submissionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var savedHandoffPhase by rememberSaveable {
        mutableStateOf(NotificationPermissionHandoff.Phase.Consumed.savedName)
    }
    var savedHandoffUrl by rememberSaveable { mutableStateOf("") }
    val permissionHandoff = NotificationPermissionHandoff.restore(savedHandoffPhase, savedHandoffUrl)
    fun publish(handoff: NotificationPermissionHandoff) {
        savedHandoffPhase = handoff.savedPhase
        savedHandoffUrl = handoff.savedPendingUrl
    }
    val parsedLinks = remember(url) { parseDownloadLinks(url) }
    val singleUrl = parsedLinks.urls.singleOrNull()
    val fileName = if (singleUrl == null) {
        "${parsedLinks.urls.size} download links"
    } else {
        suggestedFileName?.takeIf { it.isNotBlank() }
            ?: singleUrl.substringBefore('?').substringAfterLast('/').ifBlank { "Download" }
    }
    val fileType = if (singleUrl == null) "LINKS" else fileName.substringAfterLast('.', "FILE").uppercase().take(5)
    val sheetHost = rememberSdmSheetHost()
    val motion = rememberSdmSheetMotion(sheetHost.visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    val scope = rememberCoroutineScope()
    val dismissAnimated: () -> Unit = {
        submissionJob?.cancel()
        sheetHost.dismissThen(onDismiss)
    }
    fun applyUrl(value: String) {
        if (isSubmitting) return
        url = value
        urlError = null
    }
    suspend fun processBatch(input: String, startNow: Boolean) {
        val links = parseDownloadLinks(input)
        submissionJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        submittingTotal = links.urls.size
        submittedCount = 0
        val outcome = submitDownloadLinks(links.urls, onProgress = { done, _ -> submittedCount = done }) { link ->
            coordinator.submit(link, startNow, if (links.urls.size == 1) requestContext else null, schedule)
        }
        val remaining = links.invalidLines + outcome.failedUrls
        isSubmitting = false
        if (outcome.added > 0) {
            onToast(if (outcome.added == 1) "Download added" else "${outcome.added} downloads added")
        }
        if (remaining.isEmpty()) {
            sheetHost.dismissThen(onDismiss)
        } else {
            url = remaining.joinToString("\n")
            urlError = if (outcome.added > 0) {
                "${outcome.added} added · ${remaining.size} links need attention"
            } else {
                outcome.firstFailure ?: "${remaining.size} links are not valid download URLs"
            }
        }
    }
    fun launchSubmit(input: String, startNow: Boolean) {
        scope.launch {
            try {
                processBatch(input, startNow)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                isSubmitting = false
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
        val links = parseDownloadLinks(url)
        if (links.urls.isEmpty()) {
            urlError = if (url.isBlank()) DownloadUrl.errorMessage(com.espitman.sdm.domain.DownloadUrlError.EMPTY)
                else "Enter one direct HTTP or HTTPS URL per line."
            return
        }
        urlError = null
        isSubmitting = true
        if (startNow && schedule == null) {
            val waiting = NotificationPermissionHandoff.awaitingPermission(url)
            publish(waiting)
            prepareForegroundNotifications.prepareForForegroundTransfer {
                publish(waiting.onSystemResult())
            }
        } else {
            launchSubmit(url, startNow = false)
        }
    }
    LaunchedEffect(permissionHandoff.phase) {
        if (permissionHandoff.phase == NotificationPermissionHandoff.Phase.ReadyToSubmit) {
            publish(permissionHandoff.markSubmitting())
        }
    }
    LaunchedEffect(permissionHandoff.phase, permissionHandoff.pendingUrl) {
        if (permissionHandoff.phase != NotificationPermissionHandoff.Phase.Submitting) return@LaunchedEffect
        val pendingInput = permissionHandoff.pendingUrl ?: return@LaunchedEffect
        try {
            processBatch(pendingInput, true)
            publish(permissionHandoff.consume())
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            urlError = "Failed to submit download"
            publish(permissionHandoff.consume())
            isSubmitting = false
        }
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
            Modifier.fillMaxSize().background(sdmSheetScrim(motion.scrim)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismissAnimated)
                .statusBarsPadding().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, bottom = designOverlayBottomInset(), top = 12.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).onSizeChanged { panelHeight = it.height }.sdmSheetPanel(motion, panelHeight, extraTravel).clickable(remember { MutableInteractionSource() }, indication = null) {},
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
                            value = url, onValueChange = ::applyUrl,
                            enabled = !isSubmitting,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 12.sp, lineHeight = 17.4.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            cursorBrush = SolidColor(SdmGold),
                            minLines = 3, maxLines = 6,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp, max = 148.dp)
                                .background(SdmSurface, RoundedCornerShape(14.dp))
                                .border(1.dp, if (urlError == null) SdmLine else SdmDanger, RoundedCornerShape(14.dp))
                                .semantics { urlError?.let { error(it) } }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            decorationBox = { inner -> Box { if (url.isEmpty()) Text("Paste one download URL per line", color = SdmMuted, fontSize = 12.sp); inner() } },
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
                        if (parsedLinks.urls.isNotEmpty()) {
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
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp)
                                .background(SdmSurface, RoundedCornerShape(12.dp))
                                .border(1.dp, SdmLine, RoundedCornerShape(12.dp))
                                .clickable(enabled = !isSubmitting) { scheduleOpen = true }
                                .padding(horizontal = 13.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Schedule", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                scheduleSummary(schedule), color = SdmGoldHigh, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 176.dp),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(contentPadding = PaddingValues(horizontal = 8.dp), onClick = { submit(startNow = false) }, enabled = !isSubmitting, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = SdmMuted), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, SdmLine), modifier = Modifier.weight(.58f).height(48.dp)) {
                            Text(if (isSubmitting) "$submittedCount/$submittingTotal" else if (parsedLinks.urls.size > 1) "Queue ${parsedLinks.urls.size}" else "Queue", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        Button(onClick = { submit(startNow = true) }, enabled = url.isNotBlank() && !isSubmitting, colors = ButtonDefaults.buttonColors(containerColor = SdmGold, contentColor = Color(0xFF080808)), shape = RoundedCornerShape(15.dp), modifier = Modifier.weight(1.2f).height(48.dp)) {
                            Icon(SdmIcons.Download, null, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(if (schedule != null) "Add scheduled" else if (parsedLinks.urls.size > 1) "Download ${parsedLinks.urls.size}" else "Download", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
    if (scheduleOpen) DownloadScheduleSheet(schedule, true, { scheduleOpen = false }) {
        schedule = it
        scheduleOpen = false
    }
}

internal fun initialDownloadUrl(explicitUrl: String, clipboardText: String?): String {
    if (explicitUrl.isNotBlank()) return explicitUrl
    val links = parseDownloadLinks(clipboardText.orEmpty())
    return if (links.invalidLines.isEmpty()) links.urls.joinToString("\n") else ""
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
