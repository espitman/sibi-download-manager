package com.espitman.sdm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import com.espitman.sdm.network.DownloadMetadata
import com.espitman.sdm.network.DownloadMetadataResult
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private data class RefreshCandidate(val metadata: DownloadMetadata, val targetId: String?)

@Composable
internal fun RefreshDownloadLinksSheet(downloads: List<Download>, onDismiss: () -> Unit, onToast: (String) -> Unit, initialUrl: String = "", browserContext: com.espitman.sdm.network.ScopedRequestContext? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialUrl) }
    var useSession by remember { mutableStateOf(true) }
    var retainSession by remember { mutableStateOf(true) }
    val selectedContext = browserContext?.takeIf { useSession }?.copy(retainSession = retainSession)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<RefreshCandidate>>(emptyList()) }
    var choosing by remember { mutableStateOf<Int?>(null) }
    var restartConfirmed by remember { mutableStateOf(setOf<String>()) }
    var checkedDownloads by remember { mutableStateOf(downloads) }
    val single = downloads.size == 1
    val repository = AppRepositories.downloads(context)
    val matched = candidates.filter { row -> row.targetId != null &&
        candidates.count { it.targetId == row.targetId } == 1 }
    val applicable = matched.filter { row ->
        val download = checkedDownloads.first { it.id == row.targetId }
        DownloadLinkRefresh.canPreserve(download, row.metadata) || download.id in restartConfirmed
    }
    SettingsSheet(SdmIcons.Link, "DOWNLOAD LINK", if (browserContext != null) "Update browser session" else if (single) "Refresh download link" else "Refresh selected links",
        if (single) "Active downloads pause while checking the new link." else "${downloads.size} downloads selected. Active downloads pause while checking.",
        onDismiss, true) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (single) {
                Text(downloads.first().fileName, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                Text("${formatBytes(downloads.first().downloadedBytes)} downloaded", color = SdmMuted,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(if (single) "New download link" else "New links — one per line", fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            BasicTextField(text, { if (!busy) { text = it; candidates = emptyList(); restartConfirmed = emptySet(); error = null } },
                enabled = !busy, textStyle = androidx.compose.ui.text.TextStyle(color = SdmText, fontSize = 12.sp),
                cursorBrush = SolidColor(SdmGold), modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 140.dp)
                    .background(SdmSurface, RoundedCornerShape(14.dp)).border(1.dp, SdmLine, RoundedCornerShape(14.dp)).padding(14.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Paste", color = SdmGold, modifier = Modifier.clickable(enabled = !busy) {
                    clipboard.getText()?.text?.let { text = it; candidates = emptyList(); restartConfirmed = emptySet() }
                })
                Text("Clear", color = SdmMuted, modifier = Modifier.clickable(enabled = !busy) {
                    text = ""; candidates = emptyList(); restartConfirmed = emptySet(); error = null
                })
            }
            candidates.forEachIndexed { index, row ->
                val target = checkedDownloads.firstOrNull { it.id == row.targetId }
                val unique = row.targetId != null && candidates.count { it.targetId == row.targetId } == 1
                val safe = target != null && DownloadLinkRefresh.canPreserve(target, row.metadata)
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Text(target?.fileName ?: row.metadata.suggestedFilename, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (!unique) "Select a unique download" else if (safe) "Same file · Progress will be preserved"
                        else "Cannot safely reuse downloaded bytes", color = if (safe && unique) SdmSuccess else SdmGold,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    if (!single) Text("Assign file", color = SdmGold, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp).clickable(enabled = !busy) { choosing = if (choosing == index) null else index })
                    if (choosing == index) downloads.forEach { download ->
                        Text(download.fileName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                candidates = candidates.mapIndexed { i, item -> if (i == index) item.copy(targetId = download.id) else item }
                                choosing = null; restartConfirmed = emptySet()
                            }.padding(vertical = 8.dp))
                    }
                    if (unique && !safe && target != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clickable(enabled = !busy) {
                            restartConfirmed = if (target.id in restartConfirmed) restartConfirmed - target.id else restartConfirmed + target.id
                        }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        val confirmed = target.id in restartConfirmed
                        Box(Modifier.size(20.dp).background(if (confirmed) SdmGold else SdmSurface, RoundedCornerShape(5.dp))
                            .border(1.dp, if (confirmed) SdmGold else SdmLine, RoundedCornerShape(5.dp)),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (confirmed) Icon(SdmIcons.Check, "Restart confirmed", tint = SdmBackground, modifier = Modifier.size(14.dp))
                        }
                        Text("Delete partial data and restart from zero", fontSize = 11.sp, color = SdmMuted)
                    }
                }
            }
            if (browserContext != null) BrowserSessionConsent(useSession, retainSession, browserContext.isPrivate, !busy, { useSession = it }, { retainSession = it })
            error?.let { Text(it, color = SdmDanger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            Button(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val parsed = parseDownloadLinks(text)
                        require(parsed.invalidLines.isEmpty() && parsed.urls.isNotEmpty()) { "Enter valid HTTP/HTTPS links, one per line." }
                        require(!single || parsed.urls.size == 1) { "Enter one link for this download." }
                        for (selected in downloads) {
                            val current = repository.get(selected.id) ?: error("Download no longer exists")
                            if (current.state in setOf(DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING)) {
                                com.espitman.sdm.download.DownloadTransferService.pauseTransfer(context, current.id)
                                withTimeout(10_000) {
                                    repository.downloads.first { all -> all.firstOrNull { it.id == current.id }?.state !in
                                        setOf(DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING) }
                                }
                            }
                        }
                        checkedDownloads = downloads.map { repository.get(it.id) ?: error("Download no longer exists") }
                        val checked = mutableListOf<RefreshCandidate>()
                        for (url in parsed.urls) {
                            when (val result = AppRepositories.metadataRetriever().retrieve(url, if (browserContext != null) selectedContext else if (single) com.espitman.sdm.network.BrowserRequestContextRegistry.get(downloads.first().id) else null)) {
                                is DownloadMetadataResult.Success -> {
                                    val target = if (single) checkedDownloads.first() else checkedDownloads.singleOrNull {
                                        it.fileName.equals(result.metadata.suggestedFilename, ignoreCase = true)
                                    }
                                    checked += RefreshCandidate(result.metadata, target?.id)
                                }
                                is DownloadMetadataResult.Failure -> error(ErrorReportSanitizer.sanitize(result.message))
                            }
                        }
                        candidates = checked; restartConfirmed = emptySet()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Unable to check links" }
                    finally { busy = false }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = SdmSurface, contentColor = SdmGold),
                modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp)) {
                Text(if (busy) "Working…" else "Check link", maxLines = 1)
            }
            if (candidates.isNotEmpty()) Button(enabled = !busy && applicable.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    var replaced = 0
                    try {
                        for (row in applicable) {
                            val target = checkedDownloads.first { it.id == row.targetId }
                            val previousContext = com.espitman.sdm.network.BrowserRequestContextRegistry.get(target.id)
                            if (browserContext != null) com.espitman.sdm.network.BrowserRequestContextRegistry.put(target.id, selectedContext)
                            try {
                                repository.refreshLink(target.id, target, row.metadata, target.id in restartConfirmed, System.currentTimeMillis())
                            } catch (failure: Exception) {
                                if (browserContext != null) com.espitman.sdm.network.BrowserRequestContextRegistry.put(target.id, previousContext)
                                throw failure
                            }
                            replaced++
                            candidates = candidates.filter { it != row }
                            AppRepositories.queueScheduler(context).resume(target.id)
                        }
                        onToast("$replaced download links replaced")
                        if (candidates.isEmpty()) onDismiss()
                        else { candidates = candidates.filter { it !in applicable }; error = "Remaining links need review." }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = "$replaced replaced. ${failure.message ?: "Unable to replace link"}" }
                    finally { busy = false }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = SdmGold, contentColor = SdmBackground),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp)) {
                Text(if (single && restartConfirmed.isNotEmpty()) "Restart download" else if (single) "Replace & resume"
                    else "Replace ${applicable.size} matched links", maxLines = 1)
            }
        }
    }
}
