package com.espitman.sdm.ui.theme

import android.app.Activity
import com.espitman.sdm.data.settings.SettingsRepository
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LocalSdmLightTheme = staticCompositionLocalOf { false }
val SdmIsLight: Boolean @Composable get() = LocalSdmLightTheme.current

@Composable
fun sdmColor(dark: Long, light: Long): Color = Color(if (SdmIsLight) light else dark)

val SdmBackground: Color @Composable get() = sdmColor(0xFF070707, 0xFFF4F1E9)
val SdmSurface: Color @Composable get() = sdmColor(0xFF121315, 0xFFFFFFFF)
val SdmSurfaceAlt: Color @Composable get() = sdmColor(0xFF191A1C, 0xFFECE8DF)
val SdmGold: Color @Composable get() = sdmColor(0xFFD4AF37, 0xFFB68B12)
val SdmGoldHigh: Color @Composable get() = sdmColor(0xFFF3D675, 0xFF8B6804)
val SdmText: Color @Composable get() = sdmColor(0xFFF5F1E8, 0xFF181713)
val SdmMuted: Color @Composable get() = sdmColor(0xFF9A978F, 0xFF6D6960)
val SdmLine: Color @Composable get() = sdmColor(0xFF2D2C28, 0xFFD8D2C4)
val SdmSuccess: Color @Composable get() = sdmColor(0xFF70C69B, 0xFF247A52)
val SdmDanger: Color @Composable get() = sdmColor(0xFFEF756B, 0xFFC2473E)

@Composable
fun SdmTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repository = remember(context) { SettingsRepository.get(context) }
    val settings by repository.settings.collectAsState()
    val light = settings.theme == "light"
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    CompositionLocalProvider(LocalSdmLightTheme provides light) {
        val colors = if (light) lightColorScheme() else darkColorScheme()
        MaterialTheme(
            colorScheme = colors.copy(
                primary = SdmGold, onPrimary = Color(0xFF080808), secondary = SdmGoldHigh,
                background = SdmBackground, onBackground = SdmText, surface = SdmSurface,
                onSurface = SdmText, surfaceVariant = SdmSurfaceAlt, onSurfaceVariant = SdmMuted,
                outline = SdmLine, error = SdmDanger,
            ),
            typography = SdmTypography,
            content = content,
        )
    }
}
