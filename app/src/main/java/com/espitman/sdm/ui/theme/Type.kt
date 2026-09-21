package com.espitman.sdm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.espitman.sdm.R

val SdmFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

private val BodyStyle = TextStyle(
    fontFamily = SdmFontFamily,
    fontSize = 13.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val MaterialDefaults = Typography()

private fun TextStyle.withSdmFont() = copy(
    fontFamily = SdmFontFamily,
    lineHeight = TextUnit.Unspecified,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val SdmTypography = Typography(
    displayLarge = MaterialDefaults.displayLarge.withSdmFont(),
    displayMedium = MaterialDefaults.displayMedium.withSdmFont(),
    displaySmall = MaterialDefaults.displaySmall.withSdmFont(),
    headlineLarge = MaterialDefaults.headlineLarge.withSdmFont(),
    headlineMedium = MaterialDefaults.headlineMedium.withSdmFont(),
    bodyLarge = BodyStyle,
    headlineSmall = TextStyle(
        fontFamily = SdmFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    titleLarge = MaterialDefaults.titleLarge.withSdmFont(),
    titleMedium = TextStyle(
        fontFamily = SdmFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    titleSmall = MaterialDefaults.titleSmall.withSdmFont(),
    bodyMedium = TextStyle(
        fontFamily = SdmFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    bodySmall = MaterialDefaults.bodySmall.withSdmFont(),
    labelLarge = MaterialDefaults.labelLarge.withSdmFont(),
    labelMedium = TextStyle(
        fontFamily = SdmFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ),
    labelSmall = MaterialDefaults.labelSmall.withSdmFont(),
)
