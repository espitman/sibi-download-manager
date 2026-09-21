package com.espitman.sdm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SdmBackground = Color(0xFF070707)
val SdmSurface = Color(0xFF121315)
val SdmSurfaceAlt = Color(0xFF191A1C)
val SdmGold = Color(0xFFD4AF37)
val SdmGoldHigh = Color(0xFFF3D675)
val SdmText = Color(0xFFF5F1E8)
val SdmMuted = Color(0xFF9A978F)
val SdmLine = Color(0xFF2D2C28)
val SdmSuccess = Color(0xFF70C69B)
val SdmDanger = Color(0xFFEF756B)

private val DarkColors = darkColorScheme(
    primary = SdmGold,
    onPrimary = Color(0xFF080808),
    secondary = SdmGoldHigh,
    background = SdmBackground,
    onBackground = SdmText,
    surface = SdmSurface,
    onSurface = SdmText,
    surfaceVariant = SdmSurfaceAlt,
    onSurfaceVariant = SdmMuted,
    outline = SdmLine,
    error = SdmDanger,
)

@Composable
fun SdmTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = SdmTypography,
        content = content,
    )
}
