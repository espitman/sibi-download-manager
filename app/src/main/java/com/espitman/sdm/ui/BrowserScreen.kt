package com.espitman.sdm.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import java.io.ByteArrayInputStream
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.espitman.sdm.ui.theme.SdmBackground
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor
import com.espitman.sdm.network.ScopedRequestContext

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen(
    showHeader: Boolean = true,
    onDownloadRequested: (BrowserDownloadRequest) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onToast: (String) -> Unit = {},
) {
    var tabs by remember { mutableStateOf(initialBrowserTabs()) }
    var nextTabOrdinal by remember { mutableIntStateOf(3) }
    var tabsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var privateReady by remember { mutableStateOf(false) }
    var desktopSite by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(TextFieldValue(tabs.active.url.orEmpty())) }
    var addressFocused by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val webViews = remember { mutableMapOf<String, WebView>() }
    var loadFailure by remember { mutableStateOf<BrowserLoadFailure?>(null) }
    val focusManager = LocalFocusManager.current
    val addressFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val addressScope = rememberCoroutineScope()

    fun navigate(input: String) {
        normalizeBrowserInput(input)?.let { url ->
            loadFailure = null
            currentUrl = url
            address = TextFieldValue(url)
            tabs = tabs.update(tabs.activeId, url, tabs.active.title)
        }
        focusManager.clearFocus()
    }

    fun goBack() {
        if (webView?.canGoBack() == true) {
            loadFailure = null
            webView?.goBack()
        } else {
            currentUrl = null
            address = TextFieldValue("")
            tabs = tabs.update(tabs.activeId, null, "New tab")
            loadFailure = null
        }
    }

    fun selectTab(id: String) {
        tabs = tabs.select(id)
        val active = tabs.active
        currentUrl = active.url
        address = TextFieldValue(active.url.orEmpty())
        webView = webViews[id]
        loadFailure = null
        tabsOpen = false
    }

    fun addTab(isPrivate: Boolean) {
        val id = "tab-${nextTabOrdinal++}"
        // The browser is a private session by design; the ordinary New tab action
        // follows the private-by-default policy shown in the reference.
        tabs = tabs.add(BrowserTab(id, if (isPrivate) "Private tab" else "New tab", null, isPrivate = true))
        currentUrl = null
        address = TextFieldValue("")
        webView = null
        loadFailure = null
        tabsOpen = false
    }

    fun closeTab(id: String) {
        if (tabs.tabs.size == 1) return
        webViews.remove(id)?.destroy()
        tabs = tabs.close(id)
        val active = tabs.active
        currentUrl = active.url
        address = TextFieldValue(active.url.orEmpty())
        webView = webViews[active.id]
        loadFailure = null
    }

    BackHandler(enabled = currentUrl != null) { goBack() }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) AppHeader("Browser", privateMode = true)
        Row(
            Modifier.fillMaxWidth().background(SdmBackground).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = ::goBack, modifier = Modifier.size(width = 38.dp, height = 44.dp)) { Icon(SdmIcons.Back, "Back", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(19.dp)) }
            Row(
                Modifier.weight(1f).height(46.dp).background(sdmColor(0xFF292B2F, 0xFFE8EAED), CircleShape).padding(start = 13.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SdmIcons.Lock, null, tint = Color(0xFF8AB4A0), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate(address.text) }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 12.sp),
                    cursorBrush = SolidColor(SdmGold),
                    decorationBox = { inner ->
                        Box {
                            if (address.text.isEmpty()) Text("Search or enter address", color = SdmMuted, fontSize = 12.sp, maxLines = 1)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                        .focusRequester(addressFocusRequester)
                        .onFocusChanged { addressFocused = it.isFocused }
                        .pointerInput(Unit) {
                            var lastTapUp = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val secondTap = lastTapUp > 0L &&
                                    down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis
                                if (secondTap) down.consume()
                                val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                if (secondTap) {
                                    up?.consume()
                                    lastTapUp = 0L
                                    if (up != null) {
                                        addressFocusRequester.requestFocus()
                                        keyboardController?.show()
                                        // TextField's own tap handler may update selection in this frame.
                                        addressScope.launch {
                                            withFrameNanos { }
                                            address = address.copy(selection = TextRange(0, address.text.length))
                                        }
                                    }
                                } else {
                                    lastTapUp = up?.uptimeMillis ?: 0L
                                }
                            }
                        },
                )
                IconButton(onClick = {
                    loadFailure = null
                    webView?.reload()
                }, modifier = Modifier.size(38.dp)) { Icon(SdmIcons.Refresh, "Reload", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(18.dp)) }
            }
            Box(
                Modifier.size(width = 36.dp, height = 42.dp).clickable { tabsOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(21.dp).border(2.dp, sdmColor(0xFFC9CACF, 0xFF4D5156), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text(tabs.tabs.size.toString(), color = sdmColor(0xFFC9CACF, 0xFF4D5156), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = !menuOpen }, modifier = Modifier.size(width = 38.dp, height = 44.dp)) { Icon(SdmIcons.More, "Browser menu", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(19.dp)) }
                BrowserOptionsMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    desktopSite = desktopSite,
                    onNewTab = { menuOpen = false; addTab(false) },
                    onPrivateTab = { menuOpen = false; addTab(true) },
                    onDownloads = { menuOpen = false; onOpenDownloads() },
                    onHistory = { menuOpen = false; onToast("Private browsing does not save history") },
                    onFind = { menuOpen = false; findOpen = true },
                    onDesktopSite = {
                        desktopSite = !desktopSite
                        webView?.settings?.userAgentString = if (desktopSite) DESKTOP_USER_AGENT else null
                        webView?.reload()
                    },
                    onSettings = { menuOpen = false; settingsOpen = true },
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(color = SdmLine, thickness = 1.dp)

        if (findOpen) {
            Row(
                Modifier.fillMaxWidth().background(sdmColor(0xFF191A1D, 0xFFF6F3EC))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SdmIcons.Search, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        webView?.findAllAsync(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = SdmText, fontSize = 12.sp),
                    cursorBrush = SolidColor(SdmGold),
                    decorationBox = { inner ->
                        Box(Modifier.padding(start = 10.dp)) {
                            if (findQuery.isEmpty()) Text("Find in page", color = SdmMuted, fontSize = 12.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { webView?.findNext(false) }, modifier = Modifier.size(32.dp)) {
                    Icon(SdmIcons.Back, "Previous match", tint = SdmMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { webView?.findNext(true) }, modifier = Modifier.size(32.dp)) {
                    Icon(SdmIcons.Chevron, "Next match", tint = SdmMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = {
                    findOpen = false
                    findQuery = ""
                    webView?.clearMatches()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(SdmIcons.Close, "Close find", tint = SdmMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (currentUrl == null || !privateReady) {
            BrowserLanding(onOpen = ::navigate)
        } else {
            Box(Modifier.fillMaxSize().padding(bottom = 98.dp)) {
                key(tabs.activeId) { AndroidView(
                    factory = { context ->
                        webViews[tabs.activeId] ?: WebView(context).apply {
                            configurePrivateBrowserWebView(this)
                            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                                val sourceUrl = this.url ?: url
                                onDownloadRequested(
                                    BrowserDownloadHandoffPolicy.fromUserSelectedWebViewDownload(
                                        url = url,
                                        userAgent = userAgent,
                                        contentDisposition = contentDisposition,
                                        mimeType = mimeType,
                                        contentLength = contentLength,
                                    ).copy(
                                        requestContext = ScopedRequestContext(
                                            originUrl = url,
                                            cookie = CookieManager.getInstance().getCookie(url),
                                            userAgent = userAgent,
                                            referer = sourceUrl,
                                        ),
                                    ),
                                )
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    if (!BrowserTrackingProtection.shouldBlock(request.url.toString())) return null
                                    return WebResourceResponse(
                                        "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)),
                                    )
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    loadFailure = null
                                    currentUrl = url
                                    if (!addressFocused) address = TextFieldValue(url)
                                    tabs = tabs.update(tabs.activeId, url, view.title ?: tabs.active.title)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    currentUrl = url
                                    if (!addressFocused) address = TextFieldValue(url)
                                    tabs = tabs.update(tabs.activeId, url, view.title ?: url)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame) return
                                    loadFailure = BrowserLoadFailure(
                                        request.url.toString(),
                                        browserFailureMessage(error.errorCode, error.description?.toString()),
                                    )
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse,
                                ) {
                                    if (!request.isForMainFrame || errorResponse.statusCode < 400) return
                                    loadFailure = BrowserLoadFailure(
                                        request.url.toString(),
                                        "The page returned error ${errorResponse.statusCode}.",
                                    )
                                }
                            }
                            webView = this
                            webViews[tabs.activeId] = this
                            loadUrl(currentUrl!!)
                        }
                    },
                    update = { view ->
                        webView = view
                        val requested = currentUrl ?: return@AndroidView
                        if (view.url != requested) view.loadUrl(requested)
                    },
                    modifier = Modifier.fillMaxSize(),
                ) }
                loadFailure?.let { failure ->
                    BrowserFailureCard(failure.message) {
                        loadFailure = null
                        webView?.reload()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        BrowserPrivacySession.begin { privateReady = true }
        onDispose {
            BrowserPrivacySession.end(webViews.values)
            webViews.clear()
            webView = null
        }
    }

    if (tabsOpen) {
        BrowserTabsSheet(
            snapshot = tabs,
            onSelect = ::selectTab,
            onClose = ::closeTab,
            onNewTab = { addTab(false) },
            onNewPrivateTab = { addTab(true) },
            onDismiss = { tabsOpen = false },
        )
    }
    if (settingsOpen) {
        BrowserSettingsSheet(
            onDismiss = { settingsOpen = false },
            onClearData = {
                webViews.values.forEach { it.clearHistory(); it.clearCache(true); it.clearFormData() }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                onToast("Browsing data cleared")
                settingsOpen = false
            },
            onToast = onToast,
        )
    }
}

@Composable
private fun BrowserFailureCard(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(SdmBackground), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(58.dp)
                    .background(sdmColor(0xFF211F16, 0xFFF2EAD2), CircleShape)
                    .border(1.dp, SdmGold.copy(alpha = .48f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("!", color = SdmGoldHigh, fontSize = 24.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(14.dp))
            Text("Page unavailable", color = SdmText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(message, color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
            Button(
                onClick = retry,
                colors = ButtonDefaults.buttonColors(containerColor = SdmGold, contentColor = Color.Black),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(top = 18.dp).height(38.dp),
            ) { Text("Try again", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun BrowserTabsSheet(
    snapshot: BrowserTabsSnapshot,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320)) }
    fun closeThen(action: () -> Unit) {
        scope.launch {
            progress.animateTo(0f, tween(200))
            action()
        }
    }
    Dialog(
        onDismissRequest = { closeThen(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().background(Color.Black.copy(alpha = .72f * progress.value))
                .clickable { closeThen(onDismiss) },
        ) {
            Surface(
                color = sdmColor(0xFF17181A, 0xFFFFFFFF),
                contentColor = SdmText,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, SdmGold.copy(alpha = .35f)),
                shadowElevation = 18.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = designOverlayBottomInset()).widthIn(max = 560.dp)
                    .graphicsLayer {
                        translationY = (1f - progress.value) * 96.dp.toPx()
                        scaleX = .985f + .015f * progress.value
                        scaleY = .985f + .015f * progress.value
                        alpha = .72f + .28f * progress.value
                    }
                    .clickable {},
            ) {
                Column(Modifier.padding(17.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
                            .size(width = 42.dp, height = 4.dp)
                            .background(sdmColor(0xFF514F48, 0xFFB8B2A7), CircleShape),
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("OPEN PAGES", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp)
                            Text("Your tabs", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            Text("Switch, close, or start a new browsing session.", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Box(
                            Modifier.size(40.dp).background(sdmColor(0xFF222326, 0xFFECE8DF), RoundedCornerShape(12.dp)).clickable { closeThen(onDismiss) },
                            contentAlignment = Alignment.Center,
                        ) { Text("×", color = SdmMuted, fontSize = 22.sp) }
                    }
                    Column(
                        Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        snapshot.tabs.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { tab ->
                                    BrowserTabCard(
                                        tab = tab,
                                        active = tab.id == snapshot.activeId,
                                        canClose = snapshot.tabs.size > 1,
                                        onSelect = { closeThen { onSelect(tab.id) } },
                                        onClose = { onClose(tab.id) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val newTabBorder = SdmGold.copy(alpha = .35f)
                            Box(
                                Modifier.weight(1f).heightIn(min = 144.dp)
                                    .background(sdmColor(0xFF121315, 0xFFFBFAF6), RoundedCornerShape(16.dp))
                                    .drawBehind {
                                        drawRoundRect(
                                            color = newTabBorder,
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                                        )
                                    }
                                    .clickable { closeThen(onNewTab) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(40.dp).background(sdmColor(0xFF252318, 0xFFF2EAD2), CircleShape), contentAlignment = Alignment.Center) {
                                        Text("+", color = SdmGoldHigh, fontSize = 24.sp)
                                    }
                                    Text("New tab", color = SdmText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                                    Text("Start browsing", color = SdmMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Surface(onClick = { closeThen(onNewPrivateTab) }, color = Color.Transparent, contentColor = SdmText,
                            shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, SdmLine),
                            modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(SdmIcons.PrivateTab, null, modifier = Modifier.size(16.dp))
                                Text("New private tab", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTabCard(
    tab: BrowserTab,
    active: Boolean,
    canClose: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onSelect,
        color = sdmColor(0xFF111214, 0xFFFBFAF6),
        contentColor = SdmText,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (active) SdmGold else SdmLine),
        modifier = modifier.height(145.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(92.dp)
                    .background(sdmColor(0xFF080909, 0xFFECE8DF), RoundedCornerShape(11.dp)),
            ) {
                Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(9.dp).background(sdmColor(0xFF27292D, 0xFFD8D2C4), RoundedCornerShape(5.dp)))
                    Box(Modifier.fillMaxWidth().weight(1f).background(if (tab.id == "tab-2") sdmColor(0xFF1B1B1D, 0xFFFFFFFF) else sdmColor(0xFF171812, 0xFFFFF8E5), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Text(when (tab.id) { "tab-1" -> "SD"; "tab-2" -> "A"; else -> if (tab.isPrivate) "P" else tab.title.take(1).uppercase() }, color = if (tab.id == "tab-2") sdmColor(0xFFDDDDDD, 0xFF181713) else SdmGoldHigh, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                    Box(Modifier.fillMaxWidth().height(17.dp).background(sdmColor(0xFF24262A, 0xFFD8D2C4), RoundedCornerShape(5.dp)))
                }
                if (canClose) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(27.dp).background(Color(0xB8080808), CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Text("×", color = Color(0xFFDDDDDD), fontSize = 18.sp) }
                }
            }
            Text(tab.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 2.dp, top = 7.dp))
            Text(tab.url?.substringAfter("://")?.substringBefore('/') ?: "Ready to browse", color = SdmMuted, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp, top = 3.dp))
        }
    }
}

@Composable
private fun BrowserLanding(onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 36.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(58.dp).background(sdmColor(0xFF14140F, 0xFFF2EAD2), CircleShape).border(1.dp, SdmGold.copy(alpha = .48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("SD", color = SdmGoldHigh, fontSize = 22.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(12.dp))
        Text("Browse privately.", color = SdmText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("A focused private browser built into SDM.", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        Spacer(Modifier.height(24.dp))
        Text("QUICK ACCESS", color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile("V", "Vimeo", "https://vimeo.com", Modifier.weight(1f), onOpen)
            QuickTile("A", "Archive", "https://archive.org", Modifier.weight(1f), onOpen)
            QuickTile("S", "Sound", "https://soundcloud.com", Modifier.weight(1f), onOpen)
        }
    }
}

@Composable
private fun QuickTile(letter: String, label: String, url: String, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(modifier.height(76.dp).clickable { onOpen(url) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(46.dp).background(sdmColor(0xFF211F16, 0xFFF2EAD2), CircleShape).border(1.dp, SdmLine, CircleShape), contentAlignment = Alignment.Center) {
                Text(letter, color = SdmGoldHigh, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 10.sp)
    }
}
