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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen(
    showHeader: Boolean = true,
    onDownloadRequested: (BrowserDownloadRequest) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onActivePrivacyChange: (Boolean) -> Unit = {},
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val preferencesStore = remember(context) { BrowserPreferencesStore.get(context) }
    var preferences by remember { mutableStateOf(preferencesStore.read()) }
    val startup = remember {
        resolveStartupBrowserSession(
            firstLaunch = !preferencesStore.hasPersistedSession(),
            persisted = preferencesStore.readSession(),
            privateByDefault = preferences.privateByDefault,
        )
    }
    var tabs by remember { mutableStateOf(BrowserTabsSnapshot(startup.tabs, startup.activeId)) }
    var nextTabOrdinal by remember { mutableIntStateOf(startup.nextTabOrdinal) }
    var tabsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(preferencesStore.readHistory()) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var sessionReady by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(TextFieldValue(tabs.active.url.orEmpty())) }
    var addressFocused by remember { mutableStateOf(false) }
    var currentUrl by remember {
        mutableStateOf(if (preferencesStore.hasPersistedSession()) tabs.active.url else null)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val webViews = remember { mutableMapOf<String, WebView>() }
    var loadFailure by remember { mutableStateOf<BrowserLoadFailure?>(null) }
    var pendingNavigation by remember { mutableStateOf<BrowserPendingNavigation?>(null) }
    val focusManager = LocalFocusManager.current
    val addressFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val addressScope = rememberCoroutineScope()
    val blurHideJob = remember { arrayOf<Job?>(null) }
    val blockTrackers = remember { AtomicBoolean(preferences.blockTrackers) }
    val tabsRef = remember { arrayOf(tabs) }
    val ordinalRef = remember { intArrayOf(nextTabOrdinal) }
    val historyRef = remember { arrayOf(history) }
    tabsRef[0] = tabs
    ordinalRef[0] = nextTabOrdinal
    historyRef[0] = history
    blockTrackers.set(preferences.blockTrackers)
    SideEffect { onActivePrivacyChange(tabs.active.isPrivate) }

    fun persistPreferences(next: BrowserPreferences) {
        preferences = next
        preferencesStore.write(next)
        blockTrackers.set(next.blockTrackers)
    }

    fun persistHistory(next: List<BrowserHistoryEntry>) {
        history = next
        historyRef[0] = next
        preferencesStore.writeHistory(next)
    }

    fun hideAddressChrome() {
        blurHideJob[0]?.cancel()
        blurHideJob[0] = null
        addressFocused = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun recordVisit(url: String, title: String, isPrivate: Boolean) {
        if (!BrowserWebViewCallbackPolicy.shouldRecordFinishedVisit(isPrivate)) return
        val next = recordBrowserHistoryVisit(
            entries = historyRef[0],
            url = url,
            title = title,
            isPrivate = isPrivate,
            persistHistory = BrowserPrivacyPolicy.forTab(isPrivate).persistHistory,
            visitedAt = System.currentTimeMillis(),
        )
        if (next != historyRef[0]) persistHistory(next)
    }

    fun replaceTabs(next: BrowserTabsSnapshot) {
        tabs = next
        tabsRef[0] = next
    }

    fun navigate(input: String, exactUrl: Boolean = false) {
        val resolved = if (exactUrl && isRecordableBrowserHistoryUrl(input.trim())) {
            input.trim()
        } else {
            normalizeBrowserInput(input, preferences.searchEngine)
        }
        resolved?.let { url ->
            loadFailure = null
            currentUrl = url
            address = TextFieldValue(url)
            val tabId = tabs.activeId
            replaceTabs(tabs.update(tabId, url, tabs.active.title))
            val target = webViews[tabId] ?: webView
            if (target != null) {
                target.loadUrl(url)
                pendingNavigation = null
            } else {
                pendingNavigation = BrowserPendingNavigation(tabId, url)
            }
        }
        hideAddressChrome()
    }

    fun selectAddressSuggestion(entry: BrowserHistoryEntry) {
        val url = browserAddressSuggestionDestination(entry)
        address = TextFieldValue(url, TextRange(url.length))
        navigate(url, exactUrl = true)
    }

    fun goBack() {
        hideAddressChrome()
        if (webView?.canGoBack() == true) {
            loadFailure = null
            webView?.goBack()
        } else {
            currentUrl = null
            address = TextFieldValue("")
            replaceTabs(tabs.update(tabs.activeId, null, if (tabs.active.isPrivate) "Private tab" else "New tab"))
            loadFailure = null
        }
    }

    fun selectTab(id: String) {
        hideAddressChrome()
        replaceTabs(tabs.select(id))
        val active = tabs.active
        currentUrl = active.url
        address = TextFieldValue(active.url.orEmpty())
        webView = webViews[id]
        loadFailure = null
        tabsOpen = false
    }

    fun addTab(explicitPrivate: Boolean) {
        hideAddressChrome()
        val id = "tab-${nextTabOrdinal++}"
        replaceTabs(tabs.add(createBrowserTab(id, explicitPrivate)))
        currentUrl = null
        address = TextFieldValue("")
        webView = null
        loadFailure = null
        tabsOpen = false
    }

    fun closeTab(id: String) {
        if (tabs.tabs.size == 1) return
        hideAddressChrome()
        webViews.remove(id)?.destroy()
        replaceTabs(tabs.close(id))
        val active = tabs.active
        currentUrl = active.url
        address = TextFieldValue(active.url.orEmpty())
        webView = webViews[active.id]
        loadFailure = null
    }

    val addressSuggestions = remember(history, address.text, tabs.active.isPrivate, addressFocused) {
        suggestBrowserHistory(
            entries = history,
            query = address.text,
            isPrivate = tabs.active.isPrivate,
            focused = addressFocused,
        )
    }

    BackHandler(enabled = shouldDismissBrowserAddressSuggestionsOnBack(addressSuggestions.isNotEmpty()) || currentUrl != null) {
        if (shouldDismissBrowserAddressSuggestionsOnBack(addressSuggestions.isNotEmpty())) {
            hideAddressChrome()
        } else {
            goBack()
        }
    }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        if (showHeader) AppHeader("Browser", privateMode = tabs.active.isPrivate, showSearch = false, showMore = false)
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
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                blurHideJob[0]?.cancel()
                                blurHideJob[0] = null
                                addressFocused = true
                            } else {
                                blurHideJob[0]?.cancel()
                                blurHideJob[0] = addressScope.launch {
                                    delay(160)
                                    addressFocused = false
                                }
                            }
                        }
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
                    desktopSite = tabs.active.desktopSite,
                    privateSession = tabs.active.isPrivate,
                    onNewTab = { menuOpen = false; addTab(false) },
                    onPrivateTab = { menuOpen = false; addTab(true) },
                    onDownloads = { menuOpen = false; onOpenDownloads() },
                    onHistory = {
                        menuOpen = false
                        if (browserHistoryMenuOpensSheet(tabs.active.isPrivate)) {
                            historyOpen = true
                        } else {
                            onToast("Private browsing does not save history")
                        }
                    },
                    onFind = { menuOpen = false; findOpen = true },
                    onDesktopSite = {
                        val id = tabs.activeId
                        val next = !tabs.active.desktopSite
                        replaceTabs(tabs.setDesktopSite(id, next))
                        val target = webViews[id] ?: webView
                        if (target != null) {
                            applyBrowserWebViewDisplayMode(target, next)
                            target.reload()
                        }
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (currentUrl == null || !sessionReady) {
                BrowserLanding(isPrivate = tabs.active.isPrivate, onOpen = ::navigate)
            } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = BrowserWebViewLayoutPolicy.DOCK_CLEARANCE_DP.dp),
            ) {
                key(tabs.activeId) { AndroidView(
                    factory = { context ->
                        val tabId = tabs.activeId
                        val tabIsPrivate = tabs.active.isPrivate
                        val hosted = webViews[tabId] ?: WebView(context).apply {
                            val tabDesktop = tabs.tabs.firstOrNull { it.id == tabId }?.desktopSite == true
                            configureBrowserWebView(this, tabIsPrivate, tabDesktop)
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
                                    if (!shouldBlockBrowserTracker(request.url.toString(), blockTrackers.get())) return null
                                    return WebResourceResponse(
                                        "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)),
                                    )
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    val snapshot = tabsRef[0]
                                    if (BrowserWebViewCallbackPolicy.appliesToActiveChrome(tabId, snapshot.activeId)) {
                                        loadFailure = null
                                        currentUrl = url
                                        if (!addressFocused) address = TextFieldValue(url)
                                    }
                                    val existingTitle = snapshot.tabs.firstOrNull { it.id == tabId }?.title
                                    replaceTabs(
                                        snapshot.update(
                                            tabId,
                                            url,
                                            BrowserWebViewCallbackPolicy.resolvedTabTitle(view.title, existingTitle, url),
                                        ),
                                    )
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    val snapshot = tabsRef[0]
                                    if (BrowserWebViewCallbackPolicy.appliesToActiveChrome(tabId, snapshot.activeId)) {
                                        currentUrl = url
                                        if (!addressFocused) address = TextFieldValue(url)
                                    }
                                    val existingTitle = snapshot.tabs.firstOrNull { it.id == tabId }?.title
                                    val title = BrowserWebViewCallbackPolicy.resolvedTabTitle(
                                        view.title,
                                        existingTitle,
                                        url,
                                    )
                                    replaceTabs(snapshot.update(tabId, url, title))
                                    if (snapshot.tabs.any { it.id == tabId }) {
                                        recordVisit(url, title, tabIsPrivate)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!BrowserWebViewCallbackPolicy.appliesToActiveLoadFailure(
                                            tabId,
                                            tabsRef[0].activeId,
                                            request.isForMainFrame,
                                        )
                                    ) return
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
                                    if (errorResponse.statusCode < 400) return
                                    if (!BrowserWebViewCallbackPolicy.appliesToActiveLoadFailure(
                                            tabId,
                                            tabsRef[0].activeId,
                                            request.isForMainFrame,
                                        )
                                    ) return
                                    loadFailure = BrowserLoadFailure(
                                        request.url.toString(),
                                        "The page returned error ${errorResponse.statusCode}.",
                                    )
                                }
                            }
                            webView = this
                            webViews[tabId] = this
                            loadUrl(currentUrl!!)
                            if (BrowserWebViewCallbackPolicy.shouldConsumePendingNavigation(pendingNavigation, tabId)) {
                                pendingNavigation = null
                            }
                        }
                        hostPrivateBrowserWebView(context, hosted)
                    },
                    update = { host ->
                        val view = host.getChildAt(0) as WebView
                        webView = view
                        val visibleId = tabs.activeId
                        applyBrowserWebViewDisplayMode(view, tabs.active.desktopSite)
                        val pending = pendingNavigation
                        BrowserWebViewCallbackPolicy.urlToLoadOnComposeUpdate(pending, visibleId, view.url)
                            ?.let(view::loadUrl)
                        if (BrowserWebViewCallbackPolicy.shouldConsumePendingNavigation(pending, visibleId)) {
                            pendingNavigation = null
                        }
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
            if (addressSuggestions.isNotEmpty()) {
                BrowserAddressSuggestionSurface(
                    entries = addressSuggestions,
                    onSelect = ::selectAddressSuggestion,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 10.dp, end = 10.dp, top = 4.dp)
                        .zIndex(2f),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        if (shouldClearBrowserDataOnStart(preferences, tabs.tabs)) {
            BrowserPrivacySession.begin { sessionReady = true }
        } else {
            sessionReady = true
        }
        onDispose {
            val latestTabs = tabsRef[0]
            val latestPrefs = preferencesStore.read()
            val clearData = shouldClearBrowserDataOnExit(latestPrefs, latestTabs.tabs)
            BrowserPrivacySession.end(webViews.values, clearData = clearData)
            if (shouldClearBrowserHistoryOnExit(latestPrefs)) {
                preferencesStore.writeHistory(emptyList())
            }
            preferencesStore.writeSession(
                sessionToPersistOnExit(latestPrefs, latestTabs, ordinalRef[0]),
            )
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
            preferences = preferences,
            onDismiss = { settingsOpen = false },
            onClearData = {
                webViews.values.forEach { it.clearHistory(); it.clearCache(true); it.clearFormData() }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                persistHistory(emptyList())
                onToast("Browsing data cleared")
                settingsOpen = false
            },
            onPreferencesChange = ::persistPreferences,
            onToast = onToast,
        )
    }
    if (historyOpen) {
        BrowserHistorySheet(
            entries = history,
            onDismiss = { historyOpen = false },
            onOpen = { url ->
                historyOpen = false
                navigate(url)
            },
            onClear = {
                persistHistory(emptyList())
                onToast("Browsing history cleared")
            },
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
    val sheetHost = rememberSdmSheetHost()
    val motion = rememberSdmSheetMotion(sheetHost.visible)
    var panelHeight by remember { mutableIntStateOf(0) }
    val extraTravel = with(LocalDensity.current) { 24.dp.toPx() }
    fun closeThen(action: () -> Unit) {
        sheetHost.dismissThen(action)
    }
    Dialog(
        onDismissRequest = { closeThen(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogWindowDim()
        Box(
            Modifier.fillMaxSize().statusBarsPadding().background(sdmSheetScrim(motion.scrim))
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
                    .onSizeChanged { panelHeight = it.height }
                    .sdmSheetPanel(motion, panelHeight, extraTravel)
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
private fun BrowserLanding(isPrivate: Boolean, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 36.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(58.dp).background(sdmColor(0xFF14140F, 0xFFF2EAD2), CircleShape).border(1.dp, SdmGold.copy(alpha = .48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("SD", color = SdmGoldHigh, fontSize = 22.sp, fontWeight = FontWeight.Black) }
        Spacer(Modifier.height(12.dp))
        Text(if (isPrivate) "Browse privately." else "Start browsing.", color = SdmText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (isPrivate) "A focused private browser built into SDM." else "A focused browser built into SDM.",
            color = SdmMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 7.dp),
        )
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
