package com.espitman.sdm.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The Open Design viewport reserves 52 dp above headers and 18 dp below the dock.
@Composable
internal fun designHeaderInset(): Dp {
    val density = LocalDensity.current
    val systemInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    return (52.dp - systemInset).coerceAtLeast(0.dp)
}

@Composable
internal fun designDockInset(): Dp {
    val density = LocalDensity.current
    val systemInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return (18.dp - systemInset).coerceAtLeast(0.dp)
}

@Composable
internal fun designOverlayBottomInset(): Dp {
    val density = LocalDensity.current
    val systemInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return (68.dp - systemInset).coerceAtLeast(0.dp)
}
