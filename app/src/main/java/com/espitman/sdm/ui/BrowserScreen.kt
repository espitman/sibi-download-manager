package com.espitman.sdm.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.espitman.sdm.ui.theme.SdmBackground
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserScreen() {
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = currentUrl != null) {
        if (webView?.canGoBack() == true) webView?.goBack() else currentUrl = null
    }

    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        AppHeader("Browser", privateMode = true)
        Row(
            Modifier.fillMaxWidth().background(sdmColor(0xFF191A1D, 0xFFF8F9FA)).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = {
                if (webView?.canGoBack() == true) webView?.goBack() else currentUrl = null
            }, modifier = Modifier.size(width = 38.dp, height = 44.dp)) { Icon(SdmIcons.Back, "Back", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(19.dp)) }
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
                    keyboardActions = KeyboardActions(onGo = {
                        val input = address.trim()
                        if (input.isNotEmpty()) currentUrl = normalizeUrl(input)
                        focusManager.clearFocus()
                    }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = SdmText, fontSize = 12.sp),
                    cursorBrush = SolidColor(SdmGold),
                    decorationBox = { inner ->
                        Box {
                            if (address.isEmpty()) Text("Search or enter address", color = SdmMuted, fontSize = 12.sp, maxLines = 1)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(38.dp)) { Icon(SdmIcons.Refresh, "Reload", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(18.dp)) }
            }
            Box(
                Modifier.size(width = 36.dp, height = 42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(21.dp).border(2.dp, sdmColor(0xFFC9CACF, 0xFF4D5156), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text("1", color = sdmColor(0xFFC9CACF, 0xFF4D5156), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            IconButton(onClick = {}, modifier = Modifier.size(width = 38.dp, height = 44.dp)) { Icon(SdmIcons.More, "Browser menu", tint = sdmColor(0xFFB7B9BD, 0xFF4D5156), modifier = Modifier.size(19.dp)) }
        }
        androidx.compose.material3.HorizontalDivider(color = SdmLine, thickness = 1.dp)

        if (currentUrl == null) {
            BrowserLanding(onOpen = {
                address = it
                currentUrl = it
            })
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {}
                        webView = this
                        loadUrl(currentUrl!!)
                    }
                },
                update = { view ->
                    webView = view
                    if (view.url != currentUrl) view.loadUrl(currentUrl!!)
                },
                modifier = Modifier.fillMaxSize().padding(bottom = 98.dp),
            )
        }
    }
}

private fun normalizeUrl(input: String): String = when {
    input.startsWith("http://") || input.startsWith("https://") -> input
    input.contains('.') && !input.contains(' ') -> "https://$input"
    else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
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
