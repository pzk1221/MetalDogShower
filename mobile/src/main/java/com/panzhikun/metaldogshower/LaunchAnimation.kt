package com.panzhikun.metaldogshower

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val LaunchNavy = Color(0xFF111638)
private val LaunchWaterEdge = Color(0xFF4B538B)
private val WaterRiseEasing = CubicBezierEasing(0.58f, 0.02f, 0.16f, 1f)
private val WaterSettleEasing = CubicBezierEasing(0.20f, 0.75f, 0.24f, 1f)

/**
 * A code-only liquid wipe. The navy water recedes upward and settles into the exact header
 * silhouette used by the home screen, so revealing the live UI does not introduce a hard cut.
 */
@Composable
internal fun AnimatedAppLaunch(
    playAnimation: Boolean,
    content: @Composable () -> Unit,
) {
    var showingLaunch by remember { mutableStateOf(playAnimation) }

    Box(Modifier.fillMaxSize()) {
        content()
        if (showingLaunch) {
            WaterLaunchOverlay(onFinished = { showingLaunch = false })
        }
    }
}

@Composable
private fun WaterLaunchOverlay(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()
    val headerContentHeightPx = with(density) { 110.dp.toPx() }
    val cornerRadiusPx = with(density) { 28.dp.toPx() }
    val overshootPx = with(density) { 15.dp.toPx() }
    val initialExtraPx = with(density) { 72.dp.toPx() }
    val maxWavePx = with(density) { 34.dp.toPx() }
    val edgeStrokePx = with(density) { 1.5.dp.toPx() }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1_650, easing = LinearEasing),
        )
        onFinished()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Once the water has settled over the identically colored live header,
                // fade only this paint layer so the real title and mark appear underneath.
                val reveal = ((progress.value - 0.89f) / 0.11f).coerceIn(0f, 1f)
                alpha = 1f - smoothStep(reveal)
            },
    ) {
        val t = progress.value
        val finalBottom = statusBarHeightPx + headerContentHeightPx
        val travelPhase = (t / 0.78f).coerceIn(0f, 1f)
        val travel = WaterRiseEasing.transform(travelPhase)
        val overshootBottom = finalBottom - overshootPx
        var waterBottom = lerp(size.height + initialExtraPx, overshootBottom, travel)

        if (t > 0.78f) {
            val settlePhase = ((t - 0.78f) / 0.11f).coerceIn(0f, 1f)
            waterBottom = lerp(
                overshootBottom,
                finalBottom,
                WaterSettleEasing.transform(settlePhase),
            )
        }

        val shapePhase = smoothStep(((t - 0.46f) / 0.43f).coerceIn(0f, 1f))
        val cornerRadius = cornerRadiusPx * shapePhase
        val waveDecay = 1f - smoothStep((t / 0.80f).coerceIn(0f, 1f))
        val waveAmplitude = maxWavePx * waveDecay
        val body = waterBodyPath(
            width = size.width,
            bottom = waterBottom,
            cornerRadius = cornerRadius,
            waveAmplitude = waveAmplitude,
        )
        drawPath(body, LaunchNavy)

        if (waveAmplitude > 0.5f && waterBottom < size.height + maxWavePx) {
            drawPath(
                waterSurfacePath(
                    width = size.width,
                    bottom = waterBottom,
                    cornerRadius = cornerRadius,
                    waveAmplitude = waveAmplitude,
                ),
                color = LaunchWaterEdge.copy(alpha = 0.34f * waveDecay),
                style = Stroke(width = edgeStrokePx),
            )
        }
    }
}

private fun waterBodyPath(
    width: Float,
    bottom: Float,
    cornerRadius: Float,
    waveAmplitude: Float,
): Path = Path().apply {
    val radius = cornerRadius.coerceAtMost(width / 2f)
    moveTo(0f, 0f)
    lineTo(width, 0f)
    lineTo(width, bottom - radius)
    quadraticTo(width, bottom, width - radius, bottom)
    cubicTo(
        width * 0.79f,
        bottom + waveAmplitude * 0.58f,
        width * 0.64f,
        bottom - waveAmplitude,
        width * 0.50f,
        bottom,
    )
    cubicTo(
        width * 0.37f,
        bottom + waveAmplitude * 0.82f,
        width * 0.21f,
        bottom - waveAmplitude * 0.62f,
        radius,
        bottom,
    )
    quadraticTo(0f, bottom, 0f, bottom - radius)
    close()
}

private fun waterSurfacePath(
    width: Float,
    bottom: Float,
    cornerRadius: Float,
    waveAmplitude: Float,
): Path = Path().apply {
    val radius = cornerRadius.coerceAtMost(width / 2f)
    moveTo(width - radius, bottom)
    cubicTo(
        width * 0.79f,
        bottom + waveAmplitude * 0.58f,
        width * 0.64f,
        bottom - waveAmplitude,
        width * 0.50f,
        bottom,
    )
    cubicTo(
        width * 0.37f,
        bottom + waveAmplitude * 0.82f,
        width * 0.21f,
        bottom - waveAmplitude * 0.62f,
        radius,
        bottom,
    )
}

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction
