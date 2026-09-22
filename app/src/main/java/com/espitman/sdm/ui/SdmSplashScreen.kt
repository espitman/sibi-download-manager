package com.espitman.sdm.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.espitman.sdm.R

private val SplashBackground = Color(0xFF070707)
private val SplashGold = Color(0xFFD4AF37)
private val SplashGoldHigh = Color(0xFFF3D675)

@Composable
internal fun SdmLaunchLayer(content: @Composable () -> Unit) {
    var splashVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = splashVisible,
            exit = fadeOut(tween(durationMillis = 260, easing = LinearEasing)),
        ) {
            SdmSplashScreen(onAnimationFinished = { splashVisible = false })
        }
    }
}

@Composable
private fun SdmSplashScreen(onAnimationFinished: () -> Unit) {
    val arc = remember { Animatable(0f) }
    val centerBar = remember { Animatable(0f) }
    val sideBars = remember { Animatable(0f) }
    val landing = remember { Animatable(0f) }
    val loading = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { arc.animateTo(1f, tween(720, easing = FastOutSlowInEasing)) }
            launch { delay(120); centerBar.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            launch { delay(230); sideBars.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
            launch { delay(520); landing.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
            launch { delay(690); loading.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
        }
        delay(170)
        onAnimationFinished()
    }

    Box(Modifier.fillMaxSize().background(SplashBackground)) {
            Canvas(Modifier.align(Alignment.Center).offset(y = (-104.2).dp).size(206.dp)) {
                val stroke = 3.dp.toPx()
                val arcInset = 14.dp.toPx()
                drawArc(
                    color = SplashGold.copy(alpha = .18f),
                    startAngle = 137f,
                    sweepAngle = 78f * arc.value,
                    useCenter = false,
                    topLeft = Offset(arcInset, arcInset),
                    size = Size(size.width - arcInset * 2, size.height - arcInset * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = SplashGold,
                    startAngle = -102f,
                    sweepAngle = 205f * arc.value,
                    useCenter = false,
                    topLeft = Offset(arcInset, arcInset),
                    size = Size(size.width - arcInset * 2, size.height - arcInset * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = SplashGold.copy(alpha = .32f),
                    startAngle = 142f,
                    sweepAngle = 64f * arc.value,
                    useCenter = false,
                    topLeft = Offset(38.dp.toPx(), 38.dp.toPx()),
                    size = Size(size.width - 76.dp.toPx(), size.height - 76.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )

                fun drawBlade(centerX: Float, top: Float, bottom: Float, width: Float, reveal: Float, color: Color) {
                    val animatedTop = bottom - (bottom - top) * reveal
                    val tip = 8.dp.toPx() * reveal
                    val path = Path().apply {
                        moveTo(centerX - width / 2f, animatedTop)
                        lineTo(centerX + width / 2f, animatedTop)
                        lineTo(centerX + width / 2f, bottom - tip)
                        lineTo(centerX, bottom)
                        lineTo(centerX - width / 2f, bottom - tip)
                        close()
                    }
                    drawPath(path, color)
                }

                val centerX = size.width / 2f
                val centerBottom = 137.dp.toPx()
                drawBlade(centerX, 61.dp.toPx(), centerBottom, 12.dp.toPx(), centerBar.value, SplashGold)
                drawBlade(centerX - 24.dp.toPx(), 82.dp.toPx(), 127.dp.toPx(), 9.dp.toPx(), sideBars.value, SplashGold)
                drawBlade(centerX + 24.dp.toPx(), 82.dp.toPx(), 127.dp.toPx(), 9.dp.toPx(), sideBars.value, SplashGold)

                val lineHalf = 25.dp.toPx() * landing.value
                drawLine(SplashGoldHigh, Offset(centerX - lineHalf, 151.dp.toPx()), Offset(centerX + lineHalf, 151.dp.toPx()), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Square)
            }
        SplashWordmark(Modifier.align(Alignment.Center))

        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).width(220.dp).height(2.dp).background(Color(0xFF242424), CircleShape),
        ) {
            Box(Modifier.fillMaxWidth(loading.value).height(2.dp).background(SplashGold, CircleShape))
        }
    }
}

@Composable
private fun SplashWordmark(modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Android rasterizes the native splash at the default icon size, expands the
    // foreground by 1.5, then scales it to the no-background size. Reuse that
    // rasterization so handing off to Compose keeps the lettering stationary.
    val wordmark = remember(context, density.density) {
        fun platformDimension(name: String, fallbackDp: Float): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else (fallbackDp * density.density).toInt()
        }
        val sourceSize = platformDimension("starting_surface_default_icon_size", 108f)
        val finalSize = (platformDimension("starting_surface_icon_size", 160f) * 1.2f).toInt()
        val bitmap = Bitmap.createBitmap(sourceSize, sourceSize, Bitmap.Config.ARGB_8888)
        val center = sourceSize / 2
        val halfForeground = (sourceSize * .75f).toInt()
        context.getDrawable(R.drawable.splash_wordmark)!!.mutate().apply {
            setBounds(center - halfForeground, center - halfForeground, center + halfForeground, center + halfForeground)
            draw(AndroidCanvas(bitmap))
        }
        bitmap.asImageBitmap() to (finalSize / density.density).dp
    }
    Image(wordmark.first, contentDescription = "SDM · Sibi Download Manager", modifier = modifier.size(wordmark.second))
}
