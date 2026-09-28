package com.espitman.sdm.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SDM_SHEET_TRAVEL_MS = 320
internal const val SDM_SHEET_OPACITY_MS = 220
internal const val SDM_SHEET_SCRIM_MS = 200

private val SdmSheetTravelEasing = CubicBezierEasing(.2f, .82f, .24f, 1f)

internal data class SdmSheetMotion(
    val travel: Float,
    val opacity: Float,
    val scrim: Float,
)

@Composable
internal fun rememberSdmSheetMotion(visible: Boolean): SdmSheetMotion {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val target = if (entered && visible) 1f else 0f
    val travel by animateFloatAsState(
        target,
        tween(SDM_SHEET_TRAVEL_MS, easing = SdmSheetTravelEasing),
        label = "sdm-sheet-travel",
    )
    val opacity by animateFloatAsState(
        target,
        tween(SDM_SHEET_OPACITY_MS),
        label = "sdm-sheet-opacity",
    )
    val scrim by animateFloatAsState(
        target,
        tween(SDM_SHEET_SCRIM_MS),
        label = "sdm-sheet-scrim",
    )
    return SdmSheetMotion(travel, opacity, scrim)
}

internal fun sdmSheetScrim(scrim: Float): Color = Color.Black.copy(alpha = .72f * scrim)

internal fun Modifier.sdmSheetPanel(
    motion: SdmSheetMotion,
    panelHeightPx: Int,
    extraTravelPx: Float,
): Modifier = graphicsLayer {
    translationY = (panelHeightPx + extraTravelPx) * (1f - motion.travel)
    scaleX = .985f + .015f * motion.travel
    scaleY = scaleX
    alpha = .72f + .28f * motion.opacity
    transformOrigin = TransformOrigin(0.5f, 1f)
}

internal class SdmSheetHost(private val scope: CoroutineScope) {
    var visible by mutableStateOf(true)
        private set
    private var closing = false

    fun dismissThen(action: () -> Unit) {
        if (closing) return
        closing = true
        visible = false
        scope.launch {
            delay(SDM_SHEET_TRAVEL_MS.toLong())
            action()
        }
    }
}

@Composable
internal fun rememberSdmSheetHost(): SdmSheetHost {
    val scope = rememberCoroutineScope()
    return remember(scope) { SdmSheetHost(scope) }
}

@Composable
internal fun DisableDialogWindowDim() {
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
}
