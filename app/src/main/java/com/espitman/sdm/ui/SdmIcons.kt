package com.espitman.sdm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The same 24-unit outline artwork used by the approved design. */
internal object SdmIcons {
    val Download = outline("Download", "M12 3v12m0 0 5-5m-5 5-5-5M5 20h14")
    val Browser = outline("Browser", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0M15 9l-2 4-4 2 2-4z")
    val Folder = outline("Folder", "M3 7h7l2 2h9v10H3zM3 7V5h7l2 2")
    val Settings = outline("Settings", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0M5 12a7 7 0 1 1 14 0a7 7 0 1 1-14 0M12 2v3M12 19v3M2 12h3M19 12h3")
    val Add = outline("Add", "M12 5v14M5 12h14")
    val Search = outline("Search", "M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0M16 16l4 4")
    val More = ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString("M13 5a1 1 0 1 1-2 0a1 1 0 1 1 2 0M13 12a1 1 0 1 1-2 0a1 1 0 1 1 2 0M13 19a1 1 0 1 1-2 0a1 1 0 1 1 2 0").toNodes(), fill = SolidColor(Color.Black))
    }.build()
    val Pause = outline("Pause", "M8 6v12M16 6v12", 2f)
    val Play = outline("Play", "M8 5l10 7-10 7z", 2f)
    val DownloadAll = outline("DownloadAll", "M7 4v10m0 0-3-3m3 3 3-3M17 4v10m0 0-3-3m3 3 3-3M4 20h16")
    val Sort = outline("Sort", "M7 5v14m0 0-3-3m3 3 3-3M13 7h7M13 12h5M13 17h3")
    val Back = outline("Back", "M15 18l-6-6 6-6")
    val Chevron = outline("Chevron", "M9 6l6 6-6 6")
    val Lock = outline("Lock", "M7 11h10v8H7zM9 11V8a3 3 0 0 1 6 0v3")
    val Refresh = outline("Refresh", "M5 12a7 7 0 1 0 2-5M5 4v5h5")
    val Connections = outline("Connections", "M6 5v14M12 5v14M18 5v14")
    val Simultaneous = outline("Simultaneous", "M8 4v10m0 0-3-3m3 3 3-3M16 4v10m0 0-3-3m3 3 3-3M5 19h14")
    val Wifi = outline("Wifi", "M5 12.5a10 10 0 0 1 14 0M8 16a6 6 0 0 1 8 0M11 19.5a2 2 0 0 1 2 0")
    val Notifications = outline("Notifications", "M6 16h12l-2-3V9a4 4 0 0 0-8 0v4zM10 19h4")
    val Speed = outline("Speed", "M4 12h3l2-5 4 10 2-5h5")
    val Theme = outline("Theme", "M12 3a9 9 0 1 0 9 9c-5 2-9-2-9-9z")
    val Language = outline("Language", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0M4 12h16M12 4c3 3 3 13 0 16M12 4c-3 3-3 13 0 16")
    val Info = outline("Info", "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0M12 11v5M12 8h.01")
    val Close = outline("Close", "M7 7l10 10M17 7L7 17", 2f)
    val Paste = outline("Paste", "M9 5h6v3H9zM7 6H5v15h14V6h-2", 2f)

    private fun outline(name: String, path: String, width: Float = 1.8f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}
